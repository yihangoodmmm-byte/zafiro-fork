package com.niki914.zafiro.platform

import com.niki914.zafiro.platform.contract.ExecutionState
import com.niki914.zafiro.platform.contract.OperationContext
import com.niki914.zafiro.platform.contract.OperationReceipt
import com.niki914.zafiro.platform.contract.OperationTarget
import com.niki914.zafiro.platform.journal.OperationJournal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationJournalTest {

    private fun context(
        operationId: String,
        idempotencyKey: String? = null,
    ) = OperationContext(
        principalId = "user",
        conversationId = "c1",
        turnId = "t1",
        taskId = "task-1",
        operationId = operationId,
        workspaceId = null,
        deadlineEpochMillis = 1_000_000L,
        target = OperationTarget.Global,
        idempotencyKey = idempotencyKey,
    )

    @Test
    fun `same operation id cannot change its target or arguments`() {
        val journal = OperationJournal(clock = { 1_000L })
        val original = context("op-1").copy(requestFingerprint = "first")
        journal.begin(original)
        val result = journal.begin(original.copy(requestFingerprint = "different"))
        assertEquals("operation-id-reused", (result as OperationJournal.BeginResult.Conflict).code)
    }

    @Test
    fun `closed owner releases its idempotency index without touching another owner`() {
        val journal = OperationJournal(clock = { 1_000L })
        journal.begin(context("op-1", "key-1"))
        journal.settle(OperationReceipt("op-1", false, ExecutionState.NOT_EXECUTED))
        journal.begin(context("op-2").copy(principalId = "another"))
        assertEquals(1, journal.releaseOwner("user"))
        assertTrue(journal.find("op-2") != null)
        assertTrue(journal.begin(context("op-3", "key-1")) is OperationJournal.BeginResult.Fresh)
    }

    @Test
    fun `capacity rejects new calls without evicting unresolved effects`() {
        val journal = OperationJournal(clock = { 1_000L }, maxEntries = 1)
        journal.begin(context("op-1"))
        journal.markDispatched("op-1")
        val result = journal.begin(context("op-2"))
        assertEquals("journal-capacity", (result as OperationJournal.BeginResult.Conflict).code)
        assertTrue(journal.find("op-1") != null)
    }

    @Test
    fun `a fresh operation begins once`() {
        val journal = OperationJournal(clock = { 1_000L })

        assertTrue(journal.begin(context("op-1")) is OperationJournal.BeginResult.Fresh)
        assertTrue(journal.begin(context("op-1")) is OperationJournal.BeginResult.Pending)
    }

    @Test
    fun `a dispatched operation cannot be replayed`() {
        val journal = OperationJournal(clock = { 1_000L })
        journal.begin(context("op-1"))
        journal.markDispatched("op-1")

        val result = journal.begin(context("op-1"))

        assertEquals("operation-in-flight", (result as OperationJournal.BeginResult.Conflict).code)
    }

    @Test
    fun `a settled operation returns the original receipt`() {
        val journal = OperationJournal(clock = { 1_000L })
        journal.begin(context("op-1"))
        journal.markDispatched("op-1")
        journal.settle(OperationReceipt("op-1", accepted = true, execution = ExecutionState.EXECUTED))

        val result = journal.begin(context("op-1"))

        assertEquals(ExecutionState.EXECUTED, (result as OperationJournal.BeginResult.Existing).receipt.execution)
    }

    @Test
    fun `a dispatched operation cannot settle as not executed`() {
        val journal = OperationJournal(clock = { 1_000L })
        journal.begin(context("op-1"))
        journal.markDispatched("op-1")

        assertTrue(!journal.settle(OperationReceipt("op-1", true, ExecutionState.NOT_EXECUTED)))
    }

    @Test
    fun `an idempotency key cannot be shared by two operations`() {
        val journal = OperationJournal(clock = { 1_000L })
        journal.begin(context("op-1", idempotencyKey = "key-1"))

        val result = journal.begin(context("op-2", idempotencyKey = "key-1"))

        assertEquals("idempotency-key-reused", (result as OperationJournal.BeginResult.Conflict).code)
    }
}
