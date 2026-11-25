package com.niki914.zafiro.chat.compaction

import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.okia.message.ToolCallOutcome
import com.niki914.okia.tooling.ToolDescriptor

internal object RequestContextBudget {
    fun text(value: String?): Long = value?.let {
        (it.sumOf { char -> if (char.code < 128) 1L else 3L } + 2) / 3
    } ?: 0L

    fun count(system: String?, tools: List<ToolDescriptor>, messages: List<Message>): Long =
        16 + text(system) + tools.sumOf { 12 + text(it.wireName) + text(it.description) + text(it.inputSchemaJson) } +
            messages.sumOf(::message)

    fun message(message: Message): Long = 6 + when (message) {
        is Message.User -> message.content.sumOf(::block)
        is Message.Assistant -> message.message.content.sumOf(::block)
        is Message.ToolResult -> when (val result = message.outcome) {
            is ToolCallOutcome.Success -> text(result.content) + 1024L * result.images.size
            is ToolCallOutcome.Failure -> text(result.content ?: result.message)
            is ToolCallOutcome.Intercepted -> text(result.content ?: result.reason)
            is ToolCallOutcome.Interrupted -> text(result.content)
            is ToolCallOutcome.Unknown -> text(result.content ?: result.message)
        }
    }

    private fun block(block: ContentBlock): Long = when (block) {
        is ContentBlock.Text -> text(block.text)
        is ContentBlock.Image -> 1024L
        is ContentBlock.Thinking -> text(block.text)
        is ContentBlock.ToolCall -> 12 + text(block.name) + text(block.argumentsJson)
    }

    fun pruneTools(messages: List<Message>, limit: Int, includeLatest: Boolean = false): List<Message> {
        val protectedFrom = messages.indexOfLast { it is Message.Assistant &&
            it.message.content.any { block -> block is ContentBlock.ToolCall } }
        if (protectedFrom < 0) return messages
        return messages.mapIndexed { index, message ->
            val outcome = (message as? Message.ToolResult)?.outcome as? ToolCallOutcome.Success
            if ((!includeLatest && index >= protectedFrom) || outcome == null || outcome.content.length <= limit) message
            else (message as Message.ToolResult).copy(outcome = outcome.copy(
                content = outcome.content.take(limit).dropLastWhile { it.isHighSurrogate() } +
                    "\n[Older tool result shortened in context; full result remains in history.]",
            ))
        }
    }
}
