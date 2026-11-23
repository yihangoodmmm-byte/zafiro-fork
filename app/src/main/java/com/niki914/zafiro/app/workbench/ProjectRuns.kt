package com.niki914.zafiro.app.workbench

import com.niki914.zafiro.chat.agentic.AgentTenantContext
import com.niki914.zafiro.chat.agentic.shell.TerminalCommandOutcome
import com.niki914.zafiro.chat.agentic.shell.TerminalOpenOutcome
import com.niki914.zafiro.chat.agentic.shell.TerminalSessionPool
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.UUID

internal enum class ProjectRunStatus { Running, Finished, TimedOut, Failed, Stopped, Interrupted }
internal data class ProjectRun(
    val id: Long,
    val command: String,
    val status: ProjectRunStatus = ProjectRunStatus.Running,
    val output: String = "",
    val exitCode: Int? = null,
)

internal object ProjectRuns {
    suspend fun execute(directory: String, run: ProjectRun): ProjectRun =
        withContext(AgentTenantContext("workbench-run-" + UUID.randomUUID(), workspacePath = directory)) {
            executeOwned(directory, run)
        }

    private suspend fun executeOwned(directory: String, run: ProjectRun): ProjectRun {
        val opened = TerminalSessionPool.open(identity = "user", cwd = directory)
        if (opened !is TerminalOpenOutcome.Success) return run.copy(status = ProjectRunStatus.Failed)
        try {
            return when (val result = TerminalSessionPool.executeBlocking(opened.session, run.command, 120_000)) {
                is TerminalCommandOutcome.Success -> run.copy(
                    status = ProjectRunStatus.Finished,
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
