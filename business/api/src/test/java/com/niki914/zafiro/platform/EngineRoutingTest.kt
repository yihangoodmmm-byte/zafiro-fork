package com.niki914.zafiro.platform

import com.niki914.zafiro.platform.conflict.EngineBinding
import com.niki914.zafiro.platform.conflict.EngineDecision
import com.niki914.zafiro.platform.conflict.EngineKind
import com.niki914.zafiro.platform.conflict.EngineRouting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineRoutingTest {

    @Test
    fun `harness session is refused when the engine is not ready`() {
        val decision = EngineRouting.decideCreate(EngineKind.HARNESS, harnessReady = false)

        assertTrue(decision is EngineDecision.Rejected)
        assertEquals("harness-not-ready", (decision as EngineDecision.Rejected).code)
    }

    @Test
    fun `native session is allowed even when harness is missing`() {
        val decision = EngineRouting.decideCreate(EngineKind.NATIVE, harnessReady = false)

        assertTrue(decision is EngineDecision.CreateAllowed)
    }

    @Test
    fun `execute rejects a request from the other engine`() {
        val binding = EngineBinding("c1", EngineKind.NATIVE, "instance-a")

        val decision = EngineRouting.decideExecute(binding, EngineKind.HARNESS, setOf("instance-a"))

        assertTrue(decision is EngineDecision.Rejected)
        assertEquals("engine-mismatch", (decision as EngineDecision.Rejected).code)
    }

    @Test
    fun `execute rejects a binding whose instance is gone`() {
        val binding = EngineBinding("c1", EngineKind.NATIVE, "instance-a")

        val decision = EngineRouting.decideExecute(binding, EngineKind.NATIVE, setOf("instance-b"))

        assertEquals("engine-instance-gone", (decision as EngineDecision.Rejected).code)
    }

    @Test
    fun `execute accepts a matching binding that is still running`() {
        val binding = EngineBinding("c1", EngineKind.HARNESS, "instance-a")

        val decision = EngineRouting.decideExecute(binding, EngineKind.HARNESS, setOf("instance-a"))

        assertTrue(decision is EngineDecision.Execute)
    }
}
