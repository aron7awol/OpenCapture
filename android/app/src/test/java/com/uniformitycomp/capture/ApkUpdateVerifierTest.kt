package com.uniformitycomp.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkUpdateVerifierTest {
    @Test
    fun exactSignerSetIsAccepted() {
        assertTrue(
            ApkUpdateVerifier.hasSignerContinuity(
                installedCurrent = setOf("aa"),
                installedHistory = setOf("aa"),
                candidateCurrent = setOf("AA"),
                candidateHistory = setOf("aa")
            )
        )
    }

    @Test
    fun androidSigningLineageRotationIsAccepted() {
        assertTrue(
            ApkUpdateVerifier.hasSignerContinuity(
                installedCurrent = setOf("old"),
                installedHistory = setOf("old"),
                candidateCurrent = setOf("new"),
                candidateHistory = setOf("old", "new")
            )
        )
    }

    @Test
    fun unrelatedOrIncompleteSignerDataIsRejected() {
        assertFalse(
            ApkUpdateVerifier.hasSignerContinuity(
                installedCurrent = setOf("installed"),
                installedHistory = setOf("installed"),
                candidateCurrent = setOf("attacker"),
                candidateHistory = setOf("attacker")
            )
        )
        assertFalse(
            ApkUpdateVerifier.hasSignerContinuity(
                installedCurrent = emptySet(),
                installedHistory = emptySet(),
                candidateCurrent = setOf("candidate"),
                candidateHistory = setOf("candidate")
            )
        )
    }
}
