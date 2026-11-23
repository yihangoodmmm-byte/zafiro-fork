package com.niki914.zafiro.platform

import com.niki914.zafiro.platform.task.ResourceKey
import com.niki914.zafiro.platform.task.ResourceKind
import com.niki914.zafiro.platform.task.ResourceLeaseCheck
import com.niki914.zafiro.platform.task.ResourceLeaseRegistry
import com.niki914.zafiro.platform.task.ResourceLeaseResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourceLeaseTest {

    private var now = 1_000L
    private val registry = ResourceLeaseRegistry(clock = { now })
    private val terminal = ResourceKey(ResourceKind.TERMINAL, "s1", 1L)

    @Test
    fun `stale generation cannot steal a rebuilt resource even after release`() {
        val newer = terminal.copy(generation = 9)
        val lease = registry.acquire(newer, "new-owner", 60_000) as ResourceLeaseResult.Acquired
        val rejected = registry.acquire(terminal, "old-owner", 60_000, preempt = true)
        assertEquals("stale-target", (rejected as ResourceLeaseResult.Rejected).code)
        registry.release(lease.leaseId)
        assertTrue(registry.acquire(terminal, "old-owner", 60_000) is ResourceLeaseResult.Rejected)
    }

    @Test
    fun `lease cannot authorize a different resource id`() {
        val lease = registry.acquire(terminal, "owner", 60_000) as ResourceLeaseResult.Acquired
        assertEquals(ResourceLeaseCheck.NotHeld, registry.validate(lease.leaseId, terminal.copy(id = "another")))
    }

    @Test
    fun `a free resource is acquired`() {
        assertTrue(registry.acquire(terminal, "task-a", 60_000L) is ResourceLeaseResult.Acquired)
    }

    @Test
    fun `a second owner is rejected unless it preempts`() {
        registry.acquire(terminal, "task-a", 60_000L)

        val rejected = registry.acquire(terminal, "task-b", 60_000L)
        val preempted = registry.acquire(terminal, "task-b", 60_000L, preempt = true)

        assertEquals("resource-busy", (rejected as ResourceLeaseResult.Rejected).code)
        assertTrue(preempted is ResourceLeaseResult.Acquired)
    }

    @Test
    fun `release by owner only clears that owner`() {
        registry.acquire(terminal, "task-a", 60_000L)
        registry.acquire(ResourceKey(ResourceKind.SCREEN, "real", 0L), "task-b", 60_000L)

        val removed = registry.releaseByOwner("task-a")

        assertEquals(1, removed)
        assertEquals("task-b", registry.holder(ResourceKey(ResourceKind.SCREEN, "real", 0L)))
    }

    @Test
    fun `a generation change invalidates the lease`() {
        val lease = registry.acquire(terminal, "task-a", 60_000L) as ResourceLeaseResult.Acquired

        val check = registry.validate(lease.leaseId, ResourceKey(ResourceKind.TERMINAL, "s1", 2L))

        assertEquals(ResourceLeaseCheck.StaleGeneration(2L, 1L), check)
    }

    @Test
    fun `expired leases are reclaimed`() {
        registry.acquire(terminal, "task-a", 1_000L)
        now += 10_000L

        assertTrue(registry.acquire(terminal, "task-b", 60_000L) is ResourceLeaseResult.Acquired)
    }
}
