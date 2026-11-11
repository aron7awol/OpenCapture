package com.uniformitycomp.opencapture

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class OpenCaptureProtocolTest {
    private fun request() = OpenCaptureProtocol.localRequest(18, 1000.0, 800.0, .25)
    private fun rejected(action: () -> Unit) {
        try { action(); fail("Expected request rejection") } catch (_: RuntimeException) { }
    }

    @Test fun manualSequencePreservesControlUnitsAndEighteenFrames() {
        val r = request().put("whiteBalance", JSONObject().put("red", 2.0).put("green", 1.0).put("blue", 1.5))
        val settings = OpenCaptureProtocol.request(r)
        assertEquals(1_000_000_000L, settings.exposureNs)
        assertEquals(800, settings.iso)
        assertEquals(.25f, settings.focusFraction)
        assertArrayEquals(floatArrayOf(2f, 1f, 1.5f), settings.whiteBalance, 0f)
    }

    @Test fun malformedAndUnsupportedRequestsFailWithoutFallback() {
        for (r in listOf(request().put("frameCount", 33), request().put("frameCount", 1.5), request().put("frameCount", "2"),
            request().put("format", "jpeg"), request().put("quality", "quality"), request().put("iso", null),
            request().put("id", "../escape"), request().put("focusPosition", 2), request().put("operation", "shell"),
            request().put("outputPath", "file"), request().put("exposureSeconds", Double.NaN),
            request().put("operation", "applyControls"))) rejected { OpenCaptureProtocol.request(r) }
        OpenCaptureProtocol.request(request().put("operation", "applyControls").put("frameCount", 1))
    }

    @Test fun contextIsBoundedOpaqueText() {
        OpenCaptureProtocol.request(request().put("context", JSONObject().put("path", "../never-used-as-a-path")))
        rejected { OpenCaptureProtocol.request(request().put("context", JSONObject().put("label", "x".repeat(257)))) }
        rejected { OpenCaptureProtocol.request(request().put("context", JSONObject().put("label", 123))) }
    }

    @Test fun originsUseSameWindowsPortAndRefuseCredentialLeaks() {
        assertEquals("http://192.168.1.20:8765", OpenCaptureProtocol.origin("192.168.1.20", "").toString())
        assertEquals("http://pc.local:8766", OpenCaptureProtocol.origin("pc.local:8766", "").toString())
        assertEquals("https://example.com", OpenCaptureProtocol.origin("https://example.com", "x".repeat(24)).toString())
        for (uri in listOf("http://example.com", "https://example.com", "http://a:b@192.168.1.20", "192.168.1.20/path", "192.168.1.20?token=x", "192.168.1.20#fragment", "ftp://192.168.1.20")) {
            rejected { OpenCaptureProtocol.origin(uri, "") }
        }
        rejected { OpenCaptureProtocol.origin("192.168.1.20", "secret\r\nheader") }
        assertFalse(OpenCaptureProtocol.privateHost("192.168.1.300"))
        assertFalse(OpenCaptureProtocol.privateHost("172.32.0.1"))
    }

    @Test fun originalsHaveControllerCompatibleHashReceipts() {
        val directory = Files.createTempDirectory("opencapture-test").toFile()
        try {
            val file = directory.resolve("frame-0001.dng").apply { writeText("abc") }
            val receipt = OpenCaptureProtocol.receipt(file)
            assertEquals("image/dng", receipt.getString("mediaType"))
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", receipt.getString("sha256"))
            assertEquals(3L, receipt.get("bytes"))
            rejected { OpenCaptureProtocol.receipt(directory.resolve("manifest.dng").apply { writeText("x") }) }
        } finally { directory.deleteRecursively() }
    }
}
