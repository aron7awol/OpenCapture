// SPDX-License-Identifier: GPL-3.0-only
package com.uniformitycomp.opencapture

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class OpenCaptureApplication : Application() {
    lateinit var diagnostics: PersistentDiagnostics
        private set
    lateinit var events: CameraDiagnostics
        private set
    private lateinit var writer: DiagnosticWriter
    private var exitInfo = JSONObject().put("status", "unavailable")

    override fun onCreate() {
        super.onCreate()
        diagnostics = PersistentDiagnostics(File(filesDir, "camera-diagnostics"), JSONObject()
            .put("id", UUID.randomUUID().toString()).put("pid", Process.myPid())
            .put("startedUnixMs", System.currentTimeMillis()).put("appVersion", BuildConfig.VERSION_NAME)
            .put("appBuild", BuildConfig.VERSION_CODE).put("model", Build.MODEL).put("sdk", Build.VERSION.SDK_INT))
        writer = DiagnosticWriter(diagnostics::save)
        events = CameraDiagnostics(persist = writer::offer)
        events.record("process_started")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                // No exception messages: these may contain URLs, paths or controller input.
                events.record("uncaught_exception", JSONObject().put("exceptionClass", error.javaClass.name)
                    .put("stack", JSONArray(error.stackTrace.take(32).map { it.toString().take(256) })), fault = true)
                writer.flush()
            } catch (_: Throwable) { /* A crash handler must still delegate to Android. */ }
            finally {
                if (previous != null) previous.uncaughtException(thread, error)
                else { Process.killProcess(Process.myPid()); kotlin.system.exitProcess(10) }
            }
        }
        exitInfo = readExitInfo()
    }

    fun diagnosticBundle(deviceID: String): JSONObject = diagnostics.bundle(deviceID, exitInfo)

    private fun readExitInfo(): JSONObject {
        if (Build.VERSION.SDK_INT < 30) return JSONObject().put("status", "unsupported")
        return try {
            val manager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            val exits = manager.getHistoricalProcessExitReasons(packageName, 0, 8)
            JSONObject().put("status", "available").put("source", "ApplicationExitInfo")
                .put("records", JSONArray(exits.map { entry ->
                    JSONObject().put("pid", entry.pid).put("timestampUnixMs", entry.timestamp)
                        .put("reason", entry.reason).put("status", entry.status).put("importance", entry.importance)
                }))
        } catch (_: Exception) { JSONObject().put("status", "unavailable") }
    }
}
