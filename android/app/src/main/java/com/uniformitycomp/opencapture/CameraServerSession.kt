// SPDX-License-Identifier: GPL-3.0-only
package com.uniformitycomp.opencapture

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ServerFault(val status: Int, message: String) : RuntimeException(message)

/** Owns transient files until verified receipt, or until this foreground session ends. */
class CameraServerSession(
    private val root: File,
    private val capture: (JSONObject, AtomicBoolean, (String) -> Unit) -> Pair<JSONObject, File?>,
    private val notice: (String) -> Unit,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val streamCapture: ((JSONObject, AtomicBoolean, (String) -> Unit, OriginalStream) -> Pair<JSONObject, File?>)? = null,
) {
    private data class Job(val request: String, val cancel: AtomicBoolean = AtomicBoolean(false),
        var status: String = "running", var message: String = "Capturing", var result: ByteArray? = null,
        var hash: String? = null, val files: MutableMap<String, File> = linkedMapOf(),
        var writing: Boolean = true, var stream: OriginalStream? = null)
    private val worker = Executors.newSingleThreadExecutor()
    private val jobs = linkedMapOf<String, Job>()
    private var owner: String? = null
    private var touched = 0L
    private var draining = false
    private var switching = false
    private var closed = false
    private val ledger = File(root.parentFile, "server-job-history")

    init { root.mkdirs(); clearFiles() }
    private fun fail(code: Int, message: String): Nothing = throw ServerFault(code, message)
    private fun clearFiles() {
        root.listFiles().orEmpty().filter { it.isDirectory && OpenCaptureProtocol.jobId.matches(it.name) }
            .forEach { CaptureArchives(root).delete(it.name) }
    }
    @Synchronized fun connect(body: JSONObject): JSONObject {
        expire()
        if (closed || draining || switching) fail(503, "Camera session is stopping or changing lenses")
        val id = body.getString("sessionID")
        require(body.keys().asSequence().toSet() == setOf("protocolVersion", "sessionID", "clientName") &&
            body.getInt("protocolVersion") == 1 && OpenCaptureProtocol.jobId.matches(id) &&
            body.getString("clientName").length in 1..80) { "Invalid connection request" }
        if (owner != null && owner != id) fail(409, "Another PC is connected")
        owner = id; touched = now()
        notice("Connected to ${body.getString("clientName")}")
        return JSONObject().put("protocolVersion", 1).put("sessionID", id)
    }
    @Synchronized fun authorize(id: String?) {
        expire()
        if (id == null || owner == null || owner != id || draining || closed) fail(409, "No active session; connect again")
        touched = now()
    }
    @Synchronized fun expire() { if (owner != null && now() - touched > 120_000) end() }
    @Synchronized fun end() {
        if (draining || closed) return
        owner = null; draining = true
        jobs.values.forEach { it.cancel.set(true) }
        worker.execute {
            synchronized(this) {
                try { clearFiles(); jobs.clear(); notice("Ready") }
                finally { draining = false }
            }
        }
    }
    @Synchronized fun close() { if (closed) return; end(); closed = true; worker.shutdown() }
    @Synchronized fun state(): JSONObject = JSONObject().put("protocolVersion", 1)
        .put("cameraReady", !switching && jobs.values.none { it.writing })
        .put("connected", owner != null).put("job", jobs.keys.lastOrNull()?.let { jobState(it) } ?: JSONObject.NULL)
    private fun pending() = switching || jobs.values.any { it.writing || it.status in setOf("running", "complete") }
    fun select(id: String?, action: () -> JSONObject): JSONObject {
        synchronized(this) {
            authorize(id)
            if (pending()) fail(409, "Finish the capture and transfer before changing lenses")
            switching = true
        }
        try {
            val result = action()
            synchronized(this) { authorize(id) }
            return result
        } finally { synchronized(this) { switching = false } }
    }
    @Synchronized fun submit(request: JSONObject): JSONObject {
        if (closed || draining || owner == null) fail(409, "No active camera session")
        OpenCaptureProtocol.request(request)
        require(!request.has("streaming") || streamCapture != null) { "Streaming unavailable" }
        val id = request.getString("id")
        val serialized = canonical(request).toString()
        jobs[id]?.let {
            if (it.request != serialized) fail(409, "Capture ID has a different request")
            return jobState(id)
        }
        if (pending()) fail(409, "Finish the current capture and transfer first")
        if (jobs.size >= 1000) fail(409, "Session job limit reached; reconnect")
        ledger.mkdirs()
        if (!File(ledger, id).createNewFile()) fail(409, "Capture ID was already used; do not replay it")
        val job = Job(serialized); jobs[id] = job
        if(request.has("streaming")) job.stream=OriginalStream(request,File(root,id),job.cancel)
        worker.execute {
            try {
                check(!job.cancel.get()) { "Cancelled" }
                val progress: (String) -> Unit = { message ->
                    synchronized(this) { job.message = message }; notice(message)
                }
                val (result, directory) = job.stream?.let { requireNotNull(streamCapture)(request,job.cancel,progress,it) }
                    ?: capture(request,job.cancel,progress)
                val bytes = result.toString().toByteArray(Charsets.UTF_8)
                require(bytes.size <= 1024 * 1024) { "Capture result too large" }
                val files = linkedMapOf<String, File>()
                if (directory != null) {
                    require(directory.canonicalFile == File(root, id).canonicalFile) { "Capture directory is outside this job" }
                    fun add(receipt: JSONObject) {
                        val name = receipt.getString("name")
                        require(OpenCaptureProtocol.fileName.matches(name)) { "Invalid capture filename" }
                        val file = File(directory, name)
                        require(file.isFile && file.length() in 1..134217728 && files.put(name, file) == null) { "Invalid/duplicate capture file" }
                    }
                    OpenCaptureProtocol.transferReceipts(result).forEach(::add)
                    require(files.values.sumOf { it.length() } <= 4L * 1024 * 1024 * 1024) { "Capture exceeds storage limit" }
                }
                synchronized(this) {
                    check(!job.cancel.get()) { "Cancelled" }
                    if(job.status=="running") {
                        job.result = bytes; job.hash = sha256(bytes); job.files.putAll(files)
                        job.status = "complete"; job.message = "Waiting for transfer"
                    }
                }
                notice("Transferring")
            } catch (error: Exception) {
                synchronized(this) {
                    if(job.stream?.isFrozen!=true || job.cancel.get()) {
                        job.status = if (job.cancel.get()) "cancelled" else "failed"
                        job.message = (error.message ?: "Capture failed").take(256)
                        cleanup(id, job)
                    }
                }
                notice(job.message)
            } finally {
                synchronized(this) {
                    job.writing=false
                    if(job.status=="acknowledged") cleanup(id,job)
                }
            }
        }
        return jobState(id)
    }
    private fun job(id: String): Job = jobs[id] ?: fail(404, "Unknown capture")
    @Synchronized fun jobState(id: String): JSONObject {
        val job = job(id)
        return JSONObject().put("protocolVersion", 1).put("id", id).put("status", job.status)
            .put("message", job.message).put("cameraReady",!job.writing)
            .also { if (job.hash != null) it.put("resultSha256", job.hash); job.stream?.let { stream -> it.put("streaming",stream.state()) } }
    }
    @Synchronized fun result(id: String): ByteArray = job(id).let {
        if (it.status != "complete") fail(409, "Capture result unavailable")
        it.result ?: fail(404, "Capture result unavailable")
    }
    @Synchronized fun file(id: String, name: String): File = job(id).let {
        if(it.status=="running" && it.stream!=null) return it.stream!!.file(name) ?: fail(404,"Frame not published")
        if (it.status != "complete") fail(409, "Capture files unavailable")
        it.files[name] ?: fail(404, "Unknown capture file")
    }
    @Synchronized fun verdict(id: String, value: JSONObject): JSONObject {
        val job=job(id); val stream=job.stream ?: fail(409,"Not a streamed capture")
        if(job.status !in setOf("running","complete","acknowledged")) fail(409,"Capture ended")
        stream.verdict(value)
        if(job.status=="running") stream.result()?.let { result ->
            val bytes=result.toString().toByteArray(Charsets.UTF_8)
            require(bytes.size<=1024*1024)
            OpenCaptureProtocol.saveJson(File(root,"$id/manifest.json"),result)
            OpenCaptureProtocol.transferReceipts(result).forEach { r ->
                job.files[r.getString("name")]=requireNotNull(stream.file(r.getString("name")))
            }
            job.result=bytes;job.hash=sha256(bytes);job.status="complete";job.message="Selection frozen; waiting for receipt"
        }
        return jobState(id)
    }
    @Synchronized fun ack(id: String, hash: String): JSONObject {
        val job = job(id)
        if (job.status !in setOf("complete", "acknowledged") || hash != job.hash) fail(409, "Receipt does not match result")
        if(!job.writing) cleanup(id, job)
        job.status = "acknowledged"; job.message = "Received by Windows"
        notice("Connected. Ready")
        return jobState(id)
    }
    @Synchronized fun cancel(id: String): JSONObject {
        val job = job(id); job.cancel.set(true)
        if (!job.writing) { cleanup(id, job); job.status = "cancelled"; job.message = "Cancelled" }
        return jobState(id)
    }
    private fun cleanup(id: String, job: Job) {
        if (File(root, id).exists()) CaptureArchives(root).delete(id)
        job.result = null; job.files.clear()
    }
    companion object {
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private fun canonical(value: JSONObject): JSONObject = JSONObject().also { sorted ->
            value.keys().asSequence().sorted().forEach {
                val item = value.get(it)
                sorted.put(it, if (item is JSONObject) canonical(item) else item)
            }
        }
    }
}
