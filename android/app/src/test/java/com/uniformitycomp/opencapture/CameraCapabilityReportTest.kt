// SPDX-License-Identifier: GPL-3.0-only
package com.uniformitycomp.opencapture

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraCapabilityReportTest {
    @Test fun reportsIndependentRawBlockers() {
        assertEquals(listOf("manualSensor", "manualPostProcessing"),
            CameraCapabilityReport.missingForRaw(true, true, false, false, true))
        assertEquals(listOf("bayerColorFilter", "rawCapability", "supportedRawSize"),
            CameraCapabilityReport.missingForRaw(false, false, true, true, false))
        assertEquals(emptyList<String>(), CameraCapabilityReport.missingForRaw(true, true, true, true, true))
    }
}
