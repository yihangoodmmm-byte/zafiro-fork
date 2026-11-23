package com.niki914.zafiro.platform.conflict

/** 目标代次校验结果。旧代次必须拒绝，而不是回落到当前默认目标。 */
sealed interface GenerationCheck {
    data object Valid : GenerationCheck
    data class Stale(val expected: Long, val actual: Long) : GenerationCheck
    data object Missing : GenerationCheck
}

object TargetGeneration {

    fun validate(observedGeneration: Long, currentGeneration: Long?): GenerationCheck = when {
        currentGeneration == null -> GenerationCheck.Missing
        observedGeneration != currentGeneration ->
            GenerationCheck.Stale(expected = observedGeneration, actual = currentGeneration)
        else -> GenerationCheck.Valid
    }

    /** 屏幕目标：alias 与代次都必须一致；alias 消失视为缺失，不回落到真实屏。 */
    fun validateScreen(
        observedAlias: String,
        observedGeneration: Long,
        current: Map<String, Long>,
    ): GenerationCheck {
        val currentGeneration = current[observedAlias] ?: return GenerationCheck.Missing
        return validate(observedGeneration, currentGeneration)
    }
}
