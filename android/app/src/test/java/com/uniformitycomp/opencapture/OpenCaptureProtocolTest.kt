package com.uniformitycomp.opencapture

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class OpenCaptureProtocolTest {
    @Test fun samsungRequestOptionsAreIndependentAndVendorOnly() {
        for (frame in listOf(false,true)) for (shading in listOf(false,true)) {
            for (mode in listOf("samsung-vendor-preview","samsung-vendor-still")) {
                val r = request().put("captureScheduling",mode).put("omitFrameDurationOverride",frame)
                    .put("useDriverShadingDefaults",shading)
                OpenCaptureProtocol.request(r)
                OpenCaptureProtocol.request(r.put("operation","applyControls").put("frameCount",1))
            }
        }
        for (option in OpenCaptureProtocol.samsungCaptureOptions) {
            rejected { OpenCaptureProtocol.request(request().put(option,true)) }
            rejected { OpenCaptureProtocol.request(request().put("captureScheduling","raw-jpeg-still").put(option,true)) }
            rejected { OpenCaptureProtocol.request(request().put("captureScheduling","samsung-vendor-still").put(option,1)) }
        }
    }
    @Test fun focusProbeIsExplicitBoundedOriginalCaptureOnly() {
        fun probe() = request().put("frameCount", 2).put("captureScheduling", "samsung-vendor-preview")
            .put("whiteBalance", JSONObject().put("red", 2).put("green", 1).put("blue", 1.5))
            .put("focusProbe", JSONObject().put("position", .5).put("standardFocus", "omit"))
        OpenCaptureProtocol.request(probe())
        OpenCaptureProtocol.request(probe().put("captureScheduling", "samsung-vendor-still"))
        for (mode in listOf("sequential", "raw-jpeg-still")) rejected { OpenCaptureProtocol.request(probe().put("captureScheduling", mode)) }
        rejected { OpenCaptureProtocol.request(probe().put("operation", "applyControls").put("frameCount", 1)) }
        rejected { OpenCaptureProtocol.request(probe().put("exposureSeconds", 3)) }
        rejected { OpenCaptureProtocol.request(probe().put("frameCount", 3)) }
        rejected { OpenCaptureProtocol.request(probe().put("settleMilliseconds", 0)) }
        rejected { OpenCaptureProtocol.request(probe().put("streaming", JSONObject().put("mode", "originals-v1"))) }
        for (p in listOf(-.1,1.1)) rejected { OpenCaptureProtocol.request(probe().put("focusProbe", JSONObject().put("position",p).put("standardFocus","omit"))) }
    }
    @Test fun samsungExperimentsDoNotOverlapOrSilentlyAverage() {
        for (mode in OpenCaptureProtocol.experimentalScheduling) {
            val r = request().put("captureScheduling", mode)
            OpenCaptureProtocol.request(r)
            assertFalse(OpenCaptureProtocol.overlap(r, false, true))
            assertFalse(OpenCaptureProtocol.queueAhead(r))
            assertFalse(OpenCaptureProtocol.shortPreviewExposure(r))
            assertEquals(mode == "samsung-vendor-preview", OpenCaptureProtocol.keepPreviewRunning(r))
            assertEquals(mode != "raw-jpeg-still", OpenCaptureProtocol.vendorCamera(r))
            assertEquals(mode != "samsung-vendor-preview", OpenCaptureProtocol.separateStill(r))
            OpenCaptureProtocol.request(r.put("operation", "applyControls").put("frameCount", 1))
            r.put("operation", "capture").put("averaging", JSONObject().put("mode", BayerAverage.MODE)
                .put("emptyFramePolicy", "illuminated").put("keepOriginals", false))
            rejected { OpenCaptureProtocol.request(r) }
        }
    }
    @Test fun previewCompatibilityIsExplicitAndAlwaysSequential() {
        assertFalse(OpenCaptureProtocol.keepPreviewRunning(request()))
        assertTrue(OpenCaptureProtocol.overlap(request(), false, false))
        for (mode in listOf("sequential", "sequential-preview")) {
            val r = request().put("captureScheduling", mode)
            OpenCaptureProtocol.request(r)
            assertEquals(mode == "sequential-preview", OpenCaptureProtocol.keepPreviewRunning(r))
            for (average in listOf(false, true)) for (stream in listOf(false, true))
                assertFalse(OpenCaptureProtocol.overlap(r, average, stream))
            OpenCaptureProtocol.request(r.put("operation", "applyControls").put("frameCount", 1))
        }
        for (mode in listOf(true, 1, "parallel", "preview", JSONObject.NULL))
            rejected { OpenCaptureProtocol.request(request().put("captureScheduling", mode)) }
    }
    @Test fun queuedPreviewKeepsThePreviewAndNeverUsesTheTwoFrameOverlap() {
        val r = request().put("captureScheduling", "queued-preview")
        OpenCaptureProtocol.request(r)
        assertTrue(OpenCaptureProtocol.queueAhead(r))
        assertTrue(OpenCaptureProtocol.keepPreviewRunning(r))
        assertFalse(OpenCaptureProtocol.shortPreviewExposure(r))
        for (average in listOf(false, true)) for (stream in listOf(false, true))
            assertFalse(OpenCaptureProtocol.overlap(r, average, stream))
        for (mode in listOf("sequential", "sequential-preview", "sequential-short-preview"))
            assertFalse(OpenCaptureProtocol.queueAhead(request().put("captureScheduling", mode)))
        assertFalse(OpenCaptureProtocol.queueAhead(request()))
    }
    @Test fun experimentalExposureDoesNotRewriteReportedLimits() {
        val minimum = 100_000L
        val samsungMaximum = 100_000_000L
        assertTrue(OpenCaptureProtocol.acceptsExposure(null, minimum, samsungMaximum))
        assertTrue(OpenCaptureProtocol.acceptsExposure(5_000_000_000L, minimum, samsungMaximum))
        assertTrue(OpenCaptureProtocol.acceptsExposure(30_000_000_000L, minimum, samsungMaximum))
        assertFalse(OpenCaptureProtocol.acceptsExposure(30_000_000_001L, minimum, samsungMaximum))
        assertFalse(OpenCaptureProtocol.acceptsExposure(minimum - 1, minimum, samsungMaximum))
        assertTrue(OpenCaptureProtocol.acceptsExposure(16_000_000_000L, minimum, 16_000_000_000L))
    }
    @Test fun thirtySecondExposureSurvivesProtocolParsing() {
        assertEquals(30_000_000_000L, OpenCaptureProtocol.request(request().put("exposureSeconds", 30.0)).exposureNs)
        rejected { OpenCaptureProtocol.request(request().put("exposureSeconds", 30.000001)) }
    }
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
