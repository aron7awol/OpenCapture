package com.uniformitycomp.opencapture

import com.uniformitycomp.capture.RawBufferDiagnostics
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class RawBufferDiagnosticsTest {
    @Test fun requestTimelineKeepsMissingCallbacksAndBoundedRecentHistory() {
        val trace = com.uniformitycomp.capture.CaptureTimeline()
        for (i in 1..64) {
            trace.mark(i,"submitted",i*1_000_000_000L)
            if (i < 64) {
                trace.mark(i,"imageMatched",i*1_000_000_000L+500_000_000)
                trace.mark(i,"closed",i*1_000_000_000L+600_000_000)
            }
        }
        val summary = org.json.JSONObject(trace.summary())
        assertEquals(64,summary.getInt("submitted"))
        assertEquals(63,summary.getInt("buffersClosed"))
        assertEquals(8,summary.getJSONArray("recentRequests").length())
        assertFalse(summary.getJSONArray("recentRequests").getJSONObject(7).has("imageMatchedElapsedNs"))
        assertEquals(31.5,summary.getJSONObject("durations").getJSONObject("requestToImage").getDouble("seconds"),0.0)
        trace.image(12,30,0)
        assertEquals(30L,trace.image(13,40,1)["previousImageTimestampNs"])
    }

    @Test fun shadingSnapshotsDistinguishFlatChangedAndSubsampledMaps() {
        val evidence = com.uniformitycomp.capture.LensShadingDiagnostics()
        val first = org.json.JSONObject(evidence.observe(13,17,FloatArray(13*17*4){1f},mapOf("source" to "preview")))
        assertTrue(first.getJSONObject("first").getBoolean("gridComplete"))
        val changed = FloatArray(13*17*4){ if(it%4==0) 2f else 1f }
        val second = org.json.JSONObject(evidence.observe(13,17,changed,mapOf("source" to "raw")))
        assertEquals(1,second.getInt("changes"))
        assertEquals(1.0,second.getJSONObject("first").getJSONArray("channelRanges").getJSONObject(0).getDouble("max"),0.0)
        assertEquals(2.0,second.getJSONObject("latest").getJSONArray("channelRanges").getJSONObject(0).getDouble("max"),0.0)
        val large = org.json.JSONObject(evidence.observe(64,64,FloatArray(64*64*4){1f},emptyMap()))
        assertFalse(large.getJSONObject("latest").getBoolean("gridComplete"))
        assertEquals(13*17*4,large.getJSONObject("latest").getJSONArray("gains").length())
        assertEquals(63,large.getJSONObject("latest").getJSONArray("sampleColumns").getInt(16))
    }

    @Test(expected=IllegalArgumentException::class)
    fun shadingDiagnosticsRejectNonFiniteMetadata() {
        com.uniformitycomp.capture.LensShadingDiagnostics().observe(1,1,floatArrayOf(1f,1f,Float.NaN,1f),emptyMap())
    }

    @Test fun handlesStrideAndPreservesBufferState() {
        val buffer = ByteBuffer.allocate(4*12).order(ByteOrder.nativeOrder())
        for (y in 0..3) for (x in 0..3) buffer.putShort(y*12+x*2,(256+y*4+x).toShort())
        buffer.position(7)
        val result = RawBufferDiagnostics.sample(buffer,4,4,12,2)
        assertEquals(7,buffer.position())
        assertEquals(48,buffer.limit())
        assertEquals(16,result["sampleCount"])
        assertEquals(256,result["minimum"])
        assertEquals(271,result["maximum"])
        assertEquals(263.5,result["mean"] as Double,1e-10)
        assertEquals(kotlin.math.sqrt(21.25),result["spatialStd"] as Double,1e-10)
        assertEquals(result,RawBufferDiagnostics.sample(buffer.asReadOnlyBuffer(),4,4,12,2))
    }

    @Test fun distinguishesAnEmptyBufferWithoutChangingItsBytes() {
        val buffer = ByteBuffer.allocate(32).order(ByteOrder.nativeOrder())
        for (i in 0..15) buffer.putShort(i*2,256.toShort())
        val before = buffer.array().copyOf()
        val blank = RawBufferDiagnostics.sample(buffer,4,4,8,2)
        assertEquals(0.0,blank["spatialStd"] as Double,0.0)
        assertArrayEquals(before,buffer.array())
        buffer.putShort(0,4095.toShort())
        val changed = RawBufferDiagnostics.sample(buffer,4,4,8,2)
        assertNotEquals(blank["sampleSha256"],changed["sampleSha256"])
        assertTrue((changed["spatialStd"] as Double)>0)
    }

    @Test(expected=IllegalArgumentException::class)
    fun rejectsTruncatedRawBuffer() {
        RawBufferDiagnostics.sample(ByteBuffer.allocate(31),4,4,8,2)
    }
}
