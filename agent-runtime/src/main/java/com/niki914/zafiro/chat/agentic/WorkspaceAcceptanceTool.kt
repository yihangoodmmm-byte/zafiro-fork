package com.niki914.zafiro.chat.agentic

import com.niki914.okia.message.ToolCallOutcome
import com.niki914.okia.tooling.ToolCallContext
import com.niki914.okia.tooling.ToolDescriptor
import com.niki914.okia.tooling.ToolExecutor
import com.niki914.okia.tooling.ToolKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes

internal class WorkspaceAcceptanceTool(private val workspace: String) : ToolExecutor {
    override suspend fun execute(call: ToolCallContext): ToolCallOutcome {
        if (call.argumentsJson.length > 4096) return ToolCallOutcome.Failure("Acceptance input is too long.")
        val args = runCatching { Json.parseToJsonElement(call.argumentsJson) }.getOrNull() as? JsonObject
            ?: return ToolCallOutcome.Failure("Expected an object.")
        fun text(key: String) = (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val path = text("path") ?: return ToolCallOutcome.Failure("Set path.")
        val kind = text("condition") ?: return ToolCallOutcome.Failure("Set condition.")
        val expected = text("expected")
        if (path.length !in 1..512 || path.any { it.isISOControl() } || File(path).isAbsolute ||
            path.split('/').any { it.isEmpty() || it.startsWith('.') } ||
            kind !in setOf("nonempty", "contains_text") ||
            (kind == "contains_text" && (expected == null || expected.length !in 1..512))
        ) return ToolCallOutcome.Failure("Invalid path or condition.")
        for (attempt in 1..3) {
            val result = withContext(Dispatchers.IO) { checkFile(path, kind, expected) }
            if (result == "passed" || result == "unavailable" || attempt == 3) {
                return ToolCallOutcome.Success("""{"source":"workspace_observation","status":"$result","attempts":$attempt}""")
            }
            delay(1000)
        }
        error("Unreachable")
    }

    private fun checkFile(relative: String, kind: String, expected: String?): String = try {
        val root = File(workspace).canonicalFile
        val file = File(root, relative)
        val target = file.toPath()
        if (root.path != workspace || file.canonicalFile != file.absoluteFile || !target.startsWith(root.toPath())) {
            throw IOException("Path is outside workspace")
        }
        val attrs = Files.readAttributes(target, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        when {
            !attrs.isRegularFile || !Files.isReadable(target) -> "unavailable"
            attrs.size() == 0L -> "not_met"
            kind == "nonempty" -> "passed"
            attrs.size() > 65536 -> "unavailable"
            else -> Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS).use { stream ->
                val buffer = ByteArray(65537)
                var size = 0
                while (size < buffer.size) {
                    val count = stream.read(buffer, size, buffer.size - size)
                    if (count <= 0) break
                    size += count
                }
                val bytes = buffer.copyOf(size)
                if (bytes.size > 65536) "unavailable"
                else if (bytes.decodeToString(throwOnInvalidSequence = true).contains(requireNotNull(expected))) "passed"
                else "not_met"
            }
        }
    } catch (_: NoSuchFileException) {
        "not_met"
    } catch (_: IOException) {
        "unavailable"
    } catch (_: SecurityException) {
        "unavailable"
    }

    override fun onInterrupt(call: ToolCallContext) = ToolCallOutcome.Interrupted()

    companion object {
        val descriptor = ToolDescriptor(
            name = "zafiro_workspace_acceptance",
            description = "Independently check a workspace file. A mismatch is rechecked twice; no operation is replayed. Status passed applies only to the requested condition.",
            inputSchemaJson = """{"type":"object","properties":{"path":{"type":"string"},"condition":{"type":"string","enum":["nonempty","contains_text"]},"expected":{"type":"string"}},"required":["path","condition"]}""",
            kind = ToolKind.Local,
        )
    }
}
