package com.niki914.zafiro.chat.agentic

import android.util.AtomicFile
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Opt-in local observations. A workspace delta does not prove who edited a file. */
object WorkspaceTurnReviews {
    @Serializable
    data class FileChange(val path: String, val before: String?, val after: String?)
    @Serializable
    data class Review(val id: String, val workspace: String, val finishedAt: Long, val changes: List<FileChange>?, val interrupted: Boolean)

    private val enabledPaths = MutableStateFlow<Set<String>>(emptySet())
    val enabled = enabledPaths.asStateFlow()
    private val records = MutableStateFlow<List<Review>>(emptyList())
    val reviews = records.asStateFlow()
    @Serializable
    private data class Stored(val enabled: Set<String>, val reviews: List<Review>)
    private val storageLock = Mutex()
    @Volatile private var storage: AtomicFile? = null
    private var loaded = false

    fun configureStorage(directory: File) {
        storage = AtomicFile(File(directory, "workspace-turn-reviews.json"))
    }

    private val captures = ConcurrentHashMap<String, Capture>()

    internal class Capture {
        val overlap = AtomicBoolean(false)
        var initial: WorkspaceCheckpoint? = null
    }

    suspend fun load() = withContext(Dispatchers.IO) {
        storageLock.withLock {
            if (loaded) return@withLock
            val file = storage ?: return@withLock
            val saved = runCatching { Json.decodeFromString<Stored>(file.readFully().decodeToString()) }.getOrNull()
            if (saved != null) {
                enabledPaths.value = saved.enabled
                records.value = saved.reviews.take(4)
            }
            loaded = true
        }
    }

    suspend fun setEnabled(workspace: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        load()
        storageLock.withLock {
            val paths = if (enabled) enabledPaths.value + workspace else enabledPaths.value - workspace
            val rows = if (enabled) records.value else records.value.filterNot { it.workspace == workspace }
            persist(Stored(paths, rows))
            if (!enabled) captures[workspace]?.overlap?.set(true)
            enabledPaths.value = paths
            records.value = rows
        }
    }

    private fun persist(value: Stored) {
        val atomic = storage ?: return
        val bytes = Json.encodeToString(value).encodeToByteArray()
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
    }

    fun hasActiveCapture(workspace: String): Boolean = captures.containsKey(workspace)

    internal suspend fun begin(workspace: String): Capture? {
        load()
        if (workspace !in enabled.value) return null
        val capture = Capture()
        val existing = captures.putIfAbsent(workspace, capture)
        if (existing != null) {
            existing.overlap.set(true)
            return null
        }
        try {
            capture.initial = snapshot(workspace)
            return capture
        } catch (cancelled: CancellationException) {
            captures.remove(workspace, capture)
            throw cancelled
        }
    }

    internal suspend fun finish(workspace: String, capture: Capture, interrupted: Boolean) {
        try {
            val before = capture.initial
            val after = if (before?.complete == true && !capture.overlap.get()) snapshot(workspace) else null
            val changes = if (before != null && after?.complete == true && !capture.overlap.get()) {
                before.compare(after).map { FileChange(it.path, it.before?.decodeToString(), it.after?.decodeToString()) }
            } else null
            withContext(Dispatchers.IO) {
                storageLock.withLock {
                    if (workspace in enabled.value) {
                        val review = Review(UUID.randomUUID().toString(), workspace, System.currentTimeMillis(), changes, interrupted)
                        val rows = (listOf(review) + records.value).take(4)
                        runCatching { persist(Stored(enabled.value, rows)) }
                        records.value = rows
                    }
                }
            }
        } finally {
            captures.remove(workspace, capture)
        }
    }

    private suspend fun snapshot(workspace: String): WorkspaceCheckpoint? = try {
        WorkspaceCheckpoint.capture(workspace)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}
