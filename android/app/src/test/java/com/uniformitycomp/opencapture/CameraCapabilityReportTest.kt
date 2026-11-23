// SPDX-License-Identifier: GPL-3.0-only
package com.uniformitycomp.opencapture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

class CameraCapabilityReportTest {
    @Test fun reportsIndependentRawBlockers() {
        assertEquals(listOf("manualSensor", "manualPostProcessing"),
            CameraCapabilityReport.missingForRaw(true, true, false, false, true))
        assertEquals(listOf("bayerColorFilter", "rawCapability", "supportedRawSize"),
            CameraCapabilityReport.missingForRaw(false, false, true, true, false))
        assertEquals(emptyList<String>(), CameraCapabilityReport.missingForRaw(true, true, true, true, true))
    }

    @Test fun olderAndroidDoesNotQueryNewKeys() {
        val report = JSONObject()
        val errors = JSONObject()
        CameraCapabilityReport.putDiagnostic(report, errors, "maximumResolutionRawSizes", 30, 31) {
            throw AssertionError("Must not access API 31 characteristics")
        }
        assertTrue(report.isNull("maximumResolutionRawSizes"))
        assertFalse(errors.keys().hasNext())
    }

    @Test fun optionalReadFailureDoesNotHideOtherFields() {
        val report = JSONObject()
        val errors = JSONObject()
        CameraCapabilityReport.putDiagnostic(report, errors, "binningFactor", 31, 31) {
            throw IllegalArgumentException("vendor metadata failure")
        }
        CameraCapabilityReport.putDiagnostic(report, errors, "maximumResolutionRawSizes", 31, 31) {
            JSONArray(listOf("16320x12240", "8160x6120"))
        }
        assertTrue(report.isNull("binningFactor"))
        assertEquals("IllegalArgumentException", errors.getString("binningFactor"))
        assertEquals(JSONArray(listOf("16320x12240", "8160x6120")).toString(),
            report.getJSONArray("maximumResolutionRawSizes").toString())
        assertEquals(2, report.getJSONArray("maximumResolutionRawSizes").length())
    }

    @Test fun missingEmptyAndFalseRemainDistinct() {
        val report = JSONObject()
        val errors = JSONObject()
        CameraCapabilityReport.putDiagnostic(report, errors, "missing", 36, 31) { null }
        CameraCapabilityReport.putDiagnostic(report, errors, "empty", 36, 31) { JSONArray() }
        CameraCapabilityReport.putDiagnostic(report, errors, "unsupported", 36, 31) { false }
        assertTrue(report.isNull("missing"))
        assertEquals(0, report.getJSONArray("empty").length())
        assertFalse(report.getBoolean("unsupported"))
        assertFalse(errors.keys().hasNext())
    }
}
