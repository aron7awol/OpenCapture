// SPDX-License-Identifier: GPL-3.0-only
package com.uniformitycomp.opencapture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SamsungCaptureCompatibilityTest {
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
