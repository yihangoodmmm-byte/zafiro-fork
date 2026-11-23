package com.niki914.zafiro.platform.conflict

/** 全名 = `provider.name`。provider 为 native / mcp.<server> / ext.<id> / harness。 */
data class QualifiedToolName(
    val provider: String,
    val name: String,
) {
    val qualified: String
        get() = "$provider.$name"

    companion object {
        fun parse(raw: String): QualifiedToolName? {
            val text = raw.trim()
            val dot = text.lastIndexOf('.')
            if (dot <= 0 || dot == text.lastIndex) return null
            return QualifiedToolName(
                provider = text.substring(0, dot),
                name = text.substring(dot + 1),
            )
        }
    }
}

sealed interface ToolResolution {
    data class Resolved(val tool: QualifiedToolName) : ToolResolution
    data class Ambiguous(val candidates: List<QualifiedToolName>) : ToolResolution
    data object NotFound : ToolResolution
}

object ToolNamespace {

    /**
     * 解析模型给出的工具名。
     *
     * 带 provider 的写法精确匹配；裸名只在唯一时允许自动解析，
     * 同名多实现必须由用户配置的默认 provider 消歧，否则返回歧义。
     */
    fun resolve(
        registered: Collection<QualifiedToolName>,
        requested: String,
        defaultProvider: String? = null,
    ): ToolResolution {
        val text = requested.trim()
        if (text.isEmpty()) return ToolResolution.NotFound

        val qualified = QualifiedToolName.parse(text)
        if (qualified != null) {
            return if (registered.contains(qualified)) {
                ToolResolution.Resolved(qualified)
            } else {
                ToolResolution.NotFound
            }
        }

        val candidates = registered.filter { it.name == text }.distinct()
        return when (candidates.size) {
            0 -> ToolResolution.NotFound
            1 -> ToolResolution.Resolved(candidates.first())
            else -> {
                val preferred = defaultProvider?.let { provider ->
                    candidates.firstOrNull { it.provider == provider }
                }
                if (preferred != null) {
                    ToolResolution.Resolved(preferred)
                } else {
                    ToolResolution.Ambiguous(candidates.sortedBy { it.provider })
                }
            }
        }
    }

    /** 注册冲突：同名同 provider 才算重复，不同 provider 可以并存。 */
    fun duplicates(registered: Collection<QualifiedToolName>): List<QualifiedToolName> =
        registered.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.toList()
}
