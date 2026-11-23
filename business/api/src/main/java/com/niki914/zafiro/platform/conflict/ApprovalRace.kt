package com.niki914.zafiro.platform.conflict

import java.util.concurrent.ConcurrentHashMap

/** 审批投票。弃权不结算，只表示「这个入口不表态」。 */
enum class ApprovalVote {
    ALLOW,
    DENY,
    ABSTAIN,
}

sealed interface ApprovalOutcome {
    data object Pending : ApprovalOutcome
    data class Settled(
        val requestId: String,
        val vote: ApprovalVote,
        val sourceId: String,
    ) : ApprovalOutcome

    data class AlreadySettled(
        val requestId: String,
        val vote: ApprovalVote,
        val sourceId: String,
    ) : ApprovalOutcome

    data object Expired : ApprovalOutcome
    data object UnknownRequest : ApprovalOutcome
}

/**
 * 多入口审批竞争：首个有效决策结算，其余入口收到已结算回执。
 *
 * 结算与过期判定在同一原子操作里完成，避免两个入口同时通过。
 */
class ApprovalRace(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class State(val expiresAtMillis: Long) {
        @Volatile var settlement: ApprovalOutcome.Settled? = null
    }

    private val states = ConcurrentHashMap<String, State>()

    fun open(requestId: String, ttlMillis: Long) {
        require(ttlMillis > 0L) { "ttlMillis must be positive" }
        require(requestId.isNotBlank()) { "requestId must not be blank" }
        val now = clock()
        require(ttlMillis <= Long.MAX_VALUE - now) { "ttlMillis overflow" }
        states.putIfAbsent(requestId, State(now + ttlMillis))
    }

    fun settle(requestId: String, sourceId: String, vote: ApprovalVote): ApprovalOutcome {
        require(sourceId.isNotBlank()) { "sourceId must not be blank" }
        var outcome: ApprovalOutcome = ApprovalOutcome.UnknownRequest
        states.compute(requestId) { _, state ->
            if (state == null) {
                outcome = ApprovalOutcome.UnknownRequest
                return@compute null
            }
            val settlement = state.settlement
            if (settlement != null) {
                outcome = ApprovalOutcome.AlreadySettled(
                    requestId = settlement.requestId,
                    vote = settlement.vote,
                    sourceId = settlement.sourceId,
                )
                return@compute state
            }
            if (clock() >= state.expiresAtMillis) {
                outcome = ApprovalOutcome.Expired
                return@compute state
            }
            if (vote == ApprovalVote.ABSTAIN) {
                outcome = ApprovalOutcome.Pending
                return@compute state
            }
            val next = ApprovalOutcome.Settled(requestId, vote, sourceId)
            state.settlement = next
            outcome = next
            state
        }
        return outcome
    }

    fun outcome(requestId: String): ApprovalOutcome {
        val state = states[requestId] ?: return ApprovalOutcome.UnknownRequest
        val settlement = state.settlement
        if (settlement != null) return settlement
        return if (clock() >= state.expiresAtMillis) ApprovalOutcome.Expired else ApprovalOutcome.Pending
    }

    fun sweepExpired(): Int {
        val now = clock()
        var removed = 0
        states.entries.forEach { entry ->
            if (entry.value.expiresAtMillis <= now) {
                if (states.remove(entry.key, entry.value)) removed++
            }
        }
        return removed
    }
}
