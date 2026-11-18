package com.uniformitycomp.opencapture

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CameraDiagnosticsTest {
    @Test fun firstErrorSurvivesCleanupRecoveryAndRingEviction() {
        var tick = 0L
        val log = CameraDiagnostics { ++tick }
        log.begin(JSONObject().put("id", "job").put("exposureSeconds", 2.0)
            .put("context", JSONObject().put("secret", "private")), "0")
        log.record("camera_error", JSONObject().put("code", 4).put("detail", CameraDiagnostics.errorName(4)), true)
        repeat(200) { log.record("cleanup") }
        log.record("capture_exception", JSONObject().put("detail", "Cancelled"), true)
        val snapshot = log.snapshot()
        assertEquals(128, snapshot.getJSONArray("events").length())
        assertEquals(4, snapshot.getJSONObject("firstFault").getInt("code"))
        assertFalse(snapshot.toString().contains("private"))
        snapshot.getJSONObject("firstFault").put("code", 99)
        assertEquals(4, log.snapshot().getJSONObject("firstFault").getInt("code"))
        assertTrue(CameraDiagnosticException(IllegalStateException("Cancelled"), log.snapshot()).message!!.contains("ERROR_CAMERA_DEVICE"))
        log.begin(JSONObject().put("id", "next"), "0")
        assertTrue(log.snapshot().isNull("firstFault"))
    }

    @Test fun sequentialModeDisablesBothBatchAndStreamingOverlap() {
        val request = OpenCaptureProtocol.localRequest(6, 100.0, 100.0, null)
        assertTrue(OpenCaptureProtocol.overlap(request, false, false))
        assertFalse(OpenCaptureProtocol.overlap(request, true, false))
        request.put("captureScheduling", "sequential")
        OpenCaptureProtocol.request(request)
        assertFalse(OpenCaptureProtocol.overlap(request, false, false))
        assertFalse(OpenCaptureProtocol.overlap(request, false, true))
        request.put("captureScheduling", "invalid")
        try { OpenCaptureProtocol.request(request); fail("Invalid scheduling accepted") }
        catch (_: IllegalArgumentException) { }
    }
}
