// SPDX-License-Identifier: GPL-3.0-only
package com.uniformitycomp.opencapture

import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Coalesces snapshots off camera callbacks; at most one pending snapshot per operation. */
class DiagnosticWriter(private val write: (JSONObject) -> Unit) {
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "OpenCaptureDiagnostics").apply { isDaemon = true }
    }
    private val pending = linkedMapOf<String, JSONObject>()
    private var scheduled = false

    @Synchronized fun offer(snapshot: JSONObject) {
        val key = snapshot.optJSONObject("request")?.optString("id", "") ?: ""
        pending[key] = JSONObject(snapshot.toString())
        while (pending.size > PersistentDiagnostics.MAX_RECORDS) pending.remove(pending.keys.first())
        if (!scheduled) {
            scheduled = true
            worker.execute {
                while (true) {
                    val next = synchronized(this) {
                        if (pending.isEmpty()) { scheduled = false; return@execute }
                        pending.remove(pending.keys.first())!!
                    }
                    try { write(next) } catch (_: Exception) { /* Never terminate the diagnostic worker. */ }
                }
            }
        }
    }

    // Only shutdown/crash handling/tests may wait; camera callbacks never wait for storage.
    fun flush(timeoutMs: Long = 1500): Boolean = try {
        worker.submit {}.get(timeoutMs, TimeUnit.MILLISECONDS)
        true
    } catch (_: Exception) { false }

    fun close() { worker.shutdown() }
}
