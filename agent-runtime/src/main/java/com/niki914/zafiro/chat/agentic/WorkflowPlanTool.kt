package com.niki914.zafiro.chat.agentic

import com.niki914.okia.message.ToolCallOutcome
import com.niki914.okia.tooling.ToolCallContext
import com.niki914.okia.tooling.ToolDescriptor
import com.niki914.okia.tooling.ToolExecutor
import com.niki914.okia.tooling.ToolKind
import com.niki914.zafiro.chat.agentic.WorkflowPlanReport.Phase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One turn's model-reported task state. Updating it never executes a step or grants permissions. */
internal class WorkflowPlanTool : ToolExecutor {
    private val lock = Mutex()
    private var report: WorkflowPlanReport? = null

    override suspend fun execute(call: ToolCallContext): ToolCallOutcome {
        if (call.argumentsJson.length > WorkflowPlanReport.MAX_REPORT_CHARS) {
            return ToolCallOutcome.Failure("Workflow input is too long.")
        }
        val input = runCatching { Json.parseToJsonElement(call.argumentsJson) }
            .getOrNull() as? JsonObject ?: return ToolCallOutcome.Failure("Expected a JSON object.")
        val action = (input["action"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return lock.withLock {
            when (action) {
                "read" -> ToolCallOutcome.Success(report?.encode() ?: """{"source":"model_reported","state":"not_started"}""")
                "update" -> update(input)
                else -> ToolCallOutcome.Failure("Use action 'read' or 'update'.")
            }
        }
    }

    private fun update(input: JsonObject): ToolCallOutcome {
        val next = WorkflowPlanReport.readUpdate(input)
            ?: return ToolCallOutcome.Failure("Supply phase, goal, steps and checks for update.")
        next.validationError()?.let { return ToolCallOutcome.Failure(it) }
        if (next != report && !canTransition(report?.phase, next.phase)) {
            return ToolCallOutcome.Failure("Publish planning, executing, then verifying before completion. Use blocked if work cannot finish.")
        }
        val encoded = next.encode()
        if (encoded.length > WorkflowPlanReport.MAX_REPORT_CHARS) return ToolCallOutcome.Failure("Workflow report is too long.")
        report = next
        return ToolCallOutcome.Success(encoded)
    }

    private fun canTransition(previous: Phase?, next: Phase): Boolean = when (previous) {
        null -> next == Phase.Planning
        Phase.Planning -> next in setOf(Phase.Planning, Phase.Executing, Phase.Blocked)
        Phase.Executing -> next in setOf(Phase.Executing, Phase.Verifying, Phase.Blocked)
        Phase.Verifying -> next in setOf(Phase.Verifying, Phase.Executing, Phase.Completed, Phase.Blocked)
        Phase.Completed, Phase.Blocked -> false
    }

    override fun onInterrupt(call: ToolCallContext) = ToolCallOutcome.Interrupted()

    companion object {
        val descriptor = ToolDescriptor(
            name = WorkflowPlanReport.TOOL_NAME,
            description = "Read or update one turn's workflow: plan, execute, verify, then complete or report a blocker. " +
                "Each update supplies the full snapshot. This records model-reported progress only.",
            inputSchemaJson = """
                {"type":"object","properties":{
                  "action":{"type":"string","enum":["read","update"]},
                  "phase":{"type":"string","enum":["planning","executing","verifying","completed","blocked"]},
                  "goal":{"type":"string","maxLength":220},
                  "summary":{"type":"string","maxLength":420,"description":"Handoff and remaining work; required when completed or blocked."},
                  "steps":{"type":"array","minItems":1,"maxItems":8,"items":{"type":"object","properties":{
                    "title":{"type":"string","maxLength":140},
                    "status":{"type":"string","enum":["pending","in_progress","completed","blocked"]},
                    "evidence":{"type":"string","maxLength":320}
                  },"required":["title","status"]}},
                  "checks":{"type":"array","minItems":1,"maxItems":5,"items":{"type":"object","properties":{
                    "title":{"type":"string","maxLength":140},
                    "status":{"type":"string","enum":["pending","passed","failed","skipped"]},
                    "evidence":{"type":"string","maxLength":320}
                  },"required":["title","status"]}}
                },"required":["action"]}
            """.trimIndent(),
            kind = ToolKind.Local,
        )
    }
}
