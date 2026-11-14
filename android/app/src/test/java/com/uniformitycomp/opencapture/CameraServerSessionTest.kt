package com.uniformitycomp.opencapture

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CameraServerSessionTest {
    @Test fun discardedExposureFailureKeepsFrozenResult() {
        val root=Files.createTempDirectory("oc-late-failure").resolve("staging").toFile()
        val published=CountDownLatch(1);val release=CountDownLatch(1)
        var record: JSONObject?=null
        val session=CameraServerSession(root,{ _,_,_ -> error("Unexpected batch") },{},streamCapture={ request,_,_,stream ->
            val dir=CaptureArchives(root).create(request.getString("id"))
            stream.begin(JSONObject(),"start","fixture")
            val image=File(dir,"frame-0001.dng").apply { writeText("original") }
            val meta=File(dir,"frame-0001.json").apply { writeText("{}") }
            record=JSONObject().put("index",1).put("file",OpenCaptureProtocol.receipt(image)).put("metadataFile",OpenCaptureProtocol.receipt(meta))
            check(stream.next());check(stream.tryNext());stream.publish(record!!);published.countDown()
            check(release.await(3,TimeUnit.SECONDS))
            error("Discarded lookahead failed")
        })
        try {
            session.connect(connection(UUID.randomUUID().toString()))
            val request=OpenCaptureProtocol.localRequest(1,null,null,null).put("streaming",JSONObject().put("mode","originals-v1"))
            val id=request.getString("id");session.submit(request)
            assertTrue(published.await(3,TimeUnit.SECONDS))
            session.verdict(id,JSONObject().put("index",1).put("sha256",record!!.getJSONObject("file").getString("sha256"))
                .put("accepted",true).put("reason","usable"))
            val frozen=session.result(id)
            release.countDown();waitFor { session.state().getBoolean("cameraReady") }
            assertEquals("complete",session.jobState(id).getString("status"))
            assertArrayEquals(frozen,session.result(id))
            session.ack(id,CameraServerSession.sha256(frozen))
        } finally { release.countDown();session.close() }
    }

    @Test fun frozenCancellationAndSessionEndWaitForWriter() {
        for (end in listOf(false,true)) {
            val root=Files.createTempDirectory("oc-stream-cancel").resolve("staging").toFile()
            val published=CountDownLatch(1);val release=CountDownLatch(1)
            var record: JSONObject?=null
            val session=CameraServerSession(root,{ _,_,_ -> error("Unexpected batch") },{},streamCapture={ request,cancel,_,stream ->
                val dir=CaptureArchives(root).create(request.getString("id"))
                stream.begin(JSONObject(),"start","fixture")
                val image=File(dir,"frame-0001.dng").apply { writeText("original") }
                val meta=File(dir,"frame-0001.json").apply { writeText("{}") }
                record=JSONObject().put("index",1).put("file",OpenCaptureProtocol.receipt(image)).put("metadataFile",OpenCaptureProtocol.receipt(meta))
                check(stream.next());check(stream.tryNext());stream.publish(record!!);published.countDown()
                check(release.await(3,TimeUnit.SECONDS))
                assertTrue(cancel.get());assertTrue(image.exists())
                error("Cancelled while draining")
            })
            try {
                val owner=UUID.randomUUID().toString();session.connect(connection(owner))
                val request=OpenCaptureProtocol.localRequest(1,null,null,null).put("streaming",JSONObject().put("mode","originals-v1"))
                val id=request.getString("id");session.submit(request)
                assertTrue(published.await(3,TimeUnit.SECONDS))
                session.verdict(id,JSONObject().put("index",1).put("sha256",record!!.getJSONObject("file").getString("sha256"))
                    .put("accepted",true).put("reason","usable"))
                if(end) session.end() else session.cancel(id)
                assertFalse(session.state().getBoolean("cameraReady"));assertTrue(File(root,id).exists())
                fault(409) { session.submit(OpenCaptureProtocol.localRequest(1,null,null,null)) }
                fault(409) { session.select(owner) { JSONObject() } }
                release.countDown();waitFor { session.state().getBoolean("cameraReady") && !File(root,id).exists() }
            } finally { release.countDown();session.close() }
        }
    }

    @Test fun streamedReceiptPrecedesExtraExposureCleanupButNextJobWaits() {
        val root=Files.createTempDirectory("oc-stream-session").resolve("staging").toFile()
        val published=CountDownLatch(1);val release=CountDownLatch(1)
        var record: JSONObject?=null
        val session=CameraServerSession(root,{ _,_,_ -> error("Unexpected batch capture") },{},streamCapture={ request,_,_,stream ->
            val dir=CaptureArchives(root).create(request.getString("id"))
            stream.begin(JSONObject(),"start","fixture")
            val image=File(dir,"frame-0001.dng").apply { writeText("original") }
            val meta=File(dir,"frame-0001.json").apply { writeText("{}") }
            record=JSONObject().put("index",1).put("file",OpenCaptureProtocol.receipt(image)).put("metadataFile",OpenCaptureProtocol.receipt(meta))
            check(stream.next());check(stream.tryNext());stream.publish(record!!);published.countDown()
            release.await(3,TimeUnit.SECONDS)
            assertTrue("ACK must not delete files under the writer",image.exists())
            stream.finish() to dir
        })
        try {
            session.connect(connection(UUID.randomUUID().toString()))
            val request=OpenCaptureProtocol.localRequest(1,null,null,null).put("streaming",JSONObject().put("mode","originals-v1"))
            val id=request.getString("id");session.submit(request)
            assertTrue(published.await(3,TimeUnit.SECONDS))
            session.verdict(id,JSONObject().put("index",1).put("sha256",record!!.getJSONObject("file").getString("sha256"))
                .put("accepted",true).put("reason","usable"))
            assertEquals("complete",session.jobState(id).getString("status"))
            session.ack(id,CameraServerSession.sha256(session.result(id)))
            assertFalse(session.state().getBoolean("cameraReady"))
            assertTrue(File(root,id).exists())
            fault(409) { session.submit(OpenCaptureProtocol.localRequest(1,null,null,null)) }
            release.countDown();waitFor { session.state().getBoolean("cameraReady") }
            assertFalse(File(root,id).exists())
        } finally { release.countDown();session.close() }
    }
    private fun connection(id: String) = JSONObject().put("protocolVersion", 1).put("sessionID", id).put("clientName", "Test PC")
    private fun waitFor(test: () -> Boolean) {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (!test()) { assertTrue("Operation timed out", System.nanoTime() < deadline); Thread.sleep(5) }
    }
    private fun fault(code: Int, action: () -> Unit) {
        try { action(); fail("Expected server fault") } catch (e: ServerFault) { assertEquals(code, e.status) }
    }
    @Test fun retryDoesNotRecaptureAndAckDeletesOnlyTransientFiles() {
        val base = Files.createTempDirectory("oc-server").toFile()
        val root = File(base, "server-transfers")
        val old = File(base, "captures/keep.dng").apply { parentFile!!.mkdirs(); writeText("old") }
        val calls = AtomicInteger()
        val session = CameraServerSession(root, { request, _, _ ->
            calls.incrementAndGet()
            val dir = CaptureArchives(root).create(request.getString("id"))
            val image = File(dir, "frame-0001.dng").apply { writeText("original") }
            val meta = File(dir, "frame-0001.json").apply { writeText("{}") }
            JSONObject().put("frames", JSONArray().put(JSONObject().put("file", OpenCaptureProtocol.receipt(image))
                .put("metadataFile", OpenCaptureProtocol.receipt(meta)))) to dir
        }, {})
        try {
            val owner = UUID.randomUUID().toString()
            session.connect(connection(owner)); session.authorize(owner)
            val request = OpenCaptureProtocol.localRequest(1, null, null, null)
            val id = request.getString("id")
            session.submit(request)
            waitFor { session.jobState(id).getString("status") == "complete" }
            session.submit(JSONObject(request.toString()))
            assertEquals(1, calls.get())
            assertEquals("original", session.file(id, "frame-0001.dng").readText())
            fault(409) { session.select(owner) { JSONObject() } }
            fault(409) { session.ack(id, "bad") }
            val hash = CameraServerSession.sha256(session.result(id))
            session.ack(id, hash); session.ack(id, hash)
            assertFalse(File(root, id).exists()); assertTrue(old.exists())
            assertEquals("acknowledged", session.submit(request).getString("status"))
            val changed = JSONObject(request.toString()).put("context", JSONObject().put("label", "different\nrequest"))
            fault(409) { session.submit(changed) }
            session.end(); waitFor { !session.state().optBoolean("connected") && root.listFiles().orEmpty().isEmpty() }
        } finally { session.close() }
    }
    @Test fun ownershipExpiryAndCancellationDrainWriterBeforeCleanup() {
        val root = Files.createTempDirectory("oc-session").resolve("staging").toFile()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        var clock = 0L
        val session = CameraServerSession(root, { request, cancel, _ ->
            val dir = CaptureArchives(root).create(request.getString("id"))
            File(dir, "frame-0001.dng").writeText("partial")
            entered.countDown(); release.await(3, TimeUnit.SECONDS)
            assertTrue(cancel.get()); assertTrue(dir.exists())
            throw IllegalStateException("Cancelled")
        }, {}, { clock })
        try {
            val owner = UUID.randomUUID().toString()
            session.connect(connection(owner)); session.connect(connection(owner))
            fault(409) { session.connect(connection(UUID.randomUUID().toString())) }
            val request = OpenCaptureProtocol.localRequest(1, null, null, null)
            session.submit(request); assertTrue(entered.await(3, TimeUnit.SECONDS))
            clock = 120_001; session.expire()
            fault(409) { session.authorize(owner) }
            assertTrue(File(root, request.getString("id")).exists())
            release.countDown()
            waitFor { root.listFiles().orEmpty().isEmpty() }
            val next = UUID.randomUUID().toString()
            waitFor { try { session.connect(connection(next)); true } catch (_: ServerFault) { false } }
            fault(409) { session.submit(request) }
        } finally { release.countDown(); session.close() }
    }
    @Test fun privatePeersExcludePublicAndMalformedAddresses() {
        for (value in listOf("10.0.0.1", "192.168.11.10", "172.16.4.2", "169.254.1.2", "127.0.0.1")) assertTrue(CameraServer.privateIPv4(value))
        for (value in listOf("8.8.8.8", "172.32.1.1", "192.168.1.999", "camera.local", "::1")) assertFalse(CameraServer.privateIPv4(value))
    }
    @Test fun repeatedLifecycleCleanupAfterCloseDoesNotQueueOnStoppedWorker() {
        val root = Files.createTempDirectory("oc-lifecycle").resolve("staging").toFile()
        val drained = CountDownLatch(1)
        val session = CameraServerSession(root, { _, _, _ -> error("No capture expected") }, { drained.countDown() })
        fault(409) { session.submit(OpenCaptureProtocol.localRequest(1, null, null, null)) }
        session.close()
        assertTrue(drained.await(3, TimeUnit.SECONDS))
        session.end(); session.close()
        fault(409) { session.submit(OpenCaptureProtocol.localRequest(1, null, null, null)) }
        fault(503) { session.connect(connection(UUID.randomUUID().toString())) }
    }
}
