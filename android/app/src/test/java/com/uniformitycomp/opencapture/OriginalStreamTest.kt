package com.uniformitycomp.opencapture

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class OriginalStreamTest {
    private fun frame(root: File, index: Int): JSONObject {
        val image=File(root,"frame-%04d.dng".format(index)).apply { writeText("original-$index") }
        val meta=File(root,"frame-%04d.json".format(index)).apply { writeText("{}") }
        return JSONObject().put("index",index).put("file",OpenCaptureProtocol.receipt(image))
            .put("metadataFile",OpenCaptureProtocol.receipt(meta))
    }
    private fun verdict(frame: JSONObject, accepted: Boolean) = JSONObject().put("index",frame.getInt("index"))
        .put("sha256",frame.getJSONObject("file").getString("sha256")).put("accepted",accepted)
        .put("reason",if(accepted) "usable" else "empty_signal")

    @Test fun freezeKeepsExactJudgedPrefixAndVerdictRetryCannotChangeIt() {
        val root=Files.createTempDirectory("original-stream").toFile()
        try {
            val request=OpenCaptureProtocol.localRequest(2,null,null,null)
            val stream=OriginalStream(request,root,AtomicBoolean())
            stream.begin(JSONObject(),"start","fixture")
            val a=frame(root,1);val b=frame(root,2)
            assertTrue(stream.next());stream.publish(a);assertTrue(stream.next());stream.publish(b)
            val entered=CountDownLatch(1);val resumed=CountDownLatch(1)
            val worker=Thread { entered.countDown();assertTrue(stream.next());resumed.countDown() }.apply { start() }
            assertTrue(entered.await(1,TimeUnit.SECONDS));assertFalse(resumed.await(50,TimeUnit.MILLISECONDS))
            stream.verdict(verdict(a,true));assertFalse(resumed.await(50,TimeUnit.MILLISECONDS))
            stream.verdict(verdict(b,false));assertTrue(resumed.await(1,TimeUnit.SECONDS));worker.join()
            val c=frame(root,3);stream.publish(c);assertFalse(stream.tryNext())
            stream.verdict(verdict(c,true));val frozen=stream.finish().toString()
            stream.verdict(verdict(c,true));assertEquals(frozen,stream.finish().toString())
            assertFalse(stream.next());assertEquals(3,stream.finish().getJSONArray("frames").length())
            val accepted = stream.finish().getJSONObject("streaming").getJSONArray("acceptedIndices")
            assertEquals(listOf(1,3), (0 until accepted.length()).map { accepted.getInt(it) })
            assertThrows(IllegalArgumentException::class.java) { stream.verdict(verdict(c,false)) }
            assertThrows(IllegalArgumentException::class.java) { stream.verdict(verdict(frame(root,4),true)) }
        } finally { root.deleteRecursively() }
    }

    @Test fun boundedFailureAndDarkVerdictsAreControllerOwned() {
        val root=Files.createTempDirectory("original-stream").toFile()
        try {
            val stream=OriginalStream(OpenCaptureProtocol.localRequest(1,null,null,null),root,AtomicBoolean())
            stream.begin(JSONObject(),"start","fixture")
            for(i in 1..2) { assertTrue(stream.next());val f=frame(root,i);stream.publish(f);stream.verdict(verdict(f,false)) }
            assertEquals("insufficient",stream.finish().getJSONObject("streaming").getString("status"))
            assertFalse(stream.next())
        } finally { root.deleteRecursively() }
    }

    @Test fun exactTargetsAndReplacementBudget() {
        for (target in listOf(1,6,18)) for (accept in listOf(true,false)) {
            val root=Files.createTempDirectory("stream-count").toFile()
            try {
                val stream=OriginalStream(OpenCaptureProtocol.localRequest(target,null,null,null),root,AtomicBoolean())
                stream.begin(JSONObject(),"start","fixture")
                val attempts=if(accept) target else 2*target
                for(i in 1..attempts) {
                    assertTrue(stream.next());val record=frame(root,i);stream.publish(record)
                    stream.verdict(verdict(record,accept))
                }
                assertFalse(stream.next())
                val result=stream.finish().getJSONObject("streaming")
                assertEquals(attempts,result.getInt("attemptedFrames"))
                assertEquals(if(accept) target else 0,result.getJSONArray("acceptedIndices").length())
                assertEquals(if(accept) "complete" else "insufficient",result.getString("status"))
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun publicationBoundsAndUnreservedExposureCannotChangeFrozenFiles() {
        val root=Files.createTempDirectory("stream-bounds").toFile()
        try {
            val stream=OriginalStream(OpenCaptureProtocol.localRequest(2,null,null,null),root,AtomicBoolean(),20)
            stream.begin(JSONObject(),"start","fixture")
            val first=frame(root,1);assertTrue(stream.next());stream.publish(first)
            val second=frame(root,2);assertTrue(stream.tryNext())
            assertThrows(IllegalStateException::class.java) { stream.publish(second) }
            assertNull(stream.file(second.getJSONObject("file").getString("name")))
            assertThrows(IllegalArgumentException::class.java) { stream.verdict(verdict(second,true)) }
            assertThrows(IllegalArgumentException::class.java) { stream.verdict(verdict(first,true).put("sha256","wrong")) }
            assertTrue(File(root,first.getJSONObject("file").getString("name")).exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun reservationsCountUnpublishedCapturesAndFreezeStopsAdmission() {
        val root=Files.createTempDirectory("stream-exposure-overlap").toFile()
        try {
            val stream=OriginalStream(OpenCaptureProtocol.localRequest(2,null,null,null),root,AtomicBoolean())
            stream.begin(JSONObject(),"start","fixture")
            assertTrue(stream.next()) // Frame 1 has not been published yet.
            assertTrue(stream.tryNext()) // Frame 2 may expose while 1 awaits metadata.
            assertFalse(stream.tryNext()) // Both unpublished exposures consume admission.
            val a=frame(root,1);stream.publish(a)
            assertFalse(stream.tryNext()) // Publication is not a verdict.
            stream.verdict(verdict(a,false))
            assertTrue(stream.tryNext())
            assertFalse(stream.tryNext())
            val b=frame(root,2);stream.publish(b);stream.verdict(verdict(b,true))
            assertFalse(stream.tryNext()) // Accepted + pending already meets the target.
            val c=frame(root,3);stream.publish(c);stream.verdict(verdict(c,true))
            val frozen=stream.finish().toString()
            assertFalse(stream.tryNext())
            assertThrows(IllegalStateException::class.java) { stream.publish(frame(root,4)) }
            assertEquals(frozen,stream.finish().toString())
        } finally { root.deleteRecursively() }
    }

    @Test fun unreservedFrameCannotBePublished() {
        val root=Files.createTempDirectory("stream-unreserved").toFile()
        try {
            val stream=OriginalStream(OpenCaptureProtocol.localRequest(1,null,null,null),root,AtomicBoolean())
            stream.begin(JSONObject(),"start","fixture")
            assertThrows(IllegalStateException::class.java) { stream.publish(frame(root,1)) }
        } finally { root.deleteRecursively() }
    }

    @Test fun healthyTailWaitsForVerdictAndCancellationStillUnblocks() {
        for (cancelled in listOf(false,true)) {
            val root=Files.createTempDirectory("stream-tail").toFile()
            try {
                val cancel=AtomicBoolean()
                val stream=OriginalStream(OpenCaptureProtocol.localRequest(1,null,null,null),root,cancel)
                stream.begin(JSONObject(),"start","fixture")
                assertTrue(stream.next());assertFalse(stream.tryNext())
                val first=frame(root,1);stream.publish(first)
                val done=CountDownLatch(1);var next:Boolean?=null;var failure:Throwable?=null
                val worker=Thread { try { next=stream.next() } catch(e:Throwable) { failure=e } finally { done.countDown() } }.apply { start() }
                assertFalse(done.await(50,TimeUnit.MILLISECONDS))
                if(cancelled) cancel.set(true) else stream.verdict(verdict(first,true))
                assertTrue(done.await(1,TimeUnit.SECONDS));worker.join()
                if(cancelled) assertTrue(failure is IllegalStateException) else {
                    assertNull(failure);assertEquals(false,next)
                    assertEquals(1,stream.finish().getJSONObject("streaming").getInt("attemptedFrames"))
                }
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun lookaheadRemainsAvailableBeforeTheTail() {
        val root=Files.createTempDirectory("stream-interior").toFile()
        try {
            val stream=OriginalStream(OpenCaptureProtocol.localRequest(6,null,null,null),root,AtomicBoolean())
            stream.begin(JSONObject(),"start","fixture")
            assertTrue(stream.next());assertTrue(stream.tryNext());assertFalse(stream.tryNext())
            val first=frame(root,1);stream.publish(first);stream.verdict(verdict(first,true))
            assertTrue(stream.tryNext())
        } finally { root.deleteRecursively() }
    }
}
