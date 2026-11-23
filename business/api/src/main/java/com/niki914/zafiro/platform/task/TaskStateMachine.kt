package com.niki914.zafiro.platform.task

/** 任务状态。恢复时按真实证据落状态，不从持久化 running 猜运行中。 */
enum class TaskState {
    QUEUED,
    RUNNING,
    WAITING_USER,
    CANCELLING,
    COMPLETED,
    FAILED,
    INTERRUPTED,
    UNKNOWN,
}

sealed interface TaskEvent {
    data object Start : TaskEvent
    data object AskUser : TaskEvent
    data object UserAnswered : TaskEvent
    data object CancelRequested : TaskEvent
    data object CancelSettled : TaskEvent
    data object Complete : TaskEvent
    data object Fail : TaskEvent
    data object Interrupt : TaskEvent
}

sealed interface TaskTransition {
    data class Allowed(val next: TaskState) : TaskTransition
    data class Rejected(val code: String, val message: String) : TaskTransition
}

/**
 * 任务状态机。唯一允许的转换写在这里，不在调用点各自判断。
 *
 * 终态不可再转换；取消是两段式：受理后进入 CANCELLING，清理完成才结算。
 */
object TaskStateMachine {

    fun isTerminal(state: TaskState): Boolean = state in setOf(
        TaskState.COMPLETED,
        TaskState.FAILED,
        TaskState.INTERRUPTED,
        TaskState.UNKNOWN,
    )

    fun transition(from: TaskState, event: TaskEvent): TaskTransition {
        if (isTerminal(from)) {
            return TaskTransition.Rejected(
                code = "task-terminal",
                message = "任务已结束，不能再接受 ${eventName(event)}。",
            )
        }
        val next = when (from) {
            TaskState.QUEUED -> when (event) {
                TaskEvent.Start -> TaskState.RUNNING
                TaskEvent.CancelRequested -> TaskState.CANCELLING
                TaskEvent.Interrupt -> TaskState.UNKNOWN
                else -> null
            }
            TaskState.RUNNING -> when (event) {
                TaskEvent.AskUser -> TaskState.WAITING_USER
                TaskEvent.CancelRequested -> TaskState.CANCELLING
                TaskEvent.Complete -> TaskState.COMPLETED
                TaskEvent.Fail -> TaskState.FAILED
                TaskEvent.Interrupt -> TaskState.INTERRUPTED
                else -> null
            }
            TaskState.WAITING_USER -> when (event) {
                TaskEvent.UserAnswered -> TaskState.RUNNING
                TaskEvent.CancelRequested -> TaskState.CANCELLING
                TaskEvent.Interrupt -> TaskState.INTERRUPTED
                TaskEvent.Fail -> TaskState.FAILED
                else -> null
            }
            TaskState.CANCELLING -> when (event) {
                TaskEvent.CancelSettled -> TaskState.INTERRUPTED
                TaskEvent.Complete -> TaskState.COMPLETED
                TaskEvent.Fail -> TaskState.FAILED
                TaskEvent.Interrupt -> TaskState.UNKNOWN
                else -> null
            }
            TaskState.COMPLETED,
            TaskState.FAILED,
            TaskState.INTERRUPTED,
            TaskState.UNKNOWN -> null
        }
        return if (next == null) {
            TaskTransition.Rejected(
                code = "task-invalid-transition",
                message = "${from.name.lowercase()} 不接受 ${eventName(event)}。",
            )
        } else {
            TaskTransition.Allowed(next)
        }
    }

    private fun eventName(event: TaskEvent): String = when (event) {
        TaskEvent.Start -> "start"
        TaskEvent.AskUser -> "ask-user"
        TaskEvent.UserAnswered -> "user-answered"
        TaskEvent.CancelRequested -> "cancel-requested"
        TaskEvent.CancelSettled -> "cancel-settled"
        TaskEvent.Complete -> "complete"
        TaskEvent.Fail -> "fail"
        TaskEvent.Interrupt -> "interrupt"
    }
}
