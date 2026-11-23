package com.niki914.zafiro.platform

import com.niki914.zafiro.platform.conflict.CompatResult
import com.niki914.zafiro.platform.conflict.ExtensionCompat
import com.niki914.zafiro.platform.conflict.ExtensionRequirement
import com.niki914.zafiro.platform.conflict.SemVer
import com.niki914.zafiro.platform.conflict.VersionRange
import com.niki914.zafiro.platform.contract.CapabilityFact
import com.niki914.zafiro.platform.contract.CapabilityId
import com.niki914.zafiro.platform.contract.CapabilitySnapshot
import com.niki914.zafiro.platform.contract.CapabilityStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionCompatTest {

    private fun snapshot(vararg facts: CapabilityFact) = CapabilitySnapshot(
        revision = 1L,
        facts = facts.associateBy { it.id },
    )

    private fun fact(id: CapabilityId, status: CapabilityStatus) = CapabilityFact(
        id = id,
        status = status,
        source = "test",
        observedAtMillis = 0L,
    )

    @Test
    fun `invalid identifiers are rejected and build metadata does not affect precedence`() {
        listOf("01.2.3", "1.2.3-01", "1.2.3-rc..1", "1.2.3+", "1.2.3-.").forEach {
            assertNull(it, SemVer.parse(it))
        }
        assertEquals(SemVer.parse("1.2.3"), SemVer.parse("1.2.3+build.12"))
        assertTrue(SemVer.parse("1.2.3-999999999999999999999")!! < SemVer.parse("1.2.3-alpha")!!)
    }

    @Test
    fun `semver parses and orders pre releases before the release`() {
        val release = SemVer.parse("1.2.3")
        val preRelease = SemVer.parse("1.2.3-rc.1")

        assertEquals(SemVer(1, 2, 3), release)
        assertTrue(preRelease!! < release!!)
        assertNull(SemVer.parse("1.2"))
    }

    @Test
    fun `host inside the range and capabilities present is compatible`() {
        val requirement = ExtensionRequirement(
            hostApiRange = VersionRange(
                minInclusive = SemVer(1, 0, 0),
                maxExclusive = SemVer(2, 0, 0),
            ),
            requiredCapabilities = setOf(CapabilityId.TERMINAL_BIONIC),
        )

        val result = ExtensionCompat.check(
            hostApiVersion = "1.4.0",
            capabilities = snapshot(fact(CapabilityId.TERMINAL_BIONIC, CapabilityStatus.AVAILABLE)),
            requirement = requirement,
        )

        assertEquals(CompatResult.Compatible, result)
    }

    @Test
    fun `missing capability blocks the extension`() {
        val requirement = ExtensionRequirement(
            hostApiRange = null,
            requiredCapabilities = setOf(CapabilityId.HARNESS_ENGINE),
        )

        val result = ExtensionCompat.check(
            hostApiVersion = "1.0.0",
            capabilities = snapshot(fact(CapabilityId.HARNESS_ENGINE, CapabilityStatus.UNKNOWN)),
            requirement = requirement,
        )

        assertTrue(result is CompatResult.Incompatible)
        assertTrue((result as CompatResult.Incompatible).reasons.contains("capability-unavailable:harness_engine"))
    }

    @Test
    fun `invalid host version is rejected instead of assumed`() {
        val requirement = ExtensionRequirement(
            hostApiRange = VersionRange(SemVer(1, 0, 0), SemVer(2, 0, 0)),
        )

        val result = ExtensionCompat.check(
            hostApiVersion = "not-a-version",
            capabilities = snapshot(),
            requirement = requirement,
        )

        assertTrue((result as CompatResult.Incompatible).reasons.contains("invalid-host-version"))
    }
}
