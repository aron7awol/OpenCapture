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
            stream.verdict(verdict(a,true));assertTrue(resumed.await(1,TimeUnit.SECONDS));worker.join()
            val c=frame(root,3);stream.publish(c);stream.verdict(verdict(b,false))
            val d=frame(root,4);assertTrue(stream.next());stream.publish(d)
            stream.verdict(verdict(c,true));val frozen=stream.finish().toString()
            stream.verdict(verdict(c,true));assertEquals(frozen,stream.finish().toString())
            assertFalse(stream.next());assertEquals(3,stream.finish().getJSONArray("frames").length())
            val accepted = stream.finish().getJSONObject("streaming").getJSONArray("acceptedIndices")
            assertEquals(listOf(1,3), (0 until accepted.length()).map { accepted.getInt(it) })
            assertThrows(IllegalArgumentException::class.java) { stream.verdict(verdict(c,false)) }
            assertThrows(IllegalArgumentException::class.java) { stream.verdict(verdict(d,true)) }
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

    @Test fun publicationBoundsAndLateExposureCannotChangeFrozenFiles() {
        val root=Files.createTempDirectory("stream-bounds").toFile()
        try {
            val stream=OriginalStream(OpenCaptureProtocol.localRequest(1,null,null,null),root,AtomicBoolean(),20)
            stream.begin(JSONObject(),"start","fixture")
            val first=frame(root,1);stream.publish(first)
            val second=frame(root,2)
            assertThrows(IllegalStateException::class.java) { stream.publish(second) }
            assertNull(stream.file(second.getJSONObject("file").getString("name")))
            assertThrows(IllegalArgumentException::class.java) { stream.verdict(verdict(second,true)) }
            assertThrows(IllegalArgumentException::class.java) { stream.verdict(verdict(first,true).put("sha256","wrong")) }
            stream.verdict(verdict(first,true));val result=stream.finish().toString()
            stream.publish(second)
            assertEquals(result,stream.finish().toString())
            assertFalse(File(root,second.getJSONObject("file").getString("name")).exists())
            assertTrue(File(root,first.getJSONObject("file").getString("name")).exists())
        } finally { root.deleteRecursively() }
    }
}
