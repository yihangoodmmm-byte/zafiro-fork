package com.niki914.zafiro.app.workbench

import com.niki914.zafiro.chat.agentic.AgentTenantContext
import com.niki914.zafiro.chat.agentic.shell.TerminalCommandOutcome
import com.niki914.zafiro.chat.agentic.shell.TerminalOpenOutcome
import com.niki914.zafiro.chat.agentic.shell.TerminalSessionPool
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.UUID

internal enum class ProjectRunStatus { Running, Finished, TimedOut, Failed, Stopped, Interrupted }
internal data class ProjectRun(
    val id: Long,
    val command: String,
    val status: ProjectRunStatus = ProjectRunStatus.Running,
    val output: String = "",
    val exitCode: Int? = null,
    val checkCommand: String? = null,
    val checkExitCode: Int? = null,
    val checkOutput: String = "",
    val checking: Boolean = false,
)

internal object ProjectRuns {
    suspend fun execute(
        directory: String,
        run: ProjectRun,
        onChecking: suspend (ProjectRun) -> Unit = {},
    ): ProjectRun = withContext(AgentTenantContext("workbench-run-" + UUID.randomUUID(), workspacePath = directory)) {
        val main = executeOwned(directory, run)
        val check = run.checkCommand
        if (check == null || main.status != ProjectRunStatus.Finished || main.exitCode != 0) {
            return@withContext main
        }
        onChecking(main.copy(status = ProjectRunStatus.Running, checking = true))
        coroutineContext.ensureActive()
        val result = executeOwned(directory, ProjectRun(run.id, check))
        main.copy(status = result.status, checkExitCode = result.exitCode, checkOutput = result.output, checking = true)
    }

    private suspend fun executeOwned(directory: String, run: ProjectRun): ProjectRun {
        val opened = TerminalSessionPool.open(identity = "user", cwd = directory)
        if (opened !is TerminalOpenOutcome.Success) return run.copy(status = ProjectRunStatus.Failed)
        try {
            return when (val result = TerminalSessionPool.executeBlocking(opened.session, run.command, 120_000)) {
                is TerminalCommandOutcome.Success -> run.copy(
                    status = if (result.result.exitCode == 0) ProjectRunStatus.Finished else ProjectRunStatus.Failed,
                    exitCode = result.result.exitCode,
                    output = output(result.result.stdout.toByteArray(), result.result.stderr.toByteArray()),
                )
                is TerminalCommandOutcome.Timeout -> run.copy(
                    status = ProjectRunStatus.TimedOut,
                    output = output(result.result.stdout.toByteArray(), result.result.stderr.toByteArray()),
                )
                else -> run.copy(status = ProjectRunStatus.Failed)
            }
        } finally {
            withContext(NonCancellable) { TerminalSessionPool.close(opened.session) }
        }
    }

    private fun output(stdout: ByteArray, stderr: ByteArray): String =
        listOf(stdout.decodeToString().takeLast(18_000), stderr.decodeToString().takeLast(8_000))
            .filter { it.isNotEmpty() }.joinToString("\n")
}
