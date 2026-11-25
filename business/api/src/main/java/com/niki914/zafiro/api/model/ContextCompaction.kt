package com.niki914.zafiro.api.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Context maintenance is independent of the assistant's text and tool execution status. */
data class ContextCompaction(
    val sequence: Long = 0L,
    val phase: Phase = Phase.Idle,
    val beforeTokens: Long? = null,
    val afterTokens: Long? = null,
    val manual: Boolean = false,
) {
    enum class Phase { Idle, Running, Compacted, Pruned, Unchanged, Failed, Cancelled }

    companion object {
        val idle: StateFlow<ContextCompaction> = MutableStateFlow(ContextCompaction()).asStateFlow()
    }
}
