package com.niki914.zafiro.chat.agentic.buildin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A runtime check of one postcondition, not a verdict on the user's whole task. */
enum class StepCheck {
    MemoryConfirmed, ImageConfirmed, ScreenPackageConfirmed, ScreenObserved, Mismatch, Unavailable,
    CommandExitedZero, CommandExitedNonzero, CommandPending, PythonCompleted, PythonFailed, PythonTimedOut, McpResponse;

    companion object {
        fun fromResult(toolName: String, raw: String?): StepCheck? {
            if (raw.isNullOrBlank()) return null
            if (toolName.startsWith("mcp__")) return null
            if (raw.startsWith('{') && "zafiro_python_check" in raw) {
                val payload = parseObject(raw)
                val check = fromWire((payload?.get("zafiro_python_check") as? JsonPrimitive)?.contentOrNull)
                val name = (payload?.get("tool") as? JsonPrimitive)?.contentOrNull
                if (name == toolName && check != null && check in pythonChecks) return check
            }
            val allowed = when (toolName) {
                "shell", "ssh" -> setOf(CommandExitedZero, CommandExitedNonzero, CommandPending)
                "execute_python" -> pythonChecks
                "memory" -> setOf(MemoryConfirmed, Mismatch, Unavailable)
                "screenshot", "view_image" -> setOf(ImageConfirmed, Mismatch, Unavailable)
                "screen_operation_accessibility" -> setOf(ScreenPackageConfirmed, ScreenObserved, Mismatch, Unavailable)
                else -> return null
            }
            val check = if (toolName == "screen_operation_accessibility" || toolName == "execute_python") {
                TextToolResultCodec.decode(raw)?.stepCheck
            } else {
                val json = parseObject(raw)
                fromWire((json?.get("zafiro_step_check") as? JsonPrimitive)?.contentOrNull)
            }
            return check?.takeIf { it in allowed }
        }

        private val pythonChecks = setOf(PythonCompleted, PythonFailed, PythonTimedOut)

        private fun parseObject(raw: String): JsonObject? =
            runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()

        internal fun withoutReceipt(toolName: String, raw: String): String {
            if (fromResult(toolName, raw).let { it == null || it in pythonChecks }) return raw
            return if (toolName == "screen_operation_accessibility" || toolName == "execute_python") {
                val result = TextToolResultCodec.decode(raw) ?: return raw
                TextToolResultCodec.encode(result.copy(stepCheck = null))
            } else {
                val json = Json.parseToJsonElement(raw) as JsonObject
                JsonObject(json - "zafiro_step_check").toString()
            }
        }

        internal fun fromWire(value: String?): StepCheck? = entries.firstOrNull { it.name == value }
    }
}
