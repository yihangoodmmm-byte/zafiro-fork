package com.niki914.zafiro.chat.agentic

import com.niki914.zafiro.chat.ResolvedTools
import com.niki914.zafiro.settings.model.RuntimeLlmConfig
import com.niki914.zafiro.settings.model.RuntimeSkillMetadata

data class PromptComposeResult(
    val finalSystemPrompt: String,
)

data class PromptComposerInput(
    val additionalInstructions: String,
    val memoryItems: List<String> = emptyList(),
    val tools: ResolvedTools = ResolvedTools(),
    val enabledSkills: List<RuntimeSkillMetadata> = emptyList(),
    val sandboxPaths: Set<String> = emptySet(),
)

class PromptComposer {

    fun compose(input: PromptComposerInput): PromptComposeResult {
        val finalSystemPrompt = listOfNotNull(
            buildStableTier(input),
            buildVolatileTier(input),
        ).joinToString(separator = "\n\n")

        return PromptComposeResult(finalSystemPrompt = finalSystemPrompt)
    }

    // --- Stable tier: identity, tools, skills, guidance (cacheable across turns) ---

    private fun buildStableTier(input: PromptComposerInput): String {
        val customIdentity = input.additionalInstructions.trim()
        val identity = customIdentity.ifBlank { DEFAULT_AGENT_IDENTITY }
        val envBlock = renderEnvironmentBlock(input.sandboxPaths)
        return listOfNotNull(
            identity,
            PERSONA_PRECEDENCE_GUIDANCE.takeIf { customIdentity.isNotBlank() },
            envBlock,
            renderToolContext(input.tools),
            renderSkillContext(input.enabledSkills)
                .takeIf { hasBuiltinTool(input, "load_skill") },
            TASK_COMPLETION_GUIDANCE.takeIf { hasAnyTool(input) },
            TOOL_USE_ENFORCEMENT_GUIDANCE.takeIf { hasAnyTool(input) },
            EXECUTION_RULES_GUIDANCE.takeIf { hasAnyTool(input) },
            MEMORY_GUIDANCE.takeIf { hasBuiltinTool(input, "memory") },
            SKILLS_GUIDANCE.takeIf { hasBuiltinTool(input, "load_skill") },
        ).joinToString(separator = "\n\n")
    }

    // --- Volatile tier: memory snapshot (per-session) ---

    private fun buildVolatileTier(input: PromptComposerInput): String? {
        val items = input.memoryItems.map(String::trim).filter(String::isNotBlank)
        if (items.isEmpty()) return null
        val separator = "═".repeat(46)
        val content = items.joinToString("\n§\n")
        return "$separator\nMEMORY\n$separator\n$content"
    }

    // --- Tool context ---

    private fun renderToolContext(
        tools: ResolvedTools,
    ): String? {
        val blocks = listOfNotNull(
            renderNameBlock("builtin_tools", tools.builtinTools.map { it.name }),
            renderNameBlock("custom_py_tools", tools.customPyTools.map { it.name }),
        )
        if (blocks.isEmpty()) return null
        return "## Tool Context\n\n${blocks.joinToString(separator = "\n\n")}"
    }

    private fun renderNameBlock(tag: String, names: List<String>): String? {
        val normalized = names.map(String::trim).filter(String::isNotBlank).distinct().sorted()
        if (normalized.isEmpty()) return null
        return normalized.joinToString(
            separator = "\n",
            prefix = "<$tag>\n",
            postfix = "\n</$tag>",
        ) { "- $it" }
    }

    // --- Skill context ---

    private fun renderSkillContext(skills: List<RuntimeSkillMetadata>): String? {
        val entries = skills
            .mapNotNull { skill ->
                val id = skill.id.trim()
                if (id.isBlank()) return@mapNotNull null
                val name = skill.name.trim().ifBlank { id }
                val desc = skill.description.trim()
                val dir = skill.absoluteDir.trim()
                buildString {
                    appendLine("  <skill>")
                    appendLine("    <id>${escapeXmlText(id)}</id>")
                    appendLine("    <name>${escapeXmlText(name)}</name>")
                    if (desc.isNotBlank()) {
                        appendLine("    <description>${escapeXmlText(desc)}</description>")
                    }
                    if (dir.isNotBlank()) appendLine("    <dir>${escapeXmlText(dir)}</dir>")
                    append("  </skill>")
                }
            }
            .sorted()
        if (entries.isEmpty()) return null

        return buildString {
            appendLine("## Skills")
            appendLine()
            appendLine(
                "The skills below are reference material written by the user: known procedures, " +
                        "pitfalls and conventions for particular kinds of task. When one of them " +
                        "covers what you are about to do, load it with load_skill and treat it as " +
                        "guidance for how this work is normally done here. They describe method, " +
                        "not identity — the user's own instructions still decide what you say " +
                        "and how you say it."
            )
            appendLine()
            appendLine("<available_skills>")
            entries.forEach { appendLine(it) }
            append("</available_skills>")
        }
    }

    // --- Environment block ---

    /** 私有存储路径提示块：告诉 Agent 这些目录可自由读写，零权限。 */
    private fun renderEnvironmentBlock(paths: Set<String>): String? {
        if (paths.isEmpty()) return null
        val pathList = paths.sorted().joinToString("\n") { "- $it" }
        return buildString {
            appendLine("# Environment")
            appendLine()
            appendLine("Private app storage (read/write freely, no storage permission required):")
            appendLine(pathList)
            appendLine()
            appendLine("Files outside these directories are shared storage and may require")
            appendLine("storage permission that is not currently granted — prefer private")
            appendLine("directories. If a task requires public files, tell the user.")
        }
    }

    // --- Helpers ---

    private fun hasBuiltinTool(input: PromptComposerInput, name: String): Boolean {
        return input.tools.builtinTools.any { it.name == name }
    }

    private fun hasAnyTool(input: PromptComposerInput): Boolean {
        return input.tools.builtinTools.isNotEmpty() ||
                input.tools.customPyTools.isNotEmpty() ||
                input.tools.mcpServers.any { it.enabled }
    }

    /** skill 元数据来自用户可编辑的第三方文件，必须先转义，否则可用闭合标签伪造提示词段落。 */
    private fun escapeXmlText(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    // --- Guidance constants ---

    companion object {
        fun buildMemoryItems(config: RuntimeLlmConfig): List<String> {
            val memories = config.memories.map(String::trim).filter(String::isNotBlank)
            if (memories.isNotEmpty()) {
                return memories
            }
            return listOfNotNull(config.memoryPrompt.trim().takeIf { it.isNotBlank() })
        }

        /**
         * 用户没有填写系统提示词时的兜底身份。
         * 只描述「是什么」，不预设人格、立场与语气，避免和用户后续填写的人格争夺角色定义。
         */
        internal const val DEFAULT_AGENT_IDENTITY =
            "You are an AI agent running inside an Android app. " +
                    "Your capabilities come from the tools provided to you. " +
                    "You have no name or persona of your own unless the user gives you one."

        /**
         * 仅在用户填写了自定义提示词时注入：明确「用户的人设 > 功能层」，
         * 使后续的功能性块无法在角色定义上压过用户。
         */
        internal const val PERSONA_PRECEDENCE_GUIDANCE =
            "# Precedence\n" +
                    "The identity at the top of this prompt is the user's own specification of " +
                    "who you are and how you speak. On matters of role, persona, tone, language " +
                    "and style it outranks every block below. The remaining blocks describe tool " +
                    "mechanics and runtime facts only — follow them as mechanics, and when a " +
                    "mechanical rule appears to contradict the user's identity, the identity wins."

        internal const val TASK_COMPLETION_GUIDANCE =
            "# Finishing the job\n" +
                    "When the user asks you to build, run, or verify something, the deliverable is " +
                    "a working result backed by real tool output — not a description of one. " +
                    "Do not stop after writing a stub, a plan, or a single command. Keep working " +
                    "until you have actually exercised the code or produced the requested result, " +
                    "then report what real execution returned.\n" +
                    "If a tool, install, or network call fails and blocks the real path, say so " +
                    "directly and try an alternative (different package manager, different " +
                    "approach, ask the user). NEVER substitute plausible-looking fabricated " +
                    "output (made-up data, invented file contents, synthesised API responses) " +
                    "for results you couldn't actually produce. Reporting a blocker honestly " +
                    "is always better than inventing a result."

        internal const val TOOL_USE_ENFORCEMENT_GUIDANCE =
            "# Tool use\n" +
                    "When the user asks you to perform an action, perform it with tools instead of " +
                    "describing it. When you announce an action, make the corresponding tool call " +
                    "in the same response.\n" +
                    "This applies to action requests. A question, a discussion or a role-play is " +
                    "not an action request — answer it directly."

        internal const val EXECUTION_RULES_GUIDANCE =
            "# Execution rules\n" +
                    "Tool actions may be blocked by this app's execution rules, which are " +
                    "user-configurable in app settings — not system restrictions. A block means " +
                    "the user declined the action or the rule is too strict; the user can adjust " +
                    "the rule in app settings. Do not describe blocks as system policy."

        internal const val MEMORY_GUIDANCE =
            "You have persistent memory across sessions. Save durable facts using the memory " +
                    "tool: user preferences, environment details, tool quirks, and stable conventions. " +
                    "Memory is injected into every turn, so keep it compact and focused on facts that " +
                    "will still matter later.\n" +
                    "Prioritize what reduces future user steering — the most valuable memory is one " +
                    "that prevents the user from having to correct or remind you again. " +
                    "User preferences and recurring corrections matter more than procedural task details.\n" +
                    "Do NOT save task progress, session outcomes, completed-work logs, or temporary TODO " +
                    "state to memory; use conversation history to recall those from past interactions. " +
                    "Specifically: do not record PR numbers, issue numbers, commit SHAs, 'fixed bug X', " +
                    "'submitted PR Y', 'Phase N done', file counts, or any artifact that will be stale " +
                    "in 7 days. If a fact will be stale in a week, it does not belong in memory. " +
                    "If you've discovered a new way to do something, solved a problem that could be " +
                    "necessary later, save it as a skill with python — write a SKILL.md under " +
                    "the skills directory (see the skill-creator skill).\n" +
                    "Write memories as declarative facts, not instructions to yourself. " +
                    "'User prefers concise responses' ✓ — 'Always respond concisely' ✗. " +
                    "'Project uses pytest with xdist' ✓ — 'Run tests with pytest -n 4' ✗. " +
                    "Imperative phrasing gets re-read as a directive in later sessions and can " +
                    "cause repeated work or override the user's current request. Procedures and " +
                    "workflows belong in skills, not memory."

        internal const val SKILLS_GUIDANCE =
            "# Skills\n" +
                    "Skills contain specialized knowledge — API endpoints, tool-specific " +
                    "commands, and the user's preferred conventions and quality standards. When " +
                    "a skill covers the task at hand, load it before improvising: it records how " +
                    "this work is normally done here.\n" +
                    "load_skill returns the skill's SKILL.md content; if it exceeds the limit, " +
                    "the result ends with the absolute path to the file — use shell to read " +
                    "the full content from there."
    }
}
