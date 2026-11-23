package com.niki914.zafiro.platform.contract

/**
 * 能力的真实可用状态。
 *
 * [AVAILABLE] 表示刚刚探测到可用；[DEGRADED] 表示可用但缺功能；
 * [UNKNOWN] 与 [UNAVAILABLE] 必须分开——「没探测到」不是「确认没有」。
 */
enum class CapabilityStatus {
    AVAILABLE,
    DEGRADED,
    UNAVAILABLE,
    UNKNOWN,
}

/** 能力 id。新增能力在这里登记，设置页与工具面共用同一份枚举。 */
enum class CapabilityId {
    SCREEN_OBSERVE,
    SCREEN_INPUT,
    SCREEN_CAPTURE,
    SCREEN_VIRTUAL_DISPLAY,
    DEVICE_SHIZUKU,
    DEVICE_ROOT,
    BROWSER_NAVIGATE,
    BROWSER_ACT,
    BROWSER_SNAPSHOT,
    BROWSER_MULTI_PROFILE,
    TERMINAL_BIONIC,
    TERMINAL_LINUX,
    TERMINAL_PTY,
    PYTHON_RUNTIME,
    HARNESS_ENGINE,
    EXTENSION_NODE,
    MCP_SERVER,
    LOCAL_MODEL,
    SPEECH_RECOGNIZE,
    SPEECH_SYNTHESIZE,
    NETWORK_PROXY,
}

/**
 * 一条能力事实。
 *
 * [source] 是事实来源（probe / manifest / user-grant / fallback），
 * [reason] 是给人看的原因，禁止把回落值伪装成探测结果。
 */
data class CapabilityFact(
    val id: CapabilityId,
    val status: CapabilityStatus,
    val source: String,
    val observedAtMillis: Long,
    val reason: String? = null,
)

/** 能力快照：同一时刻的事实集合，[revision] 单调递增供订阅方去重。 */
data class CapabilitySnapshot(
    val revision: Long,
    val facts: Map<CapabilityId, CapabilityFact>,
) {
    fun statusOf(id: CapabilityId): CapabilityStatus =
        facts[id]?.status ?: CapabilityStatus.UNKNOWN

    fun isAvailable(id: CapabilityId): Boolean = statusOf(id) == CapabilityStatus.AVAILABLE

    /** 写操作依赖可用或降级：未知一律不算可用。 */
    fun permitsWrite(id: CapabilityId): Boolean = when (statusOf(id)) {
        CapabilityStatus.AVAILABLE -> true
        CapabilityStatus.DEGRADED -> false
        CapabilityStatus.UNAVAILABLE -> false
        CapabilityStatus.UNKNOWN -> false
    }
}
