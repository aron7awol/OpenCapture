package com.uniformitycomp.opencapture

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

class PersistentDiagnosticsTest {
    private fun process() = JSONObject().put("id", UUID.randomUUID().toString()).put("pid", 123)
    private fun request() = JSONObject().put("id", UUID.randomUUID().toString())

    @Test fun survivesAbruptRestartWithoutCloseAndPreservesPriorFault() {
        val root = Files.createTempDirectory("oc-persist").toFile()
        try {
            val oldProcess = process()
            val store = PersistentDiagnostics(root, oldProcess)
            val events = CameraDiagnostics(persist = store::save)
            val failed = request().put("context", JSONObject().put("secret", "never-save-this"))
            events.begin(failed, "0")
            events.record("camera_error", JSONObject().put("code", 4), true)
            events.begin(request(), "0")
            events.record("frame_received", JSONObject().put("index", 1))
            // No flush/close call: stand in for a process killed between callbacks.
            val next = PersistentDiagnostics(root, process())
            val restarted = CameraDiagnostics(persist = next::save)
            restarted.record("process_started")
            val bundle = next.bundle("phone", JSONObject().put("status", "unsupported"))
            val records = bundle.getJSONArray("records")
            assertEquals(3, records.length())
            val saved = (0 until records.length()).map { records.getJSONObject(it) }
                .single { it.getJSONObject("cameraDiagnostics").getJSONObject("request").optString("id") == failed.getString("id") }
            assertEquals(oldProcess.getString("id"), saved.getJSONObject("process").getString("id"))
            assertEquals(4, saved.getJSONObject("cameraDiagnostics").getJSONObject("firstFault").getInt("code"))
            assertFalse(bundle.toString().contains("never-save-this"))
        } finally { root.deleteRecursively() }
    }

    @Test fun boundedRetentionAndDamagedOrUnfinishedWritesDoNotHideValidRecords() {
        val root = Files.createTempDirectory("oc-bounded").toFile()
        try {
            val store = PersistentDiagnostics(root, process())
            val events = CameraDiagnostics(persist = store::save)
            repeat(20) { events.begin(request(), "0"); events.record("frame_received") }
            assertEquals(PersistentDiagnostics.MAX_RECORDS, root.listFiles()!!.size)
            File(root, "${UUID.randomUUID()}.tmp").writeText("partial")
            File(root, "${UUID.randomUUID()}.json").writeText("broken")
            events.record("cleanup")
            assertTrue(root.listFiles()!!.size <= PersistentDiagnostics.MAX_RECORDS)
            assertTrue(store.bundle("phone", JSONObject()).getJSONArray("records").length() >= 10)
            assertNull(store.storageError)
        } finally { root.deleteRecursively() }
    }

    @Test fun storageFailureAndOversizedSnapshotsAreNonFatalAndDoNotOverwriteEvidence() {
        val root = Files.createTempDirectory("oc-storage").toFile()
        try {
            val store = PersistentDiagnostics(root, process())
            val events = CameraDiagnostics(persist = store::save)
            events.begin(request(), "0")
            val before = root.listFiles()!!.single().readText()
            events.record("too_large", JSONObject().put("detail", "x".repeat(150000)))
            assertNotNull(store.storageError)
            assertEquals(before, root.listFiles()!!.single().readText())
            val blocked = File(root, "not-a-directory").apply { writeText("fixture") }
            val failing = PersistentDiagnostics(blocked, process())
            CameraDiagnostics(persist = failing::save).record("process_started")
            assertNotNull(failing.storageError)
        } finally { root.deleteRecursively() }
    }
}
