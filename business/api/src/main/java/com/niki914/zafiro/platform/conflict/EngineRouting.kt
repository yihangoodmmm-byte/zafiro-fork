package com.niki914.zafiro.platform.conflict

/** 会话的执行引擎。创建时锁定，运行中不可改。 */
enum class EngineKind {
    NATIVE,
    HARNESS,
}

/** 会话与引擎实例的绑定。[instanceId] 用于拒绝「同引擎换了进程」的旧调用。 */
data class EngineBinding(
    val conversationId: String,
    val engineKind: EngineKind,
    val instanceId: String,
)

sealed interface EngineDecision {
    data class Execute(val binding: EngineBinding) : EngineDecision
    data object CreateAllowed : EngineDecision
    data class Rejected(val code: String, val message: String) : EngineDecision
}

object EngineRouting {

    /** 新建会话时决定能否用请求的引擎。Harness 未就绪就不让建，避免静默降级。 */
    fun decideCreate(requested: EngineKind, harnessReady: Boolean): EngineDecision = when {
        requested == EngineKind.HARNESS && !harnessReady ->
            EngineDecision.Rejected(
                code = "harness-not-ready",
                message = "Harness 引擎未就绪；先完成运行环境安装，或新建原生会话。",
            )
        else -> EngineDecision.CreateAllowed
    }

    /**
     * 执行前校验绑定。会话已绑定就必须用绑定引擎；实例不在运行集合里说明进程已换代。
     */
    fun decideExecute(
        binding: EngineBinding?,
        requested: EngineKind,
        runningInstances: Set<String>,
    ): EngineDecision {
        if (binding == null) {
            return EngineDecision.Rejected(
                code = "session-unbound",
                message = "会话尚未绑定引擎；先创建会话再提交。",
            )
        }
        if (binding.engineKind != requested) {
            return EngineDecision.Rejected(
                code = "engine-mismatch",
                message = "会话绑定 ${binding.engineKind.name.lowercase()} 引擎，不能由 ${requested.name.lowercase()} 执行。",
            )
        }
        if (binding.instanceId !in runningInstances) {
            return EngineDecision.Rejected(
                code = "engine-instance-gone",
                message = "原引擎实例已不在运行；重启引擎后再提交，已派发的副作用不会重放。",
            )
        }
        return EngineDecision.Execute(binding)
    }
}
