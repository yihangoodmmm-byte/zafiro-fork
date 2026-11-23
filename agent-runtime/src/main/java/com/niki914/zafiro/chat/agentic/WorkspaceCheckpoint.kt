package com.niki914.zafiro.chat.agentic

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

internal enum class WorkspaceChangeKind { Added, Modified, Deleted }
internal data class WorkspaceFileChange(val path: String, val kind: WorkspaceChangeKind, val before: ByteArray?, val after: ByteArray?)

/** Bounded in-memory capture. A partial scan cannot prove additions or deletions. */
internal class WorkspaceCheckpoint private constructor(
    private val root: Path,
    private val files: Map<String, ByteArray>,
    private val omitted: Set<String>,
    val complete: Boolean,
) {
    fun compare(after: WorkspaceCheckpoint): List<WorkspaceFileChange> {
        require(root == after.root)
        if (!complete || !after.complete) return emptyList()
        return (files.keys + after.files.keys).sorted().mapNotNull { path ->
            if (isOmitted(path) || after.isOmitted(path)) return@mapNotNull null
            val old = files[path]
            val current = after.files[path]
            when {
                old == null && current != null -> WorkspaceFileChange(path, WorkspaceChangeKind.Added, null, current)
                old != null && current == null -> WorkspaceFileChange(path, WorkspaceChangeKind.Deleted, old, null)
                old != null && current != null && !old.contentEquals(current) ->
                    WorkspaceFileChange(path, WorkspaceChangeKind.Modified, old, current)
                else -> null
            }
        }
    }

    private fun isOmitted(path: String): Boolean {
        var candidate = path
        while (candidate.isNotEmpty()) {
            if (candidate in omitted) return true
            candidate = candidate.substringBeforeLast('/', "")
        }
        return false
    }

    companion object {
        private const val MAX_ENTRIES = 10_000
        private const val MAX_FILE = 128 * 1024
        private const val MAX_BYTES = 4 * 1024 * 1024
        private val excludedDirectories = setOf("build", "node_modules", "dist", "target", "vendor")

        private val sourceExtensions = setOf(
            "kt", "kts", "java", "rs", "go", "py", "js", "mjs", "cjs", "jsx", "ts", "tsx", "css", "scss",
            "html", "htm", "md", "txt", "c", "cc", "cpp", "h", "hpp", "swift", "rb", "sh", "sql",
        )

        suspend fun capture(directory: String): WorkspaceCheckpoint {
            val context = coroutineContext
            val root = File(directory).toPath().toRealPath()
            require(Files.isDirectory(root) && root.parent != null)
            val files = linkedMapOf<String, ByteArray>()
            val omitted = mutableSetOf<String>()
            var remaining = MAX_BYTES
            var visited = 0
            var complete = true
            fun inspect(): Boolean {
                context.ensureActive()
                if (++visited > MAX_ENTRIES) complete = false
                return complete
            }
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (!inspect()) return FileVisitResult.TERMINATE
                    val name = dir.fileName.toString()
                    return if (dir != root && (name.startsWith('.') || name in excludedDirectories)) {
                        FileVisitResult.SKIP_SUBTREE
                    } else FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (!inspect()) return FileVisitResult.TERMINATE
                    val path = root.relativize(file).toString()
                    val name = file.fileName.toString().lowercase()
                    if (!attrs.isRegularFile || name.startsWith('.') || name.substringAfterLast('.', "") !in sourceExtensions ||
                        attrs.size() > MAX_FILE) {
                        omitted += path
                        return FileVisitResult.CONTINUE
                    }
                    val bytes = try {
                        Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { input ->
                            val buffer = ByteArray(MAX_FILE + 1)
                            var count = 0
                            while (count < buffer.size) {
                                val read = input.read(buffer, count, buffer.size - count)
                                if (read < 0) break
                                count += read
                            }
                            buffer.copyOf(count)
                        }
                    } catch (_: IOException) {
                        omitted += path
                        return FileVisitResult.CONTINUE
                    }
                    if (bytes.size > MAX_FILE || !isUtf8Text(bytes)) {
                        omitted += path
                        return FileVisitResult.CONTINUE
                    }
                    remaining -= bytes.size
                    if (remaining < 0) {
                        complete = false
                        return FileVisitResult.TERMINATE
                    }
                    files[path] = bytes
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    complete = false
                    return FileVisitResult.TERMINATE
                }
            })
            return WorkspaceCheckpoint(root, files, omitted, complete)
        }

        private fun isUtf8Text(bytes: ByteArray): Boolean {
            if (bytes.any { it == 0.toByte() }) return false
            return try {
                Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes))
                true
            } catch (_: CharacterCodingException) {
                false
            }
        }
    }
}
