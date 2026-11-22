// SPDX-License-Identifier: GPL-3.0-only
package com.uniformitycomp.opencapture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class SamsungCaptureCompatibilityTest {
    @Test fun normalFocusOmitsStandardCommandBeforeWritingVendorPosition() {
        for (style in listOf(null, "omit", "retain")) {
            val writes = mutableListOf<String>()
            SamsungCaptureCompatibility.writeFocus(512, style,
                { writes.add("omit-standard") }, { writes.add("vendor-$it") })
            assertEquals(if (style == "retain") listOf("vendor-512") else
                listOf("omit-standard", "vendor-512"), writes)
        }
    }
    @Test fun s20ProbeRequiresHardwareAndHelperNotAnAdvertisedShootingModeKey() {
        // S20 API 33 reports both shooting-mode key lists absent, but loads the helper.
        // Key advertisement intentionally is not an input to experimental eligibility.
        assertTrue(SamsungCaptureCompatibility.eligible(33, "samsung", "exynos990", true))
        assertTrue(SamsungCaptureCompatibility.eligible(29, "SAMSUNG", "S5E990", true))
    }

    @Test fun unsupportedPlatformsAndUnavailableHelperRemainExcluded() {
        assertFalse(SamsungCaptureCompatibility.eligible(28, "samsung", "exynos990", true))
        assertFalse(SamsungCaptureCompatibility.eligible(33, "google", "exynos990", true))
        assertFalse(SamsungCaptureCompatibility.eligible(33, "samsung", "qcom", true))
        assertFalse(SamsungCaptureCompatibility.eligible(33, "samsung", "", true))
        assertFalse(SamsungCaptureCompatibility.eligible(33, "samsung", "exynos990", false))
    }
}
