package com.niki914.zafiro.platform

import com.niki914.zafiro.platform.conflict.GenerationCheck
import com.niki914.zafiro.platform.conflict.TargetGeneration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetGenerationTest {

    @Test
    fun `matching generation is valid`() {
        assertEquals(GenerationCheck.Valid, TargetGeneration.validate(3L, 3L))
    }

    @Test
    fun `older generation is stale`() {
        val check = TargetGeneration.validate(2L, 3L)

        assertEquals(GenerationCheck.Stale(expected = 2L, actual = 3L), check)
    }

    @Test
    fun `a removed screen does not fall back`() {
        val check = TargetGeneration.validateScreen("virtual-1", 4L, emptyMap())

        assertTrue(check is GenerationCheck.Missing)
    }

    @Test
    fun `screen check uses the alias generation`() {
        val check = TargetGeneration.validateScreen(
            observedAlias = "virtual-1",
            observedGeneration = 1L,
            current = mapOf("virtual-1" to 5L),
        )

        assertEquals(GenerationCheck.Stale(expected = 1L, actual = 5L), check)
    }
}
