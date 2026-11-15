// SPDX-License-Identifier: GPL-3.0-only
package com.uniformitycomp.opencapture

import org.junit.Assert.*
import org.junit.Test

class CameraRecoveryTest {
    @Test fun networkRebindDoesNotWaitForBurstPreview() {
        assertTrue(CameraRecovery.canBindListener(previewReady = false, connected = true))
        assertTrue(CameraRecovery.canBindListener(previewReady = true, connected = false))
        assertFalse(CameraRecovery.canBindListener(previewReady = false, connected = false))
    }

    @Test fun resumeResetsBudgetWithoutInvalidatingHealthyCamera() {
        val state = CameraRecovery()
        state.takeRetry(state.beginRecovery(state.beginOpen()!!)!!)
        val camera = state.beginOpen()!!
        state.resetAttempts()
        assertTrue(state.current(camera))
        assertTrue(state.opening)
        assertEquals(0, state.attempts)
    }
    @Test fun staleOpenFailureCannotRecoverReplacement() {
        val state = CameraRecovery()
        val old = state.beginOpen()!!
        state.invalidate(resetAttempts = true)
        val replacement = state.beginOpen()!!
        assertNull(state.beginRecovery(old))
        assertTrue(state.current(replacement))
        assertTrue(state.opening)
    }

    @Test fun queuedDisconnectAndDuplicateFailuresAreFenced() {
        val state = CameraRecovery()
        val opened = state.beginOpen()!!
        val retry = state.beginRecovery(opened)!!
        assertNull(state.beginRecovery(opened))
        assertNull(state.beginOpen())
        assertTrue(state.takeRetry(retry))
        assertFalse(state.takeRetry(retry))
        val replacement = state.beginOpen()!!
        assertNull(state.beginRecovery(opened))
        assertTrue(state.current(replacement))
    }

    @Test fun stopResumeAndLensSelectionInvalidateDelayedRetry() {
        val state = CameraRecovery()
        val delayed = state.beginRecovery(state.beginOpen()!!)!!
        state.invalidate(resetAttempts = true)
        val replacement = state.beginOpen()!!
        assertFalse(state.takeRetry(delayed))
        state.abandonRetry(delayed)
        assertTrue(state.current(replacement))
        assertEquals(0, state.attempts)
    }

    @Test fun recoveryIsBoundedUntilExplicitReset() {
        val state = CameraRecovery()
        repeat(3) {
            val retry = state.beginRecovery(state.beginOpen()!!)!!
            assertTrue(state.takeRetry(retry))
            assertEquals(it + 1, state.attempts)
        }
        val exhausted = state.beginRecovery(state.beginOpen()!!)!!
        assertFalse(state.pending)
        assertFalse(state.takeRetry(exhausted))
        state.invalidate(resetAttempts = true)
        assertTrue(state.takeRetry(state.beginRecovery(state.beginOpen()!!)!!))
    }

    @Test fun staleCleanupTimeoutDoesNotCancelNewRecovery() {
        val state = CameraRecovery()
        val old = state.beginRecovery(state.beginOpen()!!)!!
        state.invalidate()
        val current = state.beginRecovery(state.beginOpen()!!)!!
        state.abandonRetry(old)
        assertTrue(state.pending)
        assertTrue(state.takeRetry(current))
        state.abandonRetry(current)
        assertFalse(state.pending)
    }
}
