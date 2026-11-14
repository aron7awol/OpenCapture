package com.uniformitycomp.opencapture

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import com.uniformitycomp.capture.RawTiming

class FrameProcessorTest {
    @Test fun oneInspectionMayOverlapCaptureButQueueCannotGrow() {
        FrameProcessor<Int>().use { processor ->
            val started=CountDownLatch(1); val release=CountDownLatch(1)
            processor.submit { started.countDown(); release.await(); 7 }
            assertTrue(started.await(2,TimeUnit.SECONDS))
            assertTrue(processor.hasPending)
            assertFalse(processor.isDone)
            assertThrows(IllegalStateException::class.java) { processor.submit { 8 } }
            release.countDown()
            assertEquals(7,processor.take())
            processor.submit { 9 }
            assertEquals(9,processor.take())
        }
    }

    @Test fun cleanupJoinsCancelledReaderBeforeReturning() {
        val processor=FrameProcessor<Unit>(); val started=CountDownLatch(1); val ended=CountDownLatch(1)
        processor.submit {
            started.countDown()
            try { CountDownLatch(1).await() } finally { ended.countDown() }
        }
        assertTrue(started.await(2,TimeUnit.SECONDS))
        processor.close()
        assertEquals(0L,ended.count)
    }

    @Test fun timingUsesCompleteImageAndOnlyComparableSensorClocks() {
        fun status(arrival:Long=11_300_000_000,sensor:Long=10_020_000_000,source:Int=1,exposure:Long=1_140_000_000) =
            RawTiming.assess(10_000_000_000,arrival,exposure,sensor,source)["status"]
        assertEquals("passed",status())
        assertEquals("invalid",status(sensor=8_900_000_000))
        assertEquals("passed",status(sensor=8_900_000_000,source=0))
        assertEquals("invalid",status(arrival=10_040_000_000,source=0))
        assertEquals("passed",status(arrival=10_008_000_000,exposure=2_000_000))
    }
}
