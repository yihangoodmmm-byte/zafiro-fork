package com.niki914.zafiro.app.workbench

import com.niki914.okia.message.ToolCallOutcome
import com.niki914.okia.tooling.DefaultToolRegistry
import com.niki914.okia.tooling.ToolCallContext
import com.niki914.okia.tooling.ToolDescriptor
import com.niki914.okia.tooling.ToolExecutor
import com.niki914.okia.tooling.ToolKind
import com.niki914.zafiro.chat.ResolvedLlmConfig
import com.niki914.zafiro.chat.RoleDiscussion
import com.niki914.zafiro.repo.XRepo
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

internal data class DisplayAgentAction(val kind: String, val x: Int = 0, val y: Int = 0, val endX: Int = x, val endY: Int = y)

internal suspend fun runDisplayAgent(prompt: String, approveAndExecute: suspend (DisplayAgentAction) -> Boolean): String = withTimeout(90_000) {
    val config = XRepo.llmConfigs.document().activeConfig() ?: error("No active model")
    val budget = AtomicInteger(4)
    val registry = DefaultToolRegistry()
    registry.register(ToolDescriptor(
        name = "virtual_display_input",
        description = "Propose one user-confirmed input to the current 540x960 virtual display. No screen content is sent to the model. Do not guess unknown coordinates.",
        inputSchemaJson = """{"type":"object","properties":{"action":{"enum":["tap","swipe","back"]},"x":{"type":"integer"},"y":{"type":"integer"},"endX":{"type":"integer"},"endY":{"type":"integer"}},"required":["action"]}""",
        kind = ToolKind.Local,
    ), object : ToolExecutor {
        override suspend fun execute(call: ToolCallContext): ToolCallOutcome {
            if (budget.getAndDecrement() <= 0 || call.argumentsJson.length > 1024) return ToolCallOutcome.Failure("Action limit reached or input too long.")
            val args = runCatching { Json.parseToJsonElement(call.argumentsJson) }.getOrNull() as? JsonObject
                ?: return ToolCallOutcome.Failure("Invalid action.")
            fun coord(name: String, max: Int) = (args[name] as? JsonPrimitive)?.intOrNull?.takeIf { it in 0 until max }
            val kind = (args["action"] as? JsonPrimitive)?.content.orEmpty()
            val action = if (kind == "back") DisplayAgentAction(kind) else {
                val x = coord("x", 540) ?: return ToolCallOutcome.Failure("Invalid x.")
                val y = coord("y", 960) ?: return ToolCallOutcome.Failure("Invalid y.")
                when (kind) {
                    "tap" -> DisplayAgentAction(kind, x, y)
                    "swipe" -> DisplayAgentAction(kind, x, y, coord("endX", 540) ?: return ToolCallOutcome.Failure("Invalid endX."),
                        coord("endY", 960) ?: return ToolCallOutcome.Failure("Invalid endY."))
                    else -> return ToolCallOutcome.Failure("Unknown action.")
                }
            }
            if (approveAndExecute(action)) return ToolCallOutcome.Success("Input dispatched. Outcome not verified.")
            budget.set(0)
            return ToolCallOutcome.Failure("Input denied or unavailable. Do not replay it.")
        }
        override fun onInterrupt(call: ToolCallContext) = ToolCallOutcome.Interrupted()
    })
    val system = "Operate only the foreground virtual display with one explicit user approval per action. You cannot see the display. Never claim an application outcome from an input receipt."
    RoleDiscussion.reply(config.protocol, ResolvedLlmConfig(
        config.endpoint, config.apiKey, config.model, system, system,
        proxy = config.proxy, maxTokens = 768, retryMaxAttempts = 1,
    ), system, prompt, registry)
}
