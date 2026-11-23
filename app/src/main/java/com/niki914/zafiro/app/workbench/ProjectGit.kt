package com.niki914.zafiro.app.workbench

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.EmptyCommitException
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.treewalk.filter.PathFilter

internal enum class GitChangeKind { Added, Modified, Deleted, Untracked, Conflict }
internal data class ProjectGitChange(val path: String, val kind: GitChangeKind, val staged: Boolean)
internal data class ProjectGitStatus(val branch: String, val changes: List<ProjectGitChange>)
internal data class ProjectDiff(val text: String, val truncated: Boolean)

internal class ProjectGit(private val root: File) {
    private fun open(): Git? {
        val directory = File(root, ".git")
        if (!directory.isDirectory || directory.canonicalFile != directory.absoluteFile) return null
        return Git.open(directory)
    }

    fun status(): ProjectGitStatus? = open()?.use { git ->
        val status = git.status().call()
        val changes = buildList {
            status.added.forEach { add(ProjectGitChange(it, GitChangeKind.Added, true)) }
            status.changed.forEach { add(ProjectGitChange(it, GitChangeKind.Modified, true)) }
            status.removed.forEach { add(ProjectGitChange(it, GitChangeKind.Deleted, true)) }
            status.modified.forEach { add(ProjectGitChange(it, GitChangeKind.Modified, false)) }
            status.missing.forEach { add(ProjectGitChange(it, GitChangeKind.Deleted, false)) }
            status.untracked.forEach { add(ProjectGitChange(it, GitChangeKind.Untracked, false)) }
            status.conflicting.forEach { add(ProjectGitChange(it, GitChangeKind.Conflict, false)) }
        }
        ProjectGitStatus(git.repository.branch.orEmpty(), changes.sortedWith(compareBy({ it.path }, { !it.staged })))
    }

    fun stage(change: ProjectGitChange) {
        require(!change.staged && change.kind != GitChangeKind.Conflict)
        require(!change.path.startsWith('/') && change.path.split('/').none { it.isBlank() || it == ".." || it == "." })
        val git = open() ?: error("No repository")
        git.use {
            if (change.kind == GitChangeKind.Deleted) it.add().setUpdate(true).addFilepattern(change.path).call()
            else it.add().addFilepattern(change.path).call()
        }
    }

    fun unstage(change: ProjectGitChange) {
        require(change.staged && change.kind != GitChangeKind.Conflict)
        require(!change.path.startsWith('/') && change.path.split('/').none { it.isBlank() || it == ".." || it == "." })
        val git = open() ?: error("No repository")
        git.use {
            if (it.repository.resolve("HEAD") == null) {
                it.rm().setCached(true).addFilepattern(change.path).call()
            } else {
                it.reset().setRef("HEAD").addPath(change.path).call()
            }
        }
    }

    fun commit(message: String) {
        require(message.isNotBlank())
        val git = open() ?: error("No repository")
        git.use {
            val config = it.repository.config
            if (config.getString("user", null, "name").isNullOrBlank() ||
                config.getString("user", null, "email").isNullOrBlank()) {
                throw ProjectFileException(ProjectFileProblem.GitIdentity)
            }
            try {
                it.commit().setAllowEmpty(false).setMessage(message).call()
            } catch (_: EmptyCommitException) {
                throw ProjectFileException(ProjectFileProblem.GitEmptyIndex)
            }
        }
    }

    fun diff(change: ProjectGitChange): ProjectDiff? {
        if (change.kind == GitChangeKind.Untracked) {
            val text = ProjectFiles.open(root.path).read(change.path).text
            return ProjectDiff(text.take(MAX_DIFF_BYTES), text.length > MAX_DIFF_BYTES)
        }
        return open()?.use { git ->
            val output = DiffOutput()
            val entries = git.diff().setCached(change.staged).setPathFilter(PathFilter.create(change.path)).call()
            DiffFormatter(output).use { formatter ->
                formatter.setRepository(git.repository)
                formatter.setBinaryFileThreshold(1024 * 1024)
                formatter.format(entries)
                formatter.flush()
            }
            ProjectDiff(output.data.toString(Charsets.UTF_8.name()), output.truncated)
        }
    }

    private class DiffOutput : OutputStream() {
        val data = ByteArrayOutputStream()
        var truncated = false
            private set

        override fun write(value: Int) {
            if (data.size() >= MAX_DIFF_BYTES) truncated = true else data.write(value)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            val accepted = minOf(length, MAX_DIFF_BYTES - data.size())
            if (accepted > 0) data.write(bytes, offset, accepted)
            if (accepted < length) truncated = true
        }
    }

    private companion object { const val MAX_DIFF_BYTES = 96 * 1024 }
}
