package com.niki914.zafiro.chat.compaction

import com.niki914.logging.Logger
import com.niki914.okia.Okia
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.okia.message.ToolCallOutcome
import com.niki914.zafiro.api.text.SummaryBlock
import com.niki914.zafiro.api.util.runCatchingCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * 会话上下文压缩调度器与摘要生成引擎。
 *
 * 核心设计：
 * 1. 触发判断：基于上下文窗口占用阈值（默认 70%）或用户手动 /compact 强制触发；
 * 2. 对话序列化：展平历史为纯文本，剔除思考块（Thinking），截断大工具输出；
 * 3. 摘要生成：调用一过性摘要生成器产出高质量 Markdown 检查点；
 * 4. “无事发生”原则：用户取消或协程终止时，绝对不调用 [Okia.append]，保持会话树 0 污染；
 * 5. 故障降级：网络异常时平滑跳过压缩，不阻断主交互流程。
 */
class Compactor(
    private val summaryGenerator: SummaryGenerator,
    var thresholdRatio: Float = DEFAULT_THRESHOLD_RATIO,
    private val keepTurns: Int = DEFAULT_KEEP_TURNS,
) {

    /** 摘要模型调用契约。 */
    fun interface SummaryGenerator {
        suspend fun generate(systemPrompt: String, userPrompt: String): String
    }

    companion object {
        private const val LOG_TAG = "niki914_zafiro_Compactor"

        /** 默认自动压缩触发阈值（窗口占用的 70%）。 */
        const val DEFAULT_THRESHOLD_RATIO = 0.70f

        /** 默认保留的最近活跃轮次数。 */
        const val DEFAULT_KEEP_TURNS = 2

        /** 单条工具结果最大保留字符数（对齐 Pi 的 TOOL_RESULT_MAX_CHARS）。 */
        const val TOOL_RESULT_MAX_CHARS = 2000

        /**
         * 判定是否应当触发压缩。
         */
        fun shouldCompact(
            contextTokens: Long,
            contextWindow: Long,
            thresholdRatio: Float = DEFAULT_THRESHOLD_RATIO,
        ): Boolean {
            if (thresholdRatio <= 0f || contextWindow <= 0L || contextTokens <= 0L) return false
            val thresholdTokens = (contextWindow * thresholdRatio).toLong()
            return contextTokens >= thresholdTokens
        }

        /**
         * 将平坦消息历史展平为用于生成摘要的纯文本（对齐 Pi 的 serializeConversation）。
         */
        fun serializeConversation(
            messages: List<Message>,
            maxToolResultChars: Int = TOOL_RESULT_MAX_CHARS,
        ): String {
            val parts = mutableListOf<String>()

            for (msg in messages) {
                when (msg) {
                    is Message.User -> {
                        val text = msg.content
                            .filterIsInstance<ContentBlock.Text>()
                            .joinToString("\n") { it.text }
                            .trim()
                        if (text.isNotEmpty()) {
                            val stripped = SummaryBlock.strip(text)
                            if (stripped.payload != null) {
                                if (stripped.text.isNotBlank()) {
                                    parts.add("[User]: ${stripped.text.trim()}")
                                }
                            } else {
                                parts.add("[User]: $text")
                            }
                        }
                    }

                    is Message.Assistant -> {
                        val textBlocks = msg.message.content
                            .filterIsInstance<ContentBlock.Text>()
                            .map { it.text.trim() }
                            .filter { it.isNotEmpty() }
                        if (textBlocks.isNotEmpty()) {
                            parts.add("[Assistant]: ${textBlocks.joinToString("\n")}")
                        }
                        val toolCalls = msg.message.content.filterIsInstance<ContentBlock.ToolCall>()
                        if (toolCalls.isNotEmpty()) {
                            val callsStr = toolCalls.joinToString("; ") { call ->
                                "${call.name}(${call.argumentsJson})"
                            }
                            parts.add("[Assistant tool calls]: $callsStr")
                        }
                        // ContentBlock.Thinking 故意剔除：思考过程冗长且省 token，防格式冲突
                    }

                    is Message.ToolResult -> {
                        val rawContent = when (val outcome = msg.outcome) {
                            is ToolCallOutcome.Success -> outcome.content
                            is ToolCallOutcome.Failure -> outcome.content ?: outcome.message
                            is ToolCallOutcome.Intercepted -> outcome.content ?: outcome.reason
                            is ToolCallOutcome.Interrupted -> outcome.content ?: "Interrupted"
                            is ToolCallOutcome.Unknown -> outcome.content ?: outcome.message
                        }
                        val content = truncateForSummary(rawContent, maxToolResultChars)
                        if (content.isNotBlank()) {
                            parts.add("[Tool result]: $content")
                        }
                    }
                }
            }

            return parts.joinToString("\n\n")
        }

        private fun truncateForSummary(text: String, maxChars: Int): String {
            if (text.length <= maxChars) return text
            val truncatedChars = text.length - maxChars
            return "${text.take(maxChars)}\n\n[... $truncatedChars more characters truncated]"
        }
    }

    /**
     * 执行会话压缩流程。
     *
     * @param session 当前活跃会话实例。
     * @param force 是否强制触发（忽略 Token 阈值）。
     * @param contextTokens 当前已用 Token。
     * @param contextWindow 上下文总窗口大小。
     * @return 若成功压缩并提交注入块则返回 true；未触发或降级跳过返回 false。
     * @throws CancellationException 若被外部取消，绝不捕获，直接抛出以恪守“无事发生”原则。
     */
    suspend fun compact(
        session: Okia,
        force: Boolean = false,
        contextTokens: Long = 0L,
        contextWindow: Long = 0L,
        replyTokens: Int = 4096,
    ): Boolean {
        if (!force && !shouldCompact(contextTokens, contextWindow, thresholdRatio)) return false
        val history = session.conversation.value.history.map { it.message }
        val checkpoint = prepare(history, contextWindow, replyTokens) ?: return false
        currentCoroutineContext().ensureActive()
        session.append(checkpoint)
        return true
    }

    internal suspend fun prepare(
        history: List<Message>,
        window: Long,
        replyTokens: Int,
        generate: suspend (String, String) -> String = summaryGenerator::generate,
    ): Message.User? {
        val projected = ContextPruner.prune(history).messages
        val previous = projected.firstOrNull()?.let(ContextPruner::payloadOf)
        val visible = projected.filterNot(ContextPruner::isCheckpoint)
        val raw = history.withIndex().filterNot { ContextPruner.isCheckpoint(it.value) }
        val firstVisible = raw.size - visible.size
        if (firstVisible < 0 || visible.size < 2) return null
        val users = visible.indices.filter { visible[it] is Message.User }
        val protectedFrom = users.getOrNull((users.size - keepTurns.coerceAtLeast(1)).coerceAtLeast(0)) ?: return null
        val system = CompactorPrompts.SUMMARIZATION_SYSTEM_PROMPT
        val inputBudget = (window - replyTokens.coerceAtLeast(0) - 512).coerceAtMost(64_000)
        if (inputBudget <= 0) return null
        var selected = 0
        var estimated = RequestContextBudget.text(previous?.summary) + 512
        val unresolved = mutableSetOf<String>()
        for (index in 0 until protectedFrom) {
            val message = visible[index]
            if (message is Message.User && message.content.any { it is ContentBlock.Image } ||
                message is Message.ToolResult && (message.outcome as? ToolCallOutcome.Success)?.images?.isNotEmpty() == true
            ) break
            if (message is Message.Assistant) {
                message.message.content.filterIsInstance<ContentBlock.ToolCall>().forEach { unresolved += it.id }
            }
            if (message is Message.ToolResult) unresolved -= message.callId
            estimated += RequestContextBudget.text(serializeConversation(listOf(message))) + 8
            if (estimated > inputBudget) break
            if (unresolved.isEmpty() && visible[index + 1] !is Message.ToolResult) selected = index + 1
        }
        if (selected == 0) return null
        val old = serializeConversation(visible.take(selected))
        if (old.isBlank()) return null
        val prompt = CompactorPrompts.buildSummarizationUserPrompt(old, previous?.summary)
        if (RequestContextBudget.text(prompt) + RequestContextBudget.text(system) + 32 > inputBudget) return null
        val summary = runCatchingCancellable { generate(system, prompt).trim() }.getOrElse {
            Logger.w(LOG_TAG, "summary request failed", it)
            return null
        }
        if (summary.isBlank()) return null
        val covered = raw[firstVisible + selected - 1].index + 1
        val before = projected.sumOf(RequestContextBudget::message)
        fun checkpoint(after: Long? = null) = Message.User(listOf(ContentBlock.Text(SummaryBlock.block(
            SummaryBlock.Payload(keepTurns, summary, covered, before, after),
        ))))
        val after = ContextPruner.prune(history + checkpoint()).messages.sumOf(RequestContextBudget::message)
        return checkpoint(after).takeIf { after + 32 < before }
    }

    private fun textOf(user: Message.User): String =
        user.content.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }
}
