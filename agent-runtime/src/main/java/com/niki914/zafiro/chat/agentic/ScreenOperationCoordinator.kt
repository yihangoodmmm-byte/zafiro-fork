package com.niki914.zafiro.chat.agentic

import com.niki914.zafiro.chat.agentic.buildin.TextToolResult
import kotlinx.coroutines.sync.Mutex

/** A complete screen observation/action/capture cycle owns the shared node cache until it returns. */
internal object ScreenOperationCoordinator {
    private val operation = Mutex()

    suspend fun execute(block: suspend () -> TextToolResult): TextToolResult {
        if (!operation.tryLock()) return TextToolResult.failure(
            "SCREEN_BUSY", "Another screen observation or action is in progress. Wait and take a fresh observation before acting.",
        )
        try { return block() } finally { operation.unlock() }
    }
}
