package com.niki914.zafiro.chat.agentic.buildin.impl

import com.niki914.zafiro.chat.agentic.currentAgentTenantId
import com.niki914.zafiro.chat.agentic.buildin.BuiltinTool
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolResult
import com.niki914.zafiro.platform.browser.BrowserCommand
import com.niki914.zafiro.platform.browser.BrowserReply
import com.niki914.zafiro.platform.browser.BrowserRequest
import com.niki914.zafiro.platform.browser.BrowserService
import com.niki914.zafiro.platform.browser.BrowserTabState
import com.niki914.zafiro.service.findService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class BrowserBuiltin(override val name: String, private val command: BrowserCommand) : BuiltinTool() {
    override val defaultEnabled: Boolean = true
    override val description: String = when (command) {
        BrowserCommand.OPEN -> "Open a new owner-isolated browser tab at an HTTP(S) URL. Returns tab_id."
        BrowserCommand.SNAPSHOT -> "Read DOM/ARIA nodes from a tab. Use returned refs and generation for actions. Refresh after navigation or stale refs."
        BrowserCommand.CLICK -> "Click one visible node using ref and generation from browser_snapshot."
        BrowserCommand.TYPE -> "Type into an input node using ref and generation. replace defaults true."
        BrowserCommand.SCREENSHOT -> "Capture the browser tab viewport and return an image file the model can view."
        else -> "Browser operation: " + command.name.lowercase() + ". Tabs are private to this agent. Supply tab_id when targeting a particular tab."
    }
    override val inputSchemaJson: String = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            fun field(name: String, type: String) { put(name, buildJsonObject { put("type", type) }) }
            field("tab_id", "string")
            when (command) {
                BrowserCommand.OPEN, BrowserCommand.NAVIGATE -> field("url", "string")
                BrowserCommand.CLICK, BrowserCommand.TYPE -> {
                    field("ref", "string"); field("generation", "integer")
                    if (command == BrowserCommand.TYPE) { field("text", "string"); field("replace", "boolean") }
                }
                BrowserCommand.PRESS -> { field("key", "string"); field("ref", "string"); field("generation", "integer") }
                BrowserCommand.SCROLL -> { field("direction", "string"); field("amount", "integer") }
                BrowserCommand.WAIT -> { field("text", "string"); field("timeout_ms", "integer") }
                BrowserCommand.SET_VIEWPORT -> put("viewport", buildJsonObject {
                    put("type", "string"); put("enum", JsonArray(listOf("phone", "tablet", "desktop").map(::JsonPrimitive)))
                })
                else -> Unit
            }
        })
        val required = when (command) {
            BrowserCommand.OPEN, BrowserCommand.NAVIGATE -> listOf("url")
            BrowserCommand.CLICK -> listOf("ref", "generation")
            BrowserCommand.TYPE -> listOf("ref", "generation", "text")
            BrowserCommand.PRESS -> listOf("ref", "generation", "key")
            BrowserCommand.SET_VIEWPORT -> listOf("viewport")
            else -> emptyList()
        }
        put("required", JsonArray(required.map(::JsonPrimitive)))
        put("additionalProperties", false)
    }.toString()

    override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
        val owner = currentAgentTenantId()?.takeIf(String::isNotBlank)
            ?: return BuiltinToolResult.failure("BROWSER_OWNER_REQUIRED", "Browser tools require an agent execution context.")
        val args = try { Json.parseToJsonElement(request.argumentsJson) as? JsonObject }
            catch (_: IllegalArgumentException) { null }
            ?: return BuiltinToolResult.failure("INVALID_ARGUMENTS", "Expected a JSON object.")
        fun text(key: String) = (args[key] as? JsonPrimitive)?.contentOrNull
        val browser = findService<BrowserService>()
            ?: return BuiltinToolResult.failure("BROWSER_UNAVAILABLE", "Browser service is not installed.")
        val reply = browser.execute(owner, BrowserRequest(
            command = command, tabId = text("tab_id"), url = text("url"), ref = text("ref"),
            generation = (args["generation"] as? JsonPrimitive)?.longOrNull,
            text = text("text"), key = text("key"), direction = text("direction"),
            amount = (args["amount"] as? JsonPrimitive)?.intOrNull ?: 600,
            replace = (args["replace"] as? JsonPrimitive)?.booleanOrNull ?: true,
            timeoutMillis = ((args["timeout_ms"] as? JsonPrimitive)?.longOrNull ?: 10_000).coerceIn(100, 30_000),
            viewport = text("viewport"),
        ))
        val data = reply.asJson()
        return if (reply.ok) BuiltinToolResult.success(reply.message, data)
        else BuiltinToolResult.failure(reply.code, reply.message, data = data)
    }

    private fun BrowserReply.asJson(): JsonObject = buildJsonObject {
        put("execution_unknown", executionUnknown)
        tab?.let { put("tab", it.asJson()); put("tab_id", it.id); put("generation", it.generation) }
        put("tabs", JsonArray(tabs.map { it.asJson() }))
        put("nodes", JsonArray(nodes.map { node -> buildJsonObject {
            put("ref", node.ref); put("role", node.role); put("name", node.name); put("enabled", node.enabled)
            put("bounds", JsonArray(node.bounds.map(::JsonPrimitive)))
        } }))
        text?.let { put("text", it) }
        put("truncated", truncated)
        imagePath?.let { path -> put("image", buildJsonObject { put("path", path); put("mime_type", "image/png") }) }
        width?.let { put("width", it) }; height?.let { put("height", it) }
    }

    private fun BrowserTabState.asJson(): JsonObject = buildJsonObject {
        put("tab_id", id); put("title", title); put("url", url); put("generation", generation)
        put("loading", loading); put("human_control", humanControl); put("viewport", viewport)
        error?.let { put("error", it) }
    }

    companion object {
        val definitions: List<Pair<String, BrowserCommand>> = listOf(
            "browser_open" to BrowserCommand.OPEN, "browser_list_tabs" to BrowserCommand.LIST_TABS,
            "browser_follow_tab" to BrowserCommand.FOLLOW_TAB, "browser_close_tab" to BrowserCommand.CLOSE_TAB,
            "browser_navigate" to BrowserCommand.NAVIGATE, "browser_back" to BrowserCommand.BACK,
            "browser_forward" to BrowserCommand.FORWARD, "browser_reload" to BrowserCommand.RELOAD,
            "browser_snapshot" to BrowserCommand.SNAPSHOT, "browser_click" to BrowserCommand.CLICK,
            "browser_type" to BrowserCommand.TYPE, "browser_press" to BrowserCommand.PRESS,
            "browser_scroll" to BrowserCommand.SCROLL, "browser_get_text" to BrowserCommand.GET_TEXT,
            "browser_wait" to BrowserCommand.WAIT, "browser_screenshot" to BrowserCommand.SCREENSHOT,
            "browser_set_viewport" to BrowserCommand.SET_VIEWPORT,
        )
    }
}
