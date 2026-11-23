package com.niki914.zafiro.platform

import com.niki914.zafiro.platform.conflict.LeaseOwner
import com.niki914.zafiro.platform.conflict.LeaseOwnerKind
import com.niki914.zafiro.platform.conflict.LeaseValidation
import com.niki914.zafiro.platform.conflict.ScreenLeaseRegistry
import com.niki914.zafiro.platform.conflict.ScreenLeaseResult
import com.niki914.zafiro.platform.conflict.ScreenTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenLeaseTest {

    private var now = 1_000L
    private val registry = ScreenLeaseRegistry(clock = { now })
    private val task = LeaseOwner(LeaseOwnerKind.TASK, "task-a")
    private val otherTask = LeaseOwner(LeaseOwnerKind.TASK, "task-b")
    private val human = LeaseOwner(LeaseOwnerKind.HUMAN, "user")

    @Test
    fun `a free screen is acquired`() {
        val result = registry.acquire(ScreenTarget("virtual-1", 1L), task, 60_000L)

        assertTrue(result is ScreenLeaseResult.Acquired)
    }

    @Test
    fun `a second writer is rejected while the lease is alive`() {
        registry.acquire(ScreenTarget("virtual-1", 1L), task, 60_000L)

        val rejected = registry.acquire(ScreenTarget("virtual-1", 1L), otherTask, 60_000L)
            as ScreenLeaseResult.Rejected

        assertEquals("screen-busy", rejected.code)
        assertEquals(task, rejected.holder)
    }

    @Test
    fun `human input preempts a task lease`() {
        registry.acquire(ScreenTarget("real", 0L), task, 60_000L)

        val result = registry.acquire(ScreenTarget("real", 0L), human, 60_000L)

        assertEquals(task, (result as ScreenLeaseResult.Acquired).preempted)
        assertEquals(human, registry.holder("real"))
    }

    @Test
    fun `a task cannot preempt another task`() {
        registry.acquire(ScreenTarget("real", 0L), task, 60_000L)

        val result = registry.acquire(ScreenTarget("real", 0L), otherTask, 60_000L)

        assertTrue(result is ScreenLeaseResult.Rejected)
    }

    @Test
    fun `the same owner keeps the same lease`() {
        val first = registry.acquire(ScreenTarget("real", 0L), task, 60_000L) as ScreenLeaseResult.Acquired
        val second = registry.acquire(ScreenTarget("real", 0L), task, 60_000L) as ScreenLeaseResult.Acquired

        assertEquals(first.leaseId, second.leaseId)
    }

    @Test
    fun `release frees the screen for another writer`() {
        val lease = registry.acquire(ScreenTarget("real", 0L), task, 60_000L) as ScreenLeaseResult.Acquired

        assertTrue(registry.release(lease.leaseId))
        assertTrue(registry.acquire(ScreenTarget("real", 0L), otherTask, 60_000L) is ScreenLeaseResult.Acquired)
    }

    @Test
    fun `a new generation invalidates the old lease`() {
        val lease = registry.acquire(ScreenTarget("virtual-1", 1L), task, 60_000L) as ScreenLeaseResult.Acquired

        val check = registry.validate(lease.leaseId, ScreenTarget("virtual-1", 2L))

        assertEquals(LeaseValidation.StaleTarget(2L, 1L), check)
    }

    @Test
    fun `expired leases are reclaimed`() {
        registry.acquire(ScreenTarget("real", 0L), task, 1_000L)
        now += 5_000L

        val result = registry.acquire(ScreenTarget("real", 0L), otherTask, 60_000L)

        assertTrue(result is ScreenLeaseResult.Acquired)
    }
}