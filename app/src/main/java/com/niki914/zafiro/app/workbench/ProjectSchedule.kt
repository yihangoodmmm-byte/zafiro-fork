package com.niki914.zafiro.app.workbench

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.Operation
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal class ProjectSchedule(context: Context, private val path: String) {
    private val manager = WorkManager.getInstance(context)
    private val history = ProjectRunHistory(context)
    private val id = MessageDigest.getInstance("SHA-256").digest(path.encodeToByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private val name = "project-schedule-$id"
    private val historyKey = "scheduled:$id"
    val work = manager.getWorkInfosForUniqueWorkFlow(name)

    fun enable(command: String, hours: Long): Operation {
        require(command.isNotBlank() && command.length <= 1024 && hours in 1..168)
        val request = PeriodicWorkRequestBuilder<ProjectScheduleWorker>(hours, TimeUnit.HOURS)
            .setInitialDelay(hours, TimeUnit.HOURS)
            .setInputData(workDataOf("path" to path, "command" to command))
            .build()
        return manager.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun disable(): Operation = manager.cancelUniqueWork(name)

    fun history(running: Boolean = false): List<ProjectRun> = history.load(historyKey, interrupted = !running)

    fun record(run: ProjectRun) {
        history.save(historyKey, (listOf(run) + history().filterNot { it.id == run.id }).take(ProjectRunHistory.LIMIT))
    }
}

class ProjectScheduleWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val path = inputData.getString("path") ?: return@withContext Result.failure()
        val command = inputData.getString("command")?.takeIf { it.isNotBlank() } ?: return@withContext Result.failure()
        val schedule = ProjectSchedule(applicationContext, path)
        // A retried worker may have dispatched the previous command before it stopped.
        if (runAttemptCount > 0) return@withContext Result.success()
        val run = ProjectRun(System.currentTimeMillis(), command)
        try {
            schedule.record(run)
            val files = ProjectFiles.open(path)
            val outcome = if (files.root.path == path) ProjectRuns.execute(path, run) else run.copy(status = ProjectRunStatus.Failed)
            schedule.record(outcome)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                runCatching { schedule.record(run.copy(status = ProjectRunStatus.Stopped)) }
            }
            throw cancelled
        } catch (_: Exception) {
            runCatching { schedule.record(run.copy(status = ProjectRunStatus.Failed)) }
        }
        Result.success()
    }
}
