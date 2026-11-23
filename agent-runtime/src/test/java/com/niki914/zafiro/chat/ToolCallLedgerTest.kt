package com.niki914.zafiro.chat

import com.niki914.zafiro.chat.agentic.ToolCallLedger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A retry, concurrent delivery or cancellation must not cause a second external effect. */
class ToolCallLedgerTest {
    @Test
    fun duplicateReturnsOriginalOutputWithoutExecutingAgain() = runTest {
        val ledger = ToolCallLedger("tenant")
        var effects = 0
        val first = ledger.execute("call", "send", "{}") { effects++; "original" }
        val duplicate = ledger.execute("call", "send", "{}") { effects++; "wrong" }
        assertEquals(first, duplicate)
        assertEquals(1, effects)
    }

    @Test
    fun changedArgumentsUnderSameIdAreRefused() = runTest {
        val ledger = ToolCallLedger("tenant")
        ledger.execute("call", "send", "first") { "sent" }
        var repeated = false
        val result = ledger.execute("call", "send", "different") { repeated = true; "wrong" }
        assertEquals("TOOL_CALL_ID_REUSED", (result as ToolCallLedger.Result.Refused).code)
        assertTrue(!repeated)
    }

    @Test
    fun concurrentDuplicateCannotDispatchWhileOriginalIsSuspended() = runTest {
        val ledger = ToolCallLedger("tenant")
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var effects = 0
        val first = async { ledger.execute("call", "send", "{}") { effects++; started.complete(Unit); finish.await(); "sent" } }
        started.await()
        val duplicate = ledger.execute("call", "send", "{}") { effects++; "wrong" }
        assertTrue(duplicate is ToolCallLedger.Result.Refused)
        finish.complete(Unit)
        first.await()
        assertEquals(1, effects)
    }

    @Test
    fun cancellationSealsTheUnknownDispatchBeforeItIsRethrown() = runTest {
        val ledger = ToolCallLedger("tenant")
        try {
            ledger.execute("call", "send", "{}") { throw CancellationException("cancel") }
        } catch (_: CancellationException) { }
        var repeated = false
        val result = ledger.execute("call", "send", "{}") { repeated = true; "wrong" }
        assertEquals("TOOL_EXECUTION_UNKNOWN", (result as ToolCallLedger.Result.Refused).code)
        assertTrue(!repeated)
    }
}
