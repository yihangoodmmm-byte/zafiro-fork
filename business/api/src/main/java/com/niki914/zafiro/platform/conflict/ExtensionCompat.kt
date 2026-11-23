package com.niki914.zafiro.platform.conflict

import com.niki914.zafiro.platform.contract.CapabilityId
import com.niki914.zafiro.platform.contract.CapabilitySnapshot

/** 扩展声明的运行条件。缺失字段的含义是「不限制」，不是「满足」。 */
data class ExtensionRequirement(
    val hostApiRange: VersionRange?,
    val requiredCapabilities: Set<CapabilityId> = emptySet(),
    val peerEngineVersion: VersionRange? = null,
)

/** 兼容检查结果，拒绝时列出全部原因，不逐条试探。 */
sealed interface CompatResult {
    data object Compatible : CompatResult
    data class Incompatible(val reasons: List<String>) : CompatResult
}

object ExtensionCompat {

    fun check(
        hostApiVersion: String,
        capabilities: CapabilitySnapshot,
        requirement: ExtensionRequirement,
        engineVersion: String? = null,
    ): CompatResult {
        val reasons = mutableListOf<String>()

        val hostRange = requirement.hostApiRange
        if (hostRange != null) {
            val host = SemVer.parse(hostApiVersion)
            if (host == null) {
                reasons += "invalid-host-version"
            } else if (!hostRange.contains(host)) {
                reasons += "host-api-out-of-range"
            }
        }

        val engineRange = requirement.peerEngineVersion
        if (engineRange != null) {
            val engine = SemVer.parse(engineVersion)
            if (engine == null) {
                reasons += "engine-version-unavailable"
            } else if (!engineRange.contains(engine)) {
                reasons += "engine-out-of-range"
            }
        }

        for (capability in requirement.requiredCapabilities) {
            if (!capabilities.isAvailable(capability)) {
                reasons += "capability-unavailable:${capability.name.lowercase()}"
            }
        }

        return if (reasons.isEmpty()) CompatResult.Compatible else CompatResult.Incompatible(reasons)
    }
}