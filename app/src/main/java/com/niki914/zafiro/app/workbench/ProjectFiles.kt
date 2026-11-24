package com.niki914.zafiro.app.workbench

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

internal data class ProjectEntry(val relativePath: String, val directory: Boolean) {
    val name: String get() = relativePath.substringAfterLast('/')
}

internal data class ProjectDocument(val relativePath: String, val text: String, val revision: String)
internal data class ProjectMatch(val relativePath: String, val line: Int, val excerpt: String)
internal data class ProjectSearch(val matches: List<ProjectMatch>, val limited: Boolean)

internal enum class ProjectFileProblem { InvalidDirectory, Inaccessible, OutsideProject, TooLarge, NotText, Changed, GitIdentity, RunHistory, RestoreBusy, AlreadyExists, GitEmptyIndex }
internal class ProjectFileException(val problem: ProjectFileProblem) : IOException()

internal const val PROJECT_ARTIFACT_PATH_LIMIT = 512

internal fun validProjectArtifactPath(path: String): Boolean =
    path.isNotBlank() && path.length <= PROJECT_ARTIFACT_PATH_LIMIT &&
        path.none { it == '\\' || it.isISOControl() } &&
        path.split('/').none { it.isEmpty() || it == "." || it == ".." || it == ".git" }

/** Native-path backend. A SAF URI is not a shell working directory. */
internal class ProjectFiles private constructor(val root: File) {
    fun list(relativePath: String): List<ProjectEntry> = children(resolve(relativePath))
        .sortedWith(compareBy<ProjectEntry> { !it.directory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        .toList()

    fun createEntry(directory: String, name: String, folder: Boolean): String {
        if (name.isBlank() || name == "." || name == ".." || name == ".git" || name.any { it == '/' || it == '\\' || it.isISOControl() }) {
            fail(ProjectFileProblem.OutsideProject)
        }
        val relative = if (directory.isEmpty()) name else "$directory/$name"
        val path = resolve(relative).toPath()
        try {
            if (folder) Files.createDirectory(path) else Files.createFile(path)
        } catch (_: FileAlreadyExistsException) {
            fail(ProjectFileProblem.AlreadyExists)
        }
        return relative
    }

    fun artifactStatus(relativePath: String): ProjectArtifactStatus = try {
        if (!validProjectArtifactPath(relativePath)) ProjectArtifactStatus.Unavailable else {
            val path = resolve(relativePath).toPath()
            when {
                Files.notExists(path, LinkOption.NOFOLLOW_LINKS) -> ProjectArtifactStatus.NotReady
                !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(path) -> ProjectArtifactStatus.Unavailable
                Files.size(path) > 0L -> ProjectArtifactStatus.Verified
                else -> ProjectArtifactStatus.NotReady
            }
        }
    } catch (_: IOException) {
        ProjectArtifactStatus.Unavailable
    } catch (_: SecurityException) {
        ProjectArtifactStatus.Unavailable
    }

    fun readDiagnostic(path: String): ProjectDocument {
        val relative = if (path.startsWith('/')) {
            val prefix = root.path + "/"
            if (!path.startsWith(prefix)) fail(ProjectFileProblem.OutsideProject)
            path.removePrefix(prefix)
        } else path.removePrefix("./")
        return read(relative)
    }

    fun read(relativePath: String): ProjectDocument {
        val bytes = readBytes(resolve(relativePath))
        return ProjectDocument(relativePath, decode(bytes), revision(bytes))
    }

    fun save(document: ProjectDocument, text: String): ProjectDocument {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) fail(ProjectFileProblem.TooLarge)
        replaceChecked(document.relativePath, bytes) { current -> revision(current) == document.revision }
        return ProjectDocument(document.relativePath, text, revision(bytes))
    }

    fun restore(relativePath: String, before: String?, after: String?) {
        require(relativePath.isNotEmpty() && (before != null || after != null))
        val target = resolve(relativePath)
        val path = target.toPath()
        if (after == null) {
            if (!Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) fail(ProjectFileProblem.Changed)
            val bytes = requireNotNull(before).toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_BYTES) fail(ProjectFileProblem.TooLarge)
            try {
                Files.write(path, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            } catch (_: FileAlreadyExistsException) {
                fail(ProjectFileProblem.Changed)
            }
            return
        }
        val expected = after.toByteArray(Charsets.UTF_8)
        if (before == null) {
            val checked = resolve(relativePath)
            if (!matchesExisting(checked) { it.contentEquals(expected) }) fail(ProjectFileProblem.Changed)
            Files.delete(checked.toPath())
        } else {
            replaceChecked(relativePath, before.toByteArray(Charsets.UTF_8)) { it.contentEquals(expected) }
        }
    }

    private fun matchesExisting(target: File, matches: (ByteArray) -> Boolean): Boolean =
        Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS) && matches(readBytes(target))

    private fun replaceChecked(relative: String, bytes: ByteArray, matches: (ByteArray) -> Boolean) {
        if (bytes.size > MAX_BYTES) fail(ProjectFileProblem.TooLarge)
        val target = resolve(relative)
        if (!matchesExisting(target, matches)) fail(ProjectFileProblem.Changed)
        if (!target.canWrite()) fail(ProjectFileProblem.Inaccessible)
        val path = target.toPath()
        val temporary = Files.createTempFile(path.parent, ".zafiro-edit-", ".tmp")
        try {
            Files.write(temporary, bytes, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
            val originalMode = Files.getFileAttributeView(path, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
                ?.readAttributes()?.permissions()
            if (originalMode != null && originalMode != Files.getPosixFilePermissions(temporary, LinkOption.NOFOLLOW_LINKS)) {
                Files.setPosixFilePermissions(temporary, originalMode)
            }
            // Recheck after writing the temporary file to narrow the window for external edits.
            if (!matchesExisting(resolve(relative), matches)) fail(ProjectFileProblem.Changed)
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    fun search(query: String, checkActive: () -> Unit): ProjectSearch {
        if (query.isBlank()) return ProjectSearch(emptyList(), false)
        val pending = ArrayDeque<String>().apply { add("") }
        val matches = mutableListOf<ProjectMatch>()
        var inspected = 0
        var decoded = 0
        while (pending.isNotEmpty()) {
            checkActive()
            val relative = pending.removeFirst()
            if (++inspected > MAX_SCAN_ENTRIES) return ProjectSearch(matches, true)
            val item = resolve(relative)
            if (item.isDirectory) {
                if (item != root && (item.name.startsWith('.') || item.name in IGNORED_DIRECTORIES)) continue
                children(item, limit = MAX_SCAN_ENTRIES + 1 - pending.size).forEach { pending.add(it.relativePath) }
                continue
            }
            if (!item.isFile || item.length() > MAX_SEARCH_FILE_BYTES) continue
            if (++decoded > MAX_SCAN_FILES) return ProjectSearch(matches, true)
            val text = try { read(relative).text } catch (_: IOException) { continue }
            text.lineSequence().forEachIndexed { index, line ->
                checkActive()
                val hit = line.indexOf(query, ignoreCase = true)
                if (hit >= 0) {
                    matches += ProjectMatch(relative, index + 1, line.drop((hit - 36).coerceAtLeast(0)).take(180))
                    if (matches.size >= MAX_MATCHES) return ProjectSearch(matches, true)
                }
            }
        }
        return ProjectSearch(matches, false)
    }

    private fun children(directory: File, limit: Int = Int.MAX_VALUE): Sequence<ProjectEntry> = sequence {
        val entries = directory.listFiles() ?: fail(ProjectFileProblem.Inaccessible)
        var emitted = 0
        for (entry in entries) {
            if (emitted >= limit) break
            if (entry.name == ".git" || entry.canonicalFile != entry.absoluteFile) continue
            val relativePath = entry.relativeTo(root).invariantSeparatorsPath
            if (entry.isDirectory || entry.isFile) {
                yield(ProjectEntry(relativePath, entry.isDirectory))
                emitted++
            }
        }
    }

    private fun resolve(relative: String): File {
        if (relative.isEmpty()) return root
        if (relative.split('/').any { it.isEmpty() || it == "." || it == ".." || it == ".git" } || '\u0000' in relative) {
            fail(ProjectFileProblem.OutsideProject)
        }
        val raw = File(root, relative)
        val resolved = raw.canonicalFile
        if (resolved != raw.absoluteFile || !resolved.toPath().startsWith(root.toPath())) {
            fail(ProjectFileProblem.OutsideProject)
        }
        return resolved
    }

    private fun readBytes(file: File): ByteArray {
        if (!file.isFile || !file.canRead()) fail(ProjectFileProblem.Inaccessible)
        if (file.length() > MAX_BYTES) fail(ProjectFileProblem.TooLarge)
        return Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS).use { stream ->
            val bytes = ByteArray(MAX_BYTES + 1)
            var length = 0
            while (length < bytes.size) {
                val count = stream.read(bytes, length, bytes.size - length)
                if (count < 0) break
                length += count
            }
            if (length > MAX_BYTES) fail(ProjectFileProblem.TooLarge)
            bytes.copyOf(length)
        }
    }

    private fun decode(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString().also {
                if ('\u0000' in it) fail(ProjectFileProblem.NotText)
            }
    } catch (_: CharacterCodingException) {
        fail(ProjectFileProblem.NotText)
    }

    private fun revision(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        private const val MAX_BYTES = 1024 * 1024
        private const val MAX_SEARCH_FILE_BYTES = 256 * 1024
        private const val MAX_SCAN_ENTRIES = 12_000
        private const val MAX_SCAN_FILES = 2_000
        private const val MAX_MATCHES = 150
        private val IGNORED_DIRECTORIES = setOf("node_modules", "build", "dist", "target", "vendor")

        fun open(path: String): ProjectFiles {
            val input = File(path.trim())
            if (!input.isAbsolute) fail(ProjectFileProblem.InvalidDirectory)
            val root = input.canonicalFile
            if (!root.isDirectory || root.parentFile == null) fail(ProjectFileProblem.InvalidDirectory)
            if (!root.canRead() || !root.canExecute()) fail(ProjectFileProblem.Inaccessible)
            return ProjectFiles(root)
        }

        private fun fail(problem: ProjectFileProblem): Nothing = throw ProjectFileException(problem)
    }
}
