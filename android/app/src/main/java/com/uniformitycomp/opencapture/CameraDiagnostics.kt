// SPDX-License-Identifier: GPL-3.0-only
package com.uniformitycomp.opencapture

import org.json.JSONArray
import org.json.JSONObject

/** Bounded in-memory evidence; no image bytes, controller credentials or context. */
class CameraDiagnostics(private val persist: ((JSONObject) -> Unit)? = null,
                        private val now: () -> Long = { System.nanoTime() }) {
    private val events = ArrayDeque<JSONObject>()
    private var sequence = 0L
    private var firstFault: JSONObject? = null
    private var request = JSONObject()
    private var startedSequence = 0L

    @Synchronized fun begin(value: JSONObject, cameraId: String) {
        firstFault = null
        startedSequence = sequence + 1
        request = JSONObject().put("cameraID", cameraId)
        for (key in listOf("id", "operation", "frameCount", "exposureSeconds", "iso", "focusPosition", "captureScheduling"))
            if (value.has(key)) request.put(key, value.get(key))
        record("operation_started")
    }

    @Synchronized fun record(event: String, details: JSONObject = JSONObject(), fault: Boolean = false) {
        val entry = JSONObject(details.toString()).put("sequence", ++sequence)
            .put("elapsedNs", now()).put("unixMs", System.currentTimeMillis()).put("event", event)
        if (fault && firstFault == null) firstFault = entry
        events.addLast(entry)
        while (events.size > 128) events.removeFirst()
        try { persist?.invoke(snapshot()) } catch (_: Exception) { /* Diagnostics cannot break capture. */ }
    }

    @Synchronized fun snapshot(): JSONObject = JSONObject().put("schemaVersion", 1)
        .put("request", JSONObject(request.toString())).put("startedSequence", startedSequence)
        .put("firstFault", firstFault?.let { JSONObject(it.toString()) } ?: JSONObject.NULL)
        .put("events", JSONArray(events.map { JSONObject(it.toString()) }))

    companion object {
        fun errorName(code: Int): String = when (code) {
            1 -> "ERROR_CAMERA_IN_USE"
            2 -> "ERROR_MAX_CAMERAS_IN_USE"
            3 -> "ERROR_CAMERA_DISABLED"
            4 -> "ERROR_CAMERA_DEVICE"
            5 -> "ERROR_CAMERA_SERVICE"
            else -> "UNKNOWN_CAMERA_ERROR"
        }
    }
}

class CameraDiagnosticException(cause: Exception, val diagnostics: JSONObject) :
    RuntimeException(diagnostics.optJSONObject("firstFault")?.let {
        "${it.optString("event", "")}: ${it.optString("detail", "")}. ${cause.message}"
    } ?: cause.message, cause)
