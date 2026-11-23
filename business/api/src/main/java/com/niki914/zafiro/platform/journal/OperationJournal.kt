package com.niki914.zafiro.platform.journal

import com.niki914.zafiro.platform.contract.ExecutionState
import com.niki914.zafiro.platform.contract.OperationContext
import com.niki914.zafiro.platform.contract.OperationReceipt
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Process-local journal. Capacity exhaustion rejects new work; it never evicts an unresolved operation. */
class OperationJournal(
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxEntries: Int = 4096,
) {
    data class Entry(
        val operationId: String,
        val idempotencyKey: String?,
        val context: OperationContext,
        val dispatchedAtMillis: Long?,
        val receipt: OperationReceipt?,
    )
    sealed interface BeginResult {
        data class Fresh(val entry: Entry) : BeginResult
        data class Existing(val receipt: OperationReceipt) : BeginResult
        data class Pending(val entry: Entry) : BeginResult
        data class Conflict(val code: String, val message: String) : BeginResult
    }
    private val lock = ReentrantLock()
    private val entries = mutableMapOf<String, Entry>()
    private val idempotencyOwners = mutableMapOf<String, String>()

    init { require(maxEntries > 0) }

    fun begin(context: OperationContext): BeginResult = lock.withLock {
        if (context.operationId.isBlank() || context.principalId.isBlank()) {
            return BeginResult.Conflict("invalid-context", "Operation and principal ids are required.")
        }
        val prior = entries[context.operationId]
        if (prior != null) {
            if (prior.context != context) return BeginResult.Conflict("operation-id-reused", "Operation id identifies a different request.")
            prior.receipt?.let { return BeginResult.Existing(it) }
            if (prior.dispatchedAtMillis != null) return BeginResult.Conflict("operation-in-flight", "Query the original operation; do not replay it.")
            return BeginResult.Pending(prior)
        }
        if (context.deadlineEpochMillis <= clock()) return BeginResult.Conflict("deadline-exceeded", "Operation deadline has passed.")
        if (entries.size >= maxEntries) return BeginResult.Conflict("journal-capacity", "Release the finished owner's records before accepting new work.")
        val key = context.idempotencyKey
        if (key != null && idempotencyOwners.containsKey(key)) return BeginResult.Conflict("idempotency-key-reused", "Idempotency key is already owned.")
        val entry = Entry(context.operationId, key, context, null, null)
        entries[context.operationId] = entry
        if (key != null) idempotencyOwners[key] = context.operationId
        BeginResult.Fresh(entry)
    }

    fun markDispatched(operationId: String): Boolean = lock.withLock {
        val entry = entries[operationId] ?: return false
        if (entry.dispatchedAtMillis != null || entry.receipt != null || entry.context.deadlineEpochMillis <= clock()) return false
        entries[operationId] = entry.copy(dispatchedAtMillis = clock())
        true
    }

    fun settle(receipt: OperationReceipt): Boolean = lock.withLock {
        val entry = entries[receipt.operationId] ?: return false
        if (entry.receipt != null) return false
        if (entry.dispatchedAtMillis == null && receipt.execution != ExecutionState.NOT_EXECUTED) return false
        if (entry.dispatchedAtMillis != null && receipt.execution == ExecutionState.NOT_EXECUTED) return false
        entries[receipt.operationId] = entry.copy(receipt = receipt)
        true
    }

    fun find(operationId: String): Entry? = lock.withLock { entries[operationId] }
    fun size(): Int = lock.withLock { entries.size }

    /** Call only after the owner is closed and cannot submit old operation ids again. */
    fun releaseOwner(principalId: String): Int = lock.withLock {
        val retired = entries.values.filter { it.context.principalId == principalId && it.receipt != null }
        retired.forEach { entry ->
            entries.remove(entry.operationId)
            entry.idempotencyKey?.let { idempotencyOwners.remove(it) }
        }
        retired.size
    }
}
