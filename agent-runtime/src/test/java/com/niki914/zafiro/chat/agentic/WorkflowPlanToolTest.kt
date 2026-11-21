package com.niki914.zafiro.chat.agentic

import com.niki914.okia.message.ToolCallOutcome
import com.niki914.okia.tooling.ToolCallContext
import com.niki914.zafiro.api.model.ChatTurnOptions
import com.niki914.zafiro.chat.agentic.WorkflowPlanReport.Phase
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowPlanToolTest {
    @Test
    fun completeWorkflowReportsEvidenceAndClosesAfterVerification() = runTest {
        val tool = WorkflowPlanTool()
        assertNull(read(tool))
        assertSuccess(tool, Phase.Planning, update("planning", "pending", "pending"))
        assertTrue(call(tool, update("completed", "completed", "passed", "Verified")) is ToolCallOutcome.Failure)
        assertSuccess(tool, Phase.Executing, update("executing", "in_progress", "pending"))
        assertSuccess(tool, Phase.Verifying, update("verifying", "completed", "pending"))
        assertTrue(call(tool, update("completed", "completed", "pending", "Done")) is ToolCallOutcome.Failure)
        assertEquals(Phase.Verifying, read(tool)?.phase)
        assertSuccess(tool, Phase.Completed, update("completed", "completed", "passed", "Verified"))
        assertEquals("Evidence for passed", read(tool)?.checks?.single()?.evidence)
        assertTrue(call(tool, update("executing", "in_progress", "pending")) is ToolCallOutcome.Failure)
    }

    @Test
    fun blockedWorkflowKeepsSkippedCheckAndHandoffVisible() = runTest {
        val tool = WorkflowPlanTool()
        assertSuccess(tool, Phase.Planning, update("planning", "pending", "pending"))
        assertSuccess(tool, Phase.Executing, update("executing", "in_progress", "pending"))
        assertSuccess(tool, Phase.Blocked, update("blocked", "blocked", "skipped", "Needs device check"))
        assertEquals(WorkflowPlanReport.CheckStatus.Skipped, read(tool)?.checks?.single()?.status)
        assertEquals("Needs device check", read(tool)?.summary)
    }

    @Test
    fun workflowAndReasoningGuidanceAreIndependentOfTheBasePrompt() {
        val base = "Base instructions"
        assertEquals(base, ChatTurnPrompt.compose(base, ChatTurnOptions(), null))
        val guidance = ChatTurnPrompt.compose(base, ChatTurnOptions(reasoningGuideEnabled = true), null)
        assertTrue(guidance.startsWith(base))
        assertTrue(guidance.contains("Do not output private reasoning traces."))
        assertTrue(!guidance.contains("## Task workflow"))
    }

    private suspend fun call(tool: WorkflowPlanTool, args: String): ToolCallOutcome {
        val descriptor = WorkflowPlanTool.descriptor
        return tool.execute(ToolCallContext("test", descriptor.wireName, descriptor, args))
    }

    private suspend fun read(tool: WorkflowPlanTool): WorkflowPlanReport? {
        val outcome = call(tool, """{"action":"read"}""") as ToolCallOutcome.Success
        return WorkflowPlanReport.fromToolResult(outcome.content)
    }

    private suspend fun assertSuccess(tool: WorkflowPlanTool, phase: Phase, args: String) {
        val outcome = call(tool, args)
        assertTrue(outcome.toString(), outcome is ToolCallOutcome.Success)
        assertEquals(phase, WorkflowPlanReport.fromToolResult((outcome as ToolCallOutcome.Success).content)?.phase)
    }

    private fun update(phase: String, step: String, check: String, summary: String = "") = """
        {"action":"update","phase":"$phase","goal":"Fix workspace binding","summary":"$summary",
         "steps":[{"title":"Change and inspect the binding","status":"$step","evidence":"Evidence for $step"}],
         "checks":[{"title":"Confirm the new default folder","status":"$check","evidence":"Evidence for $check"}]}
    """.trimIndent()
}
