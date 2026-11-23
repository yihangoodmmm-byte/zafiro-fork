package com.niki914.zafiro.platform

import com.niki914.zafiro.platform.task.TaskEvent
import com.niki914.zafiro.platform.task.TaskState
import com.niki914.zafiro.platform.task.TaskStateMachine
import com.niki914.zafiro.platform.task.TaskTransition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskStateMachineTest {

    @Test
    fun `a queued task starts running`() {
        assertEquals(
            TaskTransition.Allowed(TaskState.RUNNING),
            TaskStateMachine.transition(TaskState.QUEUED, TaskEvent.Start),
        )
    }

    @Test
    fun `asking the user pauses the task until answered`() {
        assertEquals(
            TaskTransition.Allowed(TaskState.WAITING_USER),
            TaskStateMachine.transition(TaskState.RUNNING, TaskEvent.AskUser),
        )
        assertEquals(
            TaskTransition.Allowed(TaskState.RUNNING),
            TaskStateMachine.transition(TaskState.WAITING_USER, TaskEvent.UserAnswered),
        )
    }

    @Test
    fun `cancel is two phase and ends interrupted`() {
        val cancelling = TaskStateMachine.transition(TaskState.RUNNING, TaskEvent.CancelRequested)
        val settled = TaskStateMachine.transition(TaskState.CANCELLING, TaskEvent.CancelSettled)

        assertEquals(TaskTransition.Allowed(TaskState.CANCELLING), cancelling)
        assertEquals(TaskTransition.Allowed(TaskState.INTERRUPTED), settled)
    }

    @Test
    fun `terminal tasks reject every event`() {
        val result = TaskStateMachine.transition(TaskState.COMPLETED, TaskEvent.Start)

        assertEquals("task-terminal", (result as TaskTransition.Rejected).code)
    }

    @Test
    fun `an event that does not fit is rejected`() {
        val result = TaskStateMachine.transition(TaskState.QUEUED, TaskEvent.Complete)

        assertEquals("task-invalid-transition", (result as TaskTransition.Rejected).code)
    }

    @Test
    fun `an interrupted process is not treated as running`() {
        val result = TaskStateMachine.transition(TaskState.RUNNING, TaskEvent.Interrupt)

        assertEquals(TaskTransition.Allowed(TaskState.INTERRUPTED), result)
        assertTrue(TaskStateMachine.isTerminal(TaskState.INTERRUPTED))
    }
}
