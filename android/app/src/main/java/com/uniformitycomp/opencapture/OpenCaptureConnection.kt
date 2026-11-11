package com.uniformitycomp.opencapture

import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Foreground-only job loop. Heartbeats remain independent of slow camera and file I/O. */
class OpenCaptureConnection(
    private val transport: OpenCaptureTransport,
    private val camera: OpenCaptureCamera,
    private val stopped: AtomicBoolean,
    private val preview: () -> ByteArray?,
    private val status: (String) -> Unit,
    private val readback: (JSONObject) -> Unit
) {
    @Volatile private var currentCancel: AtomicBoolean? = null
    fun cancel() { currentCancel?.set(true) }

    fun run() {
        try {
            transport.json("POST", "/oc/v1/capabilities", camera.capabilities())
            status("Connected. Use the PC's OpenCapture controls.")
            while (!stopped.get()) {
                preview()?.let { transport.preview(it) }
                val lease = transport.json("GET", "/oc/v1/lease")
                require(lease.getInt("protocolVersion") == 1)
                val request = lease.optJSONObject("request")
                if (request == null) { Thread.sleep(700); continue }
                val id = request.getString("id")
                require(OpenCaptureProtocol.jobId.matches(id))
                val secret = lease.getString("leaseToken")
                val cancel = AtomicBoolean(stopped.get())
                currentCancel = cancel
                val heartbeats = Executors.newSingleThreadScheduledExecutor()
                heartbeats.scheduleWithFixedDelay({
                    try {
                        if (stopped.get() || transport.json("POST", "/oc/v1/jobs/$id/heartbeat",
                                JSONObject().put("protocolVersion", 1), secret).optBoolean("cancelled")) cancel.set(true)
                    } catch (_: Exception) { cancel.set(true) }
                }, 0, 10, TimeUnit.SECONDS)
                try {
                    OpenCaptureProtocol.request(request)
                    if (cancel.get()) throw InterruptedException("Capture cancelled")
                    val (manifest, directory) = camera.capture(request, cancel, status)
                    if (cancel.get() || stopped.get()) throw InterruptedException("Capture cancelled")
                    if (directory == null) {
                        transport.json("POST", "/oc/v1/jobs/$id/controls-complete", manifest, secret, cancel)
                        readback(manifest)
                        status("Controls applied. Camera remains locked.")
                    } else {
                        val frames = manifest.getJSONArray("frames")
                        for (i in 0 until frames.length()) {
                            val frame = frames.getJSONObject(i)
                            status("Uploading original ${i + 1} / ${frames.length()}")
                            for (key in listOf("file", "metadataFile")) {
                                val receipt = frame.getJSONObject(key)
                                val file = File(directory, receipt.getString("name"))
                                // Same lease/name/hash makes a lost-response retry idempotent.
                                try { transport.upload(id, secret, file, receipt, cancel) }
                                catch (error: java.io.IOException) {
                                    if (cancel.get() || stopped.get()) throw error
                                    transport.upload(id, secret, file, receipt, cancel)
                                }
                            }
                        }
                        transport.json("POST", "/oc/v1/jobs/$id/complete", manifest, secret, cancel)
                        status("Uploaded ${frames.length()} DNG originals; local archive retained.")
                    }
                } catch (error: Exception) {
                    val message = error.message ?: "Capture failed"
                    runCatching { transport.json("POST", "/oc/v1/jobs/$id/failure", JSONObject()
                        .put("protocolVersion", 1).put("status", if (cancel.get() || stopped.get()) "cancelled" else "failed")
                        .put("message", message.take(256)), secret) }
                    status(message)
                } finally { heartbeats.shutdownNow(); currentCancel = null }
            }
        } finally { transport.close() }
    }
}
