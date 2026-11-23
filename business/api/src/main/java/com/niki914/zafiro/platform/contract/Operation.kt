package com.niki914.zafiro.platform.contract

/** 一次外部副作用是否已经发生。三条互斥，[UNKNOWN] 不允许重试。 */
enum class ExecutionState {
    NOT_EXECUTED,
    EXECUTED,
    UNKNOWN,
}

/** 操作目标。所有目标都带稳定 id 与代次，代次变化即旧引用失效。 */
sealed interface OperationTarget {
    data class Screen(val alias: String, val generation: Long) : OperationTarget
    data class BrowserTab(
        val sessionId: String,
        val tabId: String,
        val pageGeneration: Long,
    ) : OperationTarget
    data class Terminal(val sessionId: String) : OperationTarget
    data class Workspace(val workspaceId: String) : OperationTarget
    data class Device(val deviceId: String) : OperationTarget
    data object Global : OperationTarget
}

/**
 * 调用上下文。由可信调度器生成，模型参数无法伪造。
 *
 * [deadlineEpochMillis] 到期后能力层拒绝新派发；已派发的操作进入 [ExecutionState.UNKNOWN]
 * 而不是被当作未执行。
 */
data class OperationContext(
    val principalId: String,
    val conversationId: String?,
    val turnId: String?,
    val taskId: String?,
    val operationId: String,
    val workspaceId: String?,
    val deadlineEpochMillis: Long,
    val target: OperationTarget,
    val idempotencyKey: String? = null,
    val requestFingerprint: String? = null,
)

/** 结构化错误。code 稳定，message 给人看，retryable 明确。 */
data class OperationError(
    val code: String,
    val message: String,
    val retryable: Boolean = false,
)

/** 操作回执。[accepted] 只表示受理，[execution] 才是副作用事实。 */
data class OperationReceipt(
    val operationId: String,
    val accepted: Boolean,
    val execution: ExecutionState,
    val error: OperationError? = null,
    val artifactIds: List<String> = emptyList(),
)

/** 能力调用的结果类型，避免布尔 + 字符串混搭。 */
sealed interface OperationOutcome {
    data class Success(val receipt: OperationReceipt) : OperationOutcome
    data class Rejected(val error: OperationError) : OperationOutcome
}
