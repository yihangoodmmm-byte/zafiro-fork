package com.niki914.zafiro.platform.conflict

/**
 * 调度去重键。
 *
 * 同一次计划触发在同一时刻只应执行一次；键由自动化 id、触发 id、计划时刻共同决定，
 * 重启后按持久化的键判断，不依赖内存。
 */
object SchedulerDedup {

    private const val SEPARATOR = "|"

    fun key(automationId: String, triggerId: String, scheduledAtMillis: Long): String {
        require(automationId.isNotBlank() && triggerId.isNotBlank())
        require(SEPARATOR !in automationId && SEPARATOR !in triggerId) { "Identifiers cannot contain the key separator." }
        return listOf(automationId, triggerId, scheduledAtMillis.toString()).joinToString(SEPARATOR)
    }

    fun shouldRun(lastCompletedKey: String?, nextKey: String): Boolean =
        lastCompletedKey != nextKey

    /** 解析键用于诊断；格式不符返回 null。 */
    fun parse(raw: String): Triple<String, String, Long>? {
        val parts = raw.split(SEPARATOR)
        if (parts.size != 3) return null
        val at = parts[2].toLongOrNull() ?: return null
        if (parts[0].isEmpty() || parts[1].isEmpty()) return null
        return Triple(parts[0], parts[1], at)
    }
}
