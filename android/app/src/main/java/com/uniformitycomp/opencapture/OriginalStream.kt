// SPDX-License-Identifier: GPL-3.0-only
package com.uniformitycomp.opencapture

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/** Immutable frame publication, one-frame lookahead, and controller-owned selection. */
class OriginalStream(private val request: JSONObject, private val directory: File,
                     private val cancel: AtomicBoolean, private val maximumBytes: Long = 4L*1024*1024*1024) {
    private val lock = java.lang.Object()
    private val frames = mutableListOf<JSONObject>()
    private val verdicts = mutableListOf<JSONObject>()
    private val files = linkedMapOf<String, File>()
    private var base: JSONObject? = null
    private var frozen: JSONObject? = null
    private var lastProgress = System.nanoTime()
    private var publishedBytes = 0L
    private var reservedAttempts = 0
    init { require(maximumBytes in 1..4L*1024*1024*1024) }
    val limit = request.getInt("frameCount") * 2
    val isFrozen: Boolean get() = synchronized(lock) { frozen != null }

    fun begin(camera: JSONObject, started: String, processing: String) = synchronized(lock) {
        check(base == null)
        base = JSONObject().put("protocolVersion", 1).put("request", JSONObject(request.toString()))
            .put("camera", JSONObject(camera.toString())).put("startedAt", started).put("processing", processing)
    }
    private fun checkLive() {
        check(!cancel.get()) { "Capture cancelled" }
        check(System.nanoTime()-lastProgress < 240_000_000_000L) { "Controller frame verdict timed out" }
    }
    fun next(): Boolean = reserve(wait = true)
    fun tryNext(): Boolean = reserve(wait = false)
    private fun reserve(wait: Boolean): Boolean = synchronized(lock) {
        while (frozen == null && reservedAttempts-verdicts.size >= 2) {
            checkLive()
            if (!wait) return@synchronized false
            lock.wait(50)
        }
        checkLive()
        if (frozen != null || reservedAttempts >= limit) return@synchronized false
        reservedAttempts++
        true
    }
    fun publish(frame: JSONObject) = synchronized(lock) {
        val receipts = listOf(frame.getJSONObject("file"), frame.getJSONObject("metadataFile")) +
            listOfNotNull(frame.optJSONObject("previewFile"))
        check(frame.getInt("index") == frames.size+1 && frames.size < reservedAttempts && frames.size < limit)
        require(receipts.map { it.getString("name") }.toSet().size == receipts.size)
        for (r in receipts) {
            val name=r.getString("name"); val file=File(directory,name)
            require(OpenCaptureProtocol.fileName.matches(name) && file.canonicalFile.parentFile==directory.canonicalFile &&
                file.isFile && file.length()==r.getLong("bytes") && file.length() in 1..134217728 && !files.containsKey(name))
        }
        if (frozen != null) { receipts.forEach { File(directory,it.getString("name")).delete() }; return@synchronized }
        val bytes = receipts.sumOf { it.getLong("bytes") }
        check(bytes <= maximumBytes-publishedBytes) { "Capture exceeds storage limit" }
        receipts.forEach { files[it.getString("name")]=File(directory,it.getString("name")) }
        publishedBytes += bytes
        frames.add(JSONObject(frame.toString())); lastProgress=System.nanoTime(); lock.notifyAll()
    }
    fun state(): JSONObject = synchronized(lock) {
        JSONObject().put("mode","originals-v1").put("frames",JSONArray(frames.map { JSONObject(it.toString()) }))
            .put("verdictCount",verdicts.size).put("frozen",frozen!=null)
    }
    fun file(name: String): File? = synchronized(lock) { files[name] }
    fun verdict(value: JSONObject) = synchronized(lock) {
        require(value.keys().asSequence().toSet()==setOf("index","sha256","accepted","reason") &&
            value.get("accepted") is Boolean && value.get("index") is Number)
        val index=value.getInt("index"); val reason=value.getString("reason")
        require(value.getDouble("index")==index.toDouble() && reason.length in 1..80)
        if (index in 1..verdicts.size) {
            val previous=verdicts[index-1]
            require(listOf("index","sha256","accepted","reason").all { previous.get(it)==value.get(it) }) { "Conflicting frame verdict" }
            return@synchronized
        }
        require(frozen==null && index==verdicts.size+1 && index<=frames.size &&
            value.getString("sha256")==frames[index-1].getJSONObject("file").getString("sha256")) { "Wrong or late frame verdict" }
        verdicts.add(JSONObject(value.toString())); lastProgress=System.nanoTime()
        val accepted=verdicts.mapIndexedNotNull { i,v -> if(v.getBoolean("accepted")) i+1 else null }
        if (accepted.size==request.getInt("frameCount") || verdicts.size==limit) {
            val descriptor=JSONObject().put("mode","originals-v1")
                .put("status",if(accepted.size==request.getInt("frameCount")) "complete" else "insufficient")
                .put("targetFrames",request.getInt("frameCount")).put("attemptedFrames",verdicts.size)
                .put("acceptedIndices",JSONArray(accepted)).put("rejected",JSONArray(verdicts.mapIndexedNotNull { i,v ->
                    if(v.getBoolean("accepted")) null else JSONObject().put("index",i+1).put("reason",v.getString("reason")) }))
            frozen=JSONObject(requireNotNull(base).toString()).put("frames",JSONArray(frames.take(verdicts.size)))
                .put("streaming",descriptor).put("completedAt",Instant.now().toString())
        }
        lock.notifyAll()
    }
    fun result(): JSONObject? = synchronized(lock) { frozen?.let { JSONObject(it.toString()) } }
    fun finish(): JSONObject = synchronized(lock) {
        while (frozen==null) { checkLive(); lock.wait(50) }
        JSONObject(frozen.toString())
    }
}
