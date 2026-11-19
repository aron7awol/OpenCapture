package com.uniformitycomp.opencapture

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CameraDiagnosticsTest {
    @Test fun representativeEvidenceSurvivesRingEvictionAndResetsWithJob() {
        val log=CameraDiagnostics()
        log.begin(JSONObject().put("id","one"),"0")
        val shading=com.uniformitycomp.capture.LensShadingDiagnostics()
        log.record("lens_shading_evidence",JSONObject(shading.observe(13,17,FloatArray(884){1f},emptyMap())))
        log.record("raw_timeline_summary",JSONObject().put("submitted",3))
        log.record("camera_error",JSONObject().put("code",4),true)
        repeat(150) { log.record("progress",JSONObject().put("metadata","x".repeat(2000))) }
        val saved=log.snapshot()
        assertTrue(saved.toString().toByteArray().size<=64*1024)
        assertEquals(884,saved.getJSONObject("evidence").getJSONObject("lens_shading_evidence")
            .getJSONObject("first").getJSONArray("gains").length())
        assertEquals(3,saved.getJSONObject("evidence").getJSONObject("raw_timeline_summary").getInt("submitted"))
        assertEquals(4,saved.getJSONObject("firstFault").getInt("code"))
        log.begin(JSONObject().put("id","two"),"0")
        assertFalse(log.snapshot().getJSONObject("evidence").has("lens_shading_evidence"))
    }

    @Test fun errorChainIncludesUnderlyingFailureButIsBounded() {
        var error: Throwable=IllegalStateException("driver failure")
        repeat(10) { error=IllegalStateException("wrapper",error) }
        assertEquals(4,CameraDiagnostics.errorChain(error).length())
        val chain=CameraDiagnostics.errorChain(IllegalStateException("capture failed",IllegalArgumentException("invalid exposure")))
        assertEquals("invalid exposure",chain.getJSONObject(1).getString("message"))
    }
    @Test fun richFailureSurvivesPersistenceAndBoundedHistoryWithoutAnOriginal() {
        var persisted = JSONObject()
        val log = CameraDiagnostics(persist = { persisted = JSONObject(it.toString()) })
        log.begin(JSONObject().put("id", "failed-before-dng"), "0")
        val checks = com.uniformitycomp.capture.ManualStateReading(
            40_000_000,6368,0f,listOf(1f,1f,1f,1f),null,null,listOf(0,0,0,0,0),
            null,null,null,null,null).controlChecks(40_000_000,6945,0f,listOf(1f,1f,1f,1f),0f)
        log.record("manual_state_mismatch", JSONObject(mapOf("checks" to checks,
            "detail" to "still_controls_not_honored", "fatal" to true)), true)
        repeat(128) { log.record("later_snapshot", JSONObject().put("metadata", "x".repeat(2048))) }
        assertTrue(persisted.toString().toByteArray().size <= 64 * 1024)
        val fault = persisted.getJSONObject("firstFault")
        assertEquals("manual_state_mismatch",fault.getString("event"))
        assertEquals(6945,fault.getJSONArray("checks").getJSONObject(1).getInt("expected"))
        assertEquals(6368,fault.getJSONArray("checks").getJSONObject(1).getInt("actual"))
        assertTrue(CameraDiagnosticException(IllegalStateException("Failed"),persisted)
            .message!!.contains("still_controls_not_honored"))
    }

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
