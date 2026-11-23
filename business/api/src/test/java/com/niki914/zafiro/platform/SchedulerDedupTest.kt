package com.niki914.zafiro.platform

import com.niki914.zafiro.platform.conflict.SchedulerDedup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SchedulerDedupTest {

    @Test
    fun `same trigger at the same instant shares a key`() {
        val first = SchedulerDedup.key("auto-1", "trigger-1", 1_000L)
        val second = SchedulerDedup.key("auto-1", "trigger-1", 1_000L)

        assertEquals(first, second)
    }

    @Test
    fun `a different instant is a new run`() {
        val last = SchedulerDedup.key("auto-1", "trigger-1", 1_000L)
        val next = SchedulerDedup.key("auto-1", "trigger-1", 2_000L)

        assertTrue(SchedulerDedup.shouldRun(last, next))
    }

    @Test
    fun `an already completed key is skipped`() {
        val key = SchedulerDedup.key("auto-1", "trigger-1", 1_000L)

        assertFalse(SchedulerDedup.shouldRun(key, key))
    }

    @Test(expected = IllegalArgumentException::class)
    fun separatorCannotMakeTwoDistinctSchedulesShareAKey() {
        SchedulerDedup.key("one|two", "three", 1000L)
    }

    @Test
    fun `malformed keys are rejected`() {
        assertNull(SchedulerDedup.parse("auto-1|trigger-1"))
        assertNull(SchedulerDedup.parse("auto-1|trigger-1|not-a-number"))
    }
}
