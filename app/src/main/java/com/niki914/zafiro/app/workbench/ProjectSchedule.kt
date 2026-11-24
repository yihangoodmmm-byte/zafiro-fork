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

    fun enable(command: String, hours: Long, checkCommand: String?, artifactPath: String?): Operation {
        require(command.isNotBlank() && command.length <= 1024 && hours in 1..168)
        require(checkCommand == null || (checkCommand.isNotBlank() && checkCommand.length <= 1024))
        require(artifactPath == null || validProjectArtifactPath(artifactPath))
        val request = PeriodicWorkRequestBuilder<ProjectScheduleWorker>(hours, TimeUnit.HOURS)
            .setInitialDelay(hours, TimeUnit.HOURS)
            .setInputData(workDataOf("path" to path, "command" to command, "check" to checkCommand, "artifact" to artifactPath))
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
        val token = inputData.getString(ProjectPowerTrigger.TOKEN)
        val trigger = token?.let { ProjectPowerTrigger(applicationContext, path) }
        val periodic = if (trigger == null) ProjectSchedule(applicationContext, path) else null
        val rule = if (trigger != null) {
            val age = System.currentTimeMillis() - inputData.getLong(ProjectPowerTrigger.EVENT_AT, 0L)
            if (age !in 0L..ProjectPowerTrigger.EVENT_TTL_MS) return@withContext Result.success()
            trigger.rule()?.takeIf { it.token == token } ?: return@withContext Result.success()
        } else null
        val command = rule?.command ?: inputData.getString("command") ?: return@withContext Result.failure()
        val checkCommand = if (rule != null) rule.checkCommand else inputData.getString("check")
        val artifactPath = if (rule != null) rule.artifactPath else inputData.getString("artifact")
        if (command.isBlank() || command.length > 1024 ||
            (checkCommand != null && (checkCommand.isBlank() || checkCommand.length > 1024)) ||
            (artifactPath != null && !validProjectArtifactPath(artifactPath))
        ) return@withContext Result.failure()
        fun record(run: ProjectRun) {
            if (trigger != null) trigger.record(run) else periodic?.record(run)
        }
        // A retried worker may have dispatched the previous command before it stopped.
        if (runAttemptCount > 0) return@withContext Result.success()
        var run = ProjectRun(System.currentTimeMillis(), command, checkCommand = checkCommand, artifactPath = artifactPath)
        try {
            val files = ProjectFiles.open(path)
            if (trigger != null && trigger.rule()?.token != token) return@withContext Result.success()
            record(run)
            val outcome = if (files.root.path == path) {
                ProjectRuns.execute(path, run) { checking ->
                    run = checking
                    record(checking)
                }
            } else run.copy(status = ProjectRunStatus.Failed)
            record(outcome)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                runCatching { record(run.copy(status = ProjectRunStatus.Stopped)) }
            }
            throw cancelled
        } catch (_: Exception) {
            runCatching { record(run.copy(status = ProjectRunStatus.Failed)) }
        }
        Result.success()
    }
}
