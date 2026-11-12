package com.uniformitycomp.opencapture

import com.uniformitycomp.capture.RawBufferDiagnostics
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class RawBufferDiagnosticsTest {
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
