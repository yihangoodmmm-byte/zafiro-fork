package com.niki914.zafiro.chat

import com.niki914.zafiro.chat.agentic.ScreenOperationCoordinator
import com.niki914.zafiro.chat.agentic.buildin.TextToolResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenOperationCoordinatorTest {
    @Test
    fun concurrentScreenActionCannotReplaceTheOtherTasksNodeCache() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async { ScreenOperationCoordinator.execute { entered.complete(Unit); release.await(); TextToolResult.success("first") } }
        entered.await()
        var dispatched = false
        val second = ScreenOperationCoordinator.execute { dispatched = true; TextToolResult.success("wrong") }
        assertEquals("SCREEN_BUSY", second.code)
        assertTrue(!dispatched)
        release.complete(Unit)
        first.await()
        val third = ScreenOperationCoordinator.execute { TextToolResult.success("next") }
        assertEquals(TextToolResult.Status.Success, third.status)
    }
}
