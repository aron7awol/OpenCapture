// SPDX-License-Identifier: GPL-3.0-only
package com.uniformitycomp.opencapture

import org.junit.Assert.*
import org.junit.Test

class ForegroundAwakeTest {
    @Test fun cameraServingKeepsVisibleActivityAwake() {
        assertTrue(shouldKeepScreenAwake(true, true, false))
        assertTrue(shouldKeepScreenAwake(true, true, true))
    }

    @Test fun stoppingCameraForUpdateDoesNotAllowSleepDuringTransfer() {
        assertTrue(shouldKeepScreenAwake(true, false, true))
        assertFalse(shouldKeepScreenAwake(true, false, false))
    }

    @Test fun backgroundingAlwaysReleasesScreenRequest() {
        for (enabled in listOf(false, true)) for (updating in listOf(false, true)) {
            assertFalse(shouldKeepScreenAwake(false, enabled, updating))
        }
    }
}
