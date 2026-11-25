package com.niki914.zafiro.chat

import com.niki914.okia.tooling.DefaultToolRegistry
import com.niki914.okia.tooling.ToolRegistry
import com.niki914.zafiro.settings.model.LlmProtocol

object RoleDiscussion {
    suspend fun reply(protocol: String, config: ResolvedLlmConfig, prompt: String, transcript: String, tools: ToolRegistry? = null): String {
        val assembler = OkiaSessionAssembler(tools ?: DefaultToolRegistry(), null, ensureImageSaver = { null })
        return assembler.generateSummary(LlmProtocol.fromWire(protocol), config, prompt, transcript, allowRegisteredTools = tools != null)
            .take(6000).also { check(it.isNotBlank()) }
    }
}
