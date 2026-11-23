package com.niki914.zafiro.platform

import com.niki914.zafiro.platform.conflict.ApprovalOutcome
import com.niki914.zafiro.platform.conflict.ApprovalRace
import com.niki914.zafiro.platform.conflict.ApprovalVote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalRaceTest {

    private var now = 1_000L
    private val race = ApprovalRace(clock = { now })

    @Test
    fun `the first decisive vote settles the request`() {
        race.open("req-1", 60_000L)

        val result = race.settle("req-1", "notification", ApprovalVote.ALLOW)

        assertEquals(ApprovalOutcome.Settled("req-1", ApprovalVote.ALLOW, "notification"), result)
    }

    @Test
    fun `a later vote is told the request is already settled`() {
        race.open("req-1", 60_000L)
        race.settle("req-1", "notification", ApprovalVote.DENY)

        val result = race.settle("req-1", "overlay", ApprovalVote.ALLOW)

        assertEquals(ApprovalOutcome.AlreadySettled("req-1", ApprovalVote.DENY, "notification"), result)
    }

    @Test
    fun `abstaining does not settle the request`() {
        race.open("req-1", 60_000L)

        val result = race.settle("req-1", "main", ApprovalVote.ABSTAIN)

        assertEquals(ApprovalOutcome.Pending, result)
    }

    @Test
    fun `expired requests do not accept new votes`() {
        race.open("req-1", 1_000L)
        now += 5_000L

        val result = race.settle("req-1", "notification", ApprovalVote.ALLOW)

        assertEquals(ApprovalOutcome.Expired, result)
    }

    @Test
    fun settledRequestsCanBeRetiredAfterTheirExpiry() {
        race.open("settled", 1000L)
        race.settle("settled", "notification", ApprovalVote.ALLOW)
        now += 2000L
        assertEquals(1, race.sweepExpired())
        assertEquals(ApprovalOutcome.UnknownRequest, race.outcome("settled"))
    }

    @Test
    fun `unknown requests are reported as unknown`() {
        assertTrue(race.settle("missing", "notification", ApprovalVote.ALLOW) is ApprovalOutcome.UnknownRequest)
    }
}
