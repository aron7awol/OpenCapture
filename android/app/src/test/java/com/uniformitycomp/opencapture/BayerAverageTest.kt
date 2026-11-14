package com.uniformitycomp.opencapture

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class BayerAverageTest {
    @Test fun pullAndLegacyPushShareTheSameTransferSubset() {
        fun receipt(name: String)=org.json.JSONObject().put("name",name)
        val frames=org.json.JSONArray((1..3).map { i -> org.json.JSONObject()
            .put("file",receipt("frame-%04d.dng".format(i))).put("metadataFile",receipt("frame-%04d.json".format(i))) })
        val a=org.json.JSONObject().put("retainedOriginalIndices",org.json.JSONArray(listOf(1,2)))
            .put("files",org.json.JSONObject().put("moments",receipt("average.f32")).put("clipping",receipt("average-clips.u8")))
        val manifest=org.json.JSONObject().put("frames",frames).put("aggregation",a)
        val names=OpenCaptureProtocol.transferReceipts(manifest).map { it.getString("name") }.toSet()
        assertEquals(setOf("frame-0001.dng","frame-0002.dng","frame-0001.json","frame-0002.json","frame-0003.json","average.f32","average-clips.u8"),names)
        a.put("retainedOriginalIndices",org.json.JSONArray(listOf(1,2,3))).put("files",org.json.JSONObject())
        assertEquals(6,OpenCaptureProtocol.transferReceipts(manifest).size)
    }
    private val layout = BayerAverage.Layout(8,8,listOf(0,0,8,8),listOf(256.0,256.5,257.0,257.5),4095.0,listOf(0,1,1,2))
    private fun evidence(rms: Double=.1, std: Double=.02, integrity: String="consistent_sampled_path") =
        BayerAverage.Evidence(File("unused.dng"),layout,rms,std,.09,0.0,integrity)

    @Test fun replacementsReachUsableTargetNotAttemptCount() {
        val s = BayerAverage.Selection(3,"illuminated")
        for (frame in listOf(evidence(),evidence(.0001,0.0),evidence(),evidence())) {
            assertTrue(s.needsMore()); s.attempts.add(frame)
        }
        assertFalse(s.needsMore())
        assertEquals(listOf(0,2,3),s.accepted())
        assertEquals("empty_signal",s.reasons()[1])
    }
    @Test fun retriesStopAtHardBudget() {
        for (n in listOf(1,2,10,32)) {
            val s=BayerAverage.Selection(n,"illuminated")
            repeat(s.maxAttempts) { assertTrue(s.needsMore()); s.attempts.add(evidence(0.0,0.0)) }
            assertFalse(s.needsMore()); assertTrue(s.accepted().isEmpty())
            assertEquals(n*2,s.maxAttempts)
        }
    }
    @Test fun darkFramesAreValidButChangedBuffersAreNot() {
        val s=BayerAverage.Selection(2,"dark")
        s.attempts.add(evidence(0.0,0.0))
        s.attempts.add(evidence(0.0,0.0,"sample_mismatch_observed"))
        assertEquals(listOf(0),s.accepted()); assertTrue(s.needsMore())
        s.attempts.add(evidence(0.0,0.0)); assertFalse(s.needsMore())
    }
    @Test fun invalidTimingCannotCountAsADarkOrIlluminatedExposure() {
        for (policy in listOf("dark","relative","illuminated")) {
            val selection=BayerAverage.Selection(2,policy)
            selection.attempts.add(evidence().copy(timingInvalid=true))
            selection.attempts.add(evidence())
            assertEquals(listOf(1),selection.accepted())
            assertEquals("capture_timing_invalid",selection.reasons()[0])
            assertTrue(selection.needsMore())
            selection.attempts.add(evidence())
            assertFalse(selection.needsMore())
        }
    }
    @Test fun relativePolicyReclassifiesInitialDropoutButNotAllDarkBurst() {
        val s=BayerAverage.Selection(3,"relative")
        s.attempts.add(evidence(.0001,0.0)); assertEquals(listOf(0),s.accepted())
        s.attempts.add(evidence()); assertEquals(listOf(1),s.accepted())
        assertEquals("empty_signal",s.reasons()[0])
        val dark=BayerAverage.Selection(2,"relative")
        repeat(2) { dark.attempts.add(evidence(.0001,0.0)) }
        assertEquals(2,dark.accepted().size)
    }
    @Test fun clippingOrDimmerSignalAloneIsNotAnEmptyFrame() {
        val s=BayerAverage.Selection(4,"illuminated")
        s.attempts.addAll(listOf(evidence(.0001,.001),evidence(.002,0.0),
            evidence(0.0,0.0).copy(clipped=.001),evidence()))
        assertEquals(4,s.accepted().size)
    }
    @Test fun fractionalSiteMeansAndLumaCovarianceSurviveStripAveraging() {
        val directory=Files.createTempDirectory("bayer-average-").toFile()
        try {
            val height=70
            val files=(0..1).map { n -> File(directory,"frame-$n.dng").also { file ->
                file.writeBytes(DngSampleDiagnosticsTest().fixture(height=height,rows=3,
                    order=if(n==0)ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN) { x,y -> 512+x+y*8+n }.bytes)
            } }
            val frames=files.map { BayerAverage.inspect(it,8,height,"consistent_sampled_path") {} }
            BayerAverage.write(frames,directory) {}
            val data=ByteBuffer.wrap(File(directory,"average.f32").readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(4*35*24,data.limit())
            for (y in 0 until height/2) for (x in 0 until 4) {
                val luma=FloatArray(2)
                for (site in 0..3) {
                    val samples=FloatArray(2) { n ->
                        ((512+2*x+site%2+(2*y+site/2)*8+n).toFloat()-layout.black[site].toFloat())/
                            (layout.white.toFloat()-layout.black[site].toFloat())
                    }
                    for(n in 0..1) luma[n]+=samples[n]*.25f
                    assertEquals((samples[0].toDouble()+samples[1])/2,data.float.toDouble(),1e-7)
                }
                assertEquals((luma[0].toDouble()+luma[1])/2,data.float.toDouble(),1e-7)
                val delta=luma[1].toDouble()-luma[0]
                assertEquals(delta*delta/2,data.float.toDouble(),1e-13)
            }
            assertTrue(File(directory,"average-clips.u8").readBytes().all { it==0.toByte() })
        } finally { directory.deleteRecursively() }
    }
    @Test fun nativeClippingIsRetainedEvenWhenMeanIsUnclipped() {
        val directory=Files.createTempDirectory("bayer-clip-").toFile()
        try {
            val frames=(0..1).map { n ->
                val file=File(directory,"frame-$n.dng")
                file.writeBytes(DngSampleDiagnosticsTest().fixture(height=8) { x,y -> if(n==0&&x==0&&y==0)4095 else 512 }.bytes)
                BayerAverage.inspect(file,8,8,"consistent_sampled_path") {}
            }
            BayerAverage.write(frames,directory) {}
            val clips=File(directory,"average-clips.u8").readBytes()
            assertEquals(1,clips[0].toInt()); assertEquals(1,clips.sumOf { it.toInt() })
            assertEquals(1.0/16,frames[0].clipped,0.0)
        } finally { directory.deleteRecursively() }
    }
    @Test fun rejectsChangedNormalizationAndCancelsBeforeAccumulation() {
        val directory=Files.createTempDirectory("bayer-cancel-").toFile()
        try {
            assertThrows(IllegalArgumentException::class.java) {
                BayerAverage.write(listOf(evidence(),evidence().copy(layout=layout.copy(white=1023.0))),directory) {}
            }
            assertThrows(InterruptedException::class.java) {
                BayerAverage.write(listOf(evidence()),directory) { throw InterruptedException() }
            }
        } finally { directory.deleteRecursively() }
    }
    @Test fun averagingContractRejectsUnknownPolicyAndControlOperations() {
        val request=OpenCaptureProtocol.localRequest(4,10.0,100.0,.5)
        val a=org.json.JSONObject().put("mode",BayerAverage.MODE).put("emptyFramePolicy","dark").put("keepOriginals",false)
        request.put("averaging",a)
        OpenCaptureProtocol.request(request)
        a.put("emptyFramePolicy","anything")
        assertThrows(IllegalArgumentException::class.java) { OpenCaptureProtocol.request(request) }
        a.put("emptyFramePolicy","dark");request.put("operation","applyControls").put("frameCount",1)
        assertThrows(IllegalArgumentException::class.java) { OpenCaptureProtocol.request(request) }
    }
}
