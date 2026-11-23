package com.niki914.zafiro.chat.agentic

import com.niki914.zafiro.platform.contract.ExecutionState
import com.niki914.zafiro.platform.contract.OperationContext
import com.niki914.zafiro.platform.contract.OperationError
import com.niki914.zafiro.platform.contract.OperationReceipt
import com.niki914.zafiro.platform.contract.OperationTarget
import com.niki914.zafiro.platform.journal.OperationJournal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest

/** One ledger per executor, using framework call ids. It does not claim recovery across process death. */
internal class ToolCallLedger(
    private val ownerId: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    sealed interface Result {
        data class Output(val raw: String) : Result
        data class Refused(val code: String, val message: String) : Result
    }
    private val lock = Mutex()
    private val journal = OperationJournal(clock, maxEntries = 4096)
    private val outputs = linkedMapOf<String, String>()
    private var outputBytes = 0L

    suspend fun execute(callId: String, name: String, arguments: String, action: suspend () -> String): Result {
        if (callId.isBlank()) return Result.Refused("TOOL_CALL_ID_REQUIRED", "Framework tool call id is required.")
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest((name.length.toString() + ":" + name + arguments).toByteArray(Charsets.UTF_8))
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        lock.withLock {
            val previous = journal.find(callId)
            if (previous != null && previous.context.requestFingerprint != fingerprint) {
                return Result.Refused("TOOL_CALL_ID_REUSED", "The same call id was reused with different arguments.")
            }
            val context = previous?.context ?: OperationContext(
                principalId = ownerId, conversationId = null, turnId = null, taskId = null,
                operationId = callId, workspaceId = null, deadlineEpochMillis = clock() + 86_400_000,
                target = OperationTarget.Global, requestFingerprint = fingerprint,
            )
            when (val begin = journal.begin(context)) {
                is OperationJournal.BeginResult.Existing -> {
                    if (begin.receipt.execution == ExecutionState.UNKNOWN) return Result.Refused("TOOL_EXECUTION_UNKNOWN", "The previous dispatch has an unknown outcome. Observe before deciding whether a new action is needed.")
                    return outputs[callId]?.let { Result.Output(it) }
                        ?: Result.Refused("TOOL_RESULT_UNAVAILABLE", "The call already completed, but its cached output was retired. Do not repeat an external effect.")
                }
                is OperationJournal.BeginResult.Conflict -> return Result.Refused(begin.code, begin.message)
                is OperationJournal.BeginResult.Pending -> return Result.Refused("TOOL_CALL_PENDING", "This call is already reserved.")
                is OperationJournal.BeginResult.Fresh -> Unit
            }
            if (!journal.markDispatched(callId)) return Result.Refused("DISPATCH_REFUSED", "The operation cannot be dispatched.")
        }
        try {
            val raw = action()
            lock.withLock {
                journal.settle(OperationReceipt(callId, true, ExecutionState.EXECUTED))
                val bytes = raw.toByteArray(Charsets.UTF_8).size.toLong()
                if (bytes <= MAX_OUTPUT_BYTES) {
                    while (outputBytes + bytes > MAX_OUTPUT_BYTES && outputs.isNotEmpty()) {
                        val first = outputs.keys.first()
                        outputBytes -= outputs.remove(first)!!.toByteArray(Charsets.UTF_8).size
                    }
                    outputs[callId] = raw
                    outputBytes += bytes
                }
            }
            return Result.Output(raw)
        } catch (failure: Throwable) {
            // Cancellation must still seal the dispatch. A repeat with the same id cannot re-run it.
            withContext(NonCancellable) {
                lock.withLock {
                    journal.settle(OperationReceipt(callId, true, ExecutionState.UNKNOWN,
                        OperationError(if (failure is CancellationException) "CANCELLED_AFTER_DISPATCH" else "DISPATCH_RESULT_UNKNOWN", "Observe the target before attempting a new operation.")))
                }
            }
            throw failure
        }
    }

    private companion object { const val MAX_OUTPUT_BYTES = 8L * 1024 * 1024 }
}
