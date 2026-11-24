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
    @Test fun helperAllowsExperimentalModesWithoutDeviceIdentityOrAdvertisedKeys() {
        // Manufacturer, chipset and key advertisement are deliberately not eligibility inputs.
        for (api in listOf(29, 33, 35, 37))
            assertTrue(SamsungCaptureCompatibility.eligible(api, true))
    }

    @Test fun unsupportedPlatformsAndUnavailableHelperRemainExcluded() {
        assertFalse(SamsungCaptureCompatibility.eligible(28, true))
        for (api in listOf(28, 29, 33, 35, 37))
            assertFalse(SamsungCaptureCompatibility.eligible(api, false))
    }
}
