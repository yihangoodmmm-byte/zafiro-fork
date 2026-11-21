package com.niki914.zafiro.app.ui.devtools

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFileAttributeView
import java.util.UUID

internal const val PATCH_PANEL_MAX_DIFF = 256 * 1024
private const val MAX_FILE = 2 * 1024 * 1024
private const val MAX_BASELINES = 8 * 1024 * 1024

internal enum class PatchPanelProblem { FORMAT, LIMIT, TEXT, PATH, CONTEXT, STALE, UNSUPPORTED, IO }

internal class PatchPanelFailure(val problem: PatchPanelProblem) : IOException()

private fun patchCheck(ok: Boolean, problem: PatchPanelProblem) {
    if (!ok) throw PatchPanelFailure(problem)
}

internal fun patchPanelRead(input: InputStream, maxBytes: Int): ByteArray {
    val result = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer, 0, minOf(buffer.size, maxBytes + 1 - result.size()))
        if (count == -1) return result.toByteArray()
        patchCheck(result.size() + count <= maxBytes, PatchPanelProblem.LIMIT)
        result.write(buffer, 0, count)
    }
}

internal fun patchPanelDecode(bytes: ByteArray): String {
    val text = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        throw PatchPanelFailure(PatchPanelProblem.TEXT)
    }
    patchCheck('\u0000' !in text, PatchPanelProblem.TEXT)
    return text
}

internal data class PatchPanelLine(val kind: Char, val text: String, val terminated: Boolean = true)

internal data class PatchPanelHunk(
    val header: String,
    val oldOffset: Int,
    val oldCount: Int,
    val newOffset: Int,
    val newCount: Int,
    val lines: List<PatchPanelLine>,
)

internal data class PatchPanelFile(val relativePath: String, val hunks: List<PatchPanelHunk>)

private val hunkHeader = Regex("""@@ -(\d{1,8})(?:,(\d{1,8}))? \+(\d{1,8})(?:,(\d{1,8}))? @@(?: .*)?""")
private val indexHeader = Regex("""index [0-9a-f]+\.\.[0-9a-f]+(?: 100(?:644|755))?""")
private const val NO_NEWLINE = "\\ No newline at end of file"

internal fun patchPanelParse(diff: String): List<PatchPanelFile> {
    patchCheck(diff.length <= PATCH_PANEL_MAX_DIFF && diff.toByteArray().size <= PATCH_PANEL_MAX_DIFF, PatchPanelProblem.LIMIT)
    val lines = diff.removePrefix("\uFEFF").split('\n').let { if (it.lastOrNull() == "") it.dropLast(1) else it }
        .map { it.removeSuffix("\r") }
    patchCheck(lines.size in 1..24_000, PatchPanelProblem.LIMIT)
    patchCheck(lines.none { '\r' in it || '\u0000' in it }, PatchPanelProblem.TEXT)
    var cursor = 0
    var totalHunks = 0
    val paths = mutableSetOf<String>()
    val files = mutableListOf<PatchPanelFile>()
    while (cursor < lines.size) {
        if (lines[cursor].startsWith("diff --git ")) {
            cursor++
            if (lines.getOrNull(cursor)?.matches(indexHeader) == true) cursor++
        }
        val old = patchHeaderPath(lines.getOrNull(cursor++), "--- ")
        val new = patchHeaderPath(lines.getOrNull(cursor++), "+++ ")
        val path = when {
            old == new -> old
            old.startsWith("a/") && new.startsWith("b/") && old.drop(2) == new.drop(2) -> old.drop(2)
            else -> throw PatchPanelFailure(PatchPanelProblem.UNSUPPORTED)
        }
        requirePatchRelativePath(path)
        patchCheck(paths.add(path), PatchPanelProblem.FORMAT)
        patchCheck(paths.size <= 32, PatchPanelProblem.LIMIT)
        val hunks = mutableListOf<PatchPanelHunk>()
        var previousEnd = 0
        var delta = 0
        while (lines.getOrNull(cursor)?.startsWith("@@ ") == true) {
            val header = lines[cursor++]
            patchCheck(header.none { it != '	' && (it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt()) },
                PatchPanelProblem.FORMAT)
            val match = hunkHeader.matchEntire(header) ?: throw PatchPanelFailure(PatchPanelProblem.FORMAT)
            val (oldLine, oldSize, newLine, newSize) = match.destructured
            val oldCount = oldSize.toIntOrNull() ?: 1
            val newCount = newSize.toIntOrNull() ?: 1
            val oldOffset = oldLine.toInt() - if (oldCount > 0) 1 else 0
            val newOffset = newLine.toInt() - if (newCount > 0) 1 else 0
            patchCheck(++totalHunks <= 256, PatchPanelProblem.LIMIT)
            patchCheck(oldOffset >= previousEnd && newOffset >= 0 && newOffset == oldOffset + delta, PatchPanelProblem.FORMAT)
            var consumedOld = 0
            var consumedNew = 0
            val body = mutableListOf<PatchPanelLine>()
            while (consumedOld < oldCount || consumedNew < newCount) {
                val line = lines.getOrNull(cursor++) ?: throw PatchPanelFailure(PatchPanelProblem.FORMAT)
                val kind = line.firstOrNull()
                if (kind == null || kind !in " +-" || (kind != '+' && consumedOld == oldCount) ||
                    (kind != '-' && consumedNew == newCount)
                ) throw PatchPanelFailure(PatchPanelProblem.FORMAT)
                if (kind != '+') consumedOld++
                if (kind != '-') consumedNew++
                val terminated = lines.getOrNull(cursor) != NO_NEWLINE
                if (!terminated) cursor++
                body += PatchPanelLine(kind, line.drop(1), terminated)
            }
            patchCheck(body.any { it.kind != ' ' }, PatchPanelProblem.FORMAT)
            hunks += PatchPanelHunk(header, oldOffset, oldCount, newOffset, newCount, body)
            previousEnd = oldOffset + oldCount
            delta += newCount - oldCount
        }
        patchCheck(hunks.isNotEmpty(), PatchPanelProblem.FORMAT)
        files += PatchPanelFile(path, hunks)
    }
    return files
}

private fun patchHeaderPath(line: String?, prefix: String): String {
    if (line == null || !line.startsWith(prefix)) throw PatchPanelFailure(PatchPanelProblem.FORMAT)
    val path = line.removePrefix(prefix).substringBefore('\t')
    patchCheck(path != "/dev/null" && !path.startsWith('"'), PatchPanelProblem.UNSUPPORTED)
    requirePatchRelativePath(path)
    return path
}

private fun requirePatchRelativePath(path: String) {
    patchCheck(path.isNotBlank() && !path.startsWith('/') && path.none {
        it == '\\' || it == ':' || it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt()
    } && path.split('/').none { it.isEmpty() || it == "." || it == ".." || it.equals(".git", ignoreCase = true) }, PatchPanelProblem.PATH)
}

private data class PatchPanelTextLine(val text: String, val terminated: Boolean)

private class PatchPanelText(bytes: ByteArray) {
    private val text = patchPanelDecode(bytes)
    private val separator = if ("\r\n" in text) "\r\n" else "\n"
    private val lines = text.splitToSequence('\n').let { parts ->
        val chunks = parts.toList()
        chunks.mapIndexedNotNull { index, line ->
            val terminated = index < chunks.lastIndex
            val content = if (terminated && separator == "\r\n") {
                patchCheck(line.endsWith('\r'), PatchPanelProblem.TEXT)
                line.dropLast(1)
            } else line
            patchCheck('\r' !in content, PatchPanelProblem.TEXT)
            if (!terminated && content.isEmpty()) null else PatchPanelTextLine(content, terminated)
        }
    }

    fun render(hunks: List<PatchPanelHunk>, applied: Set<Int>): ByteArray {
        val output = StringBuilder(text.length)
        var terminated = true
        fun append(line: PatchPanelTextLine) {
            patchCheck(terminated, PatchPanelProblem.CONTEXT)
            output.append(line.text)
            if (line.terminated) output.append(separator)
            terminated = line.terminated
        }
        var cursor = 0
        hunks.forEachIndexed { index, hunk ->
            patchCheck(hunk.oldOffset <= lines.size && hunk.oldCount <= lines.size - hunk.oldOffset, PatchPanelProblem.CONTEXT)
            while (cursor < hunk.oldOffset) append(lines[cursor++])
            val selected = index in applied
            for ((kind, content, hasNewline) in hunk.lines) {
                val line = PatchPanelTextLine(content, hasNewline)
                if (kind != '+') patchCheck(lines[cursor++] == line, PatchPanelProblem.CONTEXT)
                if ((selected && kind != '-') || (!selected && kind != '+')) append(line)
            }
        }
        while (cursor < lines.size) append(lines[cursor++])
        return output.toString().toByteArray().also { patchCheck(it.size <= MAX_FILE, PatchPanelProblem.LIMIT) }
    }
}

private data class PatchPanelStamp(val key: Any, val modified: FileTime, val size: Long)
private data class PatchPanelSnapshot(val stamp: PatchPanelStamp, val bytes: ByteArray)

private fun BasicFileAttributes.patchStamp(): PatchPanelStamp {
    patchCheck(isRegularFile, PatchPanelProblem.PATH)
    return PatchPanelStamp(fileKey() ?: throw PatchPanelFailure(PatchPanelProblem.UNSUPPORTED), lastModifiedTime(), size())
}

internal class PatchPanelReview private constructor(
    private val root: Path,
    val files: List<PatchPanelFile>,
    private val baselines: List<PatchPanelText>,
    private val snapshots: List<PatchPanelSnapshot>,
    private val applied: List<Set<Int>>,
) {
    fun isApplied(file: Int, hunk: Int): Boolean = hunk in applied[file]

    fun source(file: Int, hunk: Int): Pair<String, Int> {
        val item = files[file]
        val selected = item.hunks[hunk]
        val priorDelta = applied[file].sumOf { if (it < hunk) item.hunks[it].newCount - item.hunks[it].oldCount else 0 }
        val context = selected.lines.takeWhile { it.kind == ' ' }.size
        val relative = Paths.get(item.relativePath)
        withPatchTarget(root, relative) { directory, name ->
            directory.patchCheckSnapshot(name, snapshots[file])
        }
        val bytes = snapshots[file].bytes
        val lineCount = bytes.count { it == 10.toByte() } + if (bytes.lastOrNull() == 10.toByte()) 0 else 1
        return root.resolve(relative).toString() to (selected.oldOffset + priorDelta + context + 1).coerceIn(1, lineCount)
    }

    fun write(file: Int, hunk: Int, accept: Boolean): PatchPanelReview {
        patchCheck(accept != isApplied(file, hunk), PatchPanelProblem.STALE)
        val item = files[file]
        val nowApplied = if (accept) applied[file] + hunk else applied[file] - hunk
        val bytes = baselines[file].render(item.hunks, nowApplied)
        val relative = Paths.get(item.relativePath)
        val updated = withPatchTarget(root, relative) { directory, name ->
            val original = snapshots[file]
            directory.patchCheckSnapshot(name, original)
            val parentKey = directory.patchDirectoryKey()
            val temp = Paths.get(".zafiro-patch-${UUID.randomUUID()}.tmp")
            val perms = directory.getFileAttributeView(name, PosixFileAttributeView::class.java, NOFOLLOW_LINKS)
                ?.readAttributes()?.permissions()
            val channel = directory.newByteChannel(temp, setOf(CREATE_NEW, WRITE, NOFOLLOW_LINKS))
            var moved = false
            try {
                channel.use {
                    val buffer = ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) it.write(buffer)
                }
                if (perms != null) {
                    directory.getFileAttributeView(temp, PosixFileAttributeView::class.java, NOFOLLOW_LINKS)
                        .setPermissions(perms)
                }
                val stamp = directory.patchAttributes(temp).patchStamp()
                withPatchTarget(root, relative) { currentDirectory, currentName ->
                    patchCheck(currentDirectory.patchDirectoryKey() == parentKey, PatchPanelProblem.STALE)
                    currentDirectory.patchCheckSnapshot(currentName, original)
                    directory.move(temp, currentDirectory, currentName)
                    moved = true
                }
                PatchPanelSnapshot(stamp, bytes)
            } finally {
                if (!moved) try {
                    directory.deleteFile(temp)
                } catch (_: IOException) {
                    // Preserve the original failure.
                }
            }
        }
        return PatchPanelReview(root, files, baselines, snapshots.toMutableList().also { it[file] = updated },
            applied.toMutableList().also { it[file] = nowApplied })
    }

    companion object {
        fun open(workspace: String, diff: String): PatchPanelReview {
            val root = Paths.get(workspace)
            patchCheck(root.isAbsolute && root.parent != null && root.normalize() == root, PatchPanelProblem.PATH)
            val files = patchPanelParse(diff)
            var size = 0
            val snapshots = files.map { file ->
                withPatchTarget(root, Paths.get(file.relativePath)) { directory, name ->
                    directory.patchSnapshot(name).also {
                        size += it.bytes.size
                        patchCheck(size <= MAX_BASELINES, PatchPanelProblem.LIMIT)
                    }
                }
            }
            val baselines = snapshots.map { PatchPanelText(it.bytes) }
            files.forEachIndexed { index, file -> baselines[index].render(file.hunks, file.hunks.indices.toSet()) }
            return PatchPanelReview(root, files, baselines, snapshots, files.map { emptySet() })
        }
    }
}

private fun <T> withPatchTarget(
    root: Path,
    relative: Path,
    access: (SecureDirectoryStream<Path>, Path) -> T,
): T {
    val parts = relative.toList().dropLast(1)
    patchCheck(parts.size <= 64, PatchPanelProblem.PATH)
    fun descend(directory: SecureDirectoryStream<Path>, index: Int): T {
        if (index == parts.size) return access(directory, relative.fileName)
        val part = parts[index]
        patchCheck(directory.patchAttributes(part).isDirectory, PatchPanelProblem.PATH)
        return directory.newDirectoryStream(part, NOFOLLOW_LINKS).use { descend(it, index + 1) }
    }
    // Android can permit traversal through ancestors such as /data without granting read access to them.
    val rootKey = patchRootKey(root)
    return Files.newDirectoryStream(root).use {
        val secure = it as? SecureDirectoryStream<Path> ?: throw PatchPanelFailure(PatchPanelProblem.UNSUPPORTED)
        patchCheck(secure.patchDirectoryKey() == rootKey && patchRootKey(root) == rootKey, PatchPanelProblem.STALE)
        descend(secure, 0)
    }
}

private fun patchRootKey(root: Path): Any {
    var prefix = requireNotNull(root.root)
    for (part in root) {
        prefix = prefix.resolve(part)
        val attributes = Files.readAttributes(prefix, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        patchCheck(attributes.isDirectory, PatchPanelProblem.PATH)
        if (prefix == root) return attributes.fileKey() ?: throw PatchPanelFailure(PatchPanelProblem.UNSUPPORTED)
    }
    throw PatchPanelFailure(PatchPanelProblem.PATH)
}

private fun SecureDirectoryStream<Path>.patchDirectoryKey(): Any =
    getFileAttributeView(BasicFileAttributeView::class.java).readAttributes().fileKey()
        ?: throw PatchPanelFailure(PatchPanelProblem.UNSUPPORTED)

private fun SecureDirectoryStream<Path>.patchAttributes(name: Path): BasicFileAttributes =
    getFileAttributeView(name, BasicFileAttributeView::class.java, NOFOLLOW_LINKS).readAttributes()

private fun SecureDirectoryStream<Path>.patchCheckSnapshot(name: Path, expected: PatchPanelSnapshot) {
    val current = patchSnapshot(name)
    patchCheck(current.stamp == expected.stamp && current.bytes.contentEquals(expected.bytes), PatchPanelProblem.STALE)
}

private fun SecureDirectoryStream<Path>.patchSnapshot(name: Path): PatchPanelSnapshot {
    val stamp = patchAttributes(name).patchStamp()
    patchCheck(stamp.size <= MAX_FILE, PatchPanelProblem.LIMIT)
    val bytes = newByteChannel(name, setOf(READ, NOFOLLOW_LINKS)).use { channel ->
        patchPanelRead(Channels.newInputStream(channel), MAX_FILE)
    }
    patchCheck(stamp == patchAttributes(name).patchStamp() && bytes.size.toLong() == stamp.size, PatchPanelProblem.STALE)
    return PatchPanelSnapshot(stamp, bytes)
}
