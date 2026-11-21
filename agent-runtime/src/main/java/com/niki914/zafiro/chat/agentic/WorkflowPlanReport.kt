package com.niki914.zafiro.chat.agentic

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Persisted as a normal tool outcome, so history can render it without a database migration. */
@Serializable
data class WorkflowPlanReport(
    val phase: Phase,
    val goal: String,
    val steps: List<Step>,
    val checks: List<Check>,
    val summary: String = "",
    val source: String = SOURCE,
) {
    @Serializable
    enum class Phase {
        @SerialName("planning") Planning,
        @SerialName("executing") Executing,
        @SerialName("verifying") Verifying,
        @SerialName("completed") Completed,
        @SerialName("blocked") Blocked;

        val isFinished: Boolean get() = this == Completed || this == Blocked
    }

    @Serializable
    data class Step(val title: String, val status: StepStatus, val evidence: String = "")

    @Serializable
    enum class StepStatus {
        @SerialName("pending") Pending,
        @SerialName("in_progress") InProgress,
        @SerialName("completed") Completed,
        @SerialName("blocked") Blocked,
    }

    @Serializable
    data class Check(val title: String, val status: CheckStatus, val evidence: String = "")

    @Serializable
    enum class CheckStatus {
        @SerialName("pending") Pending,
        @SerialName("passed") Passed,
        @SerialName("failed") Failed,
        @SerialName("skipped") Skipped,
    }

    internal fun validationError(): String? {
        if (source != SOURCE) return "Invalid workflow source."
        if (!goal.validText(220) || !summary.validText(420, required = false)) return "Provide a short goal and summary."
        if (steps.size !in 1..8 || checks.size !in 1..5) return "Provide 1–8 steps and 1–5 acceptance checks."
        if (steps.map { it.title }.distinct().size != steps.size || checks.map { it.title }.distinct().size != checks.size) {
            return "Step and check titles must be unique within their lists."
        }
        if (steps.any { step ->
                !step.title.validText(140) || !step.evidence.validText(
                    320, required = step.status == StepStatus.Completed || step.status == StepStatus.Blocked,
                )
            } || checks.any { !it.title.validText(140) || !it.evidence.validText(320, it.status != CheckStatus.Pending) }
        ) return "Steps/checks need short titles. Report evidence for completed/blocked steps and non-pending checks."
        if (steps.count { it.status == StepStatus.InProgress } > 1) return "Only one step may be in progress."
        return when (phase) {
            Phase.Planning -> if (steps.any { it.status != StepStatus.Pending } || checks.any { it.status != CheckStatus.Pending }) {
                "Planning must start with pending steps and checks."
            } else null
            Phase.Executing, Phase.Verifying -> null
            Phase.Completed -> if (summary.isBlank() ||
                steps.any { it.status != StepStatus.Completed } || checks.any { it.status != CheckStatus.Passed }
            ) "Completed requires a handoff summary, all steps completed and all checks passed." else null
            Phase.Blocked -> if (summary.isBlank() || (steps.none { it.status == StepStatus.Blocked } &&
                checks.none { it.status == CheckStatus.Failed || it.status == CheckStatus.Skipped })
            ) "Blocked requires a handoff summary and a blocked step or failed/skipped check." else null
        }
    }

    internal fun normalized(): WorkflowPlanReport = copy(
        goal = goal.trim(),
        summary = summary.trim(),
        steps = steps.map { it.copy(title = it.title.trim(), evidence = it.evidence.trim()) },
        checks = checks.map { it.copy(title = it.title.trim(), evidence = it.evidence.trim()) },
    )

    internal fun encode(): String = json.encodeToString(serializer(), this)

    private fun String.validText(maxLength: Int, required: Boolean = true): Boolean =
        length <= maxLength && (!required || isNotBlank()) && none { Character.isISOControl(it) }

    companion object {
        const val TOOL_NAME = "zafiro_workflow_plan"
        internal const val MAX_REPORT_CHARS = 16_384
        private const val SOURCE = "model_reported"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        internal fun readUpdate(input: JsonObject): WorkflowPlanReport? = runCatching {
            json.decodeFromJsonElement(serializer(), input).normalized()
        }.getOrNull()

        fun fromToolResult(raw: String?): WorkflowPlanReport? {
            if (raw == null || raw.length > MAX_REPORT_CHARS) return null
            return runCatching { json.decodeFromString(serializer(), raw) }
                .getOrNull()?.takeIf { it.validationError() == null }
        }
    }
}
