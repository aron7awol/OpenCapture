// SPDX-License-Identifier: GPL-3.0-only
package com.uniformitycomp.opencapture

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Small atomic operation snapshots, separate from disposable original-photo staging. */
class PersistentDiagnostics(private val root: File, private val process: JSONObject) {
    private var recordID = UUID.randomUUID().toString()
    private var operationKey: String? = null
    @Volatile var storageError: String? = null
        private set

    @Synchronized fun save(snapshot: JSONObject) {
        var temporary: File? = null
        try {
            val key = snapshot.optJSONObject("request")?.optString("id", "") ?: ""
            if (operationKey != null && operationKey != key) recordID = UUID.randomUUID().toString()
            operationKey = key
            val record = JSONObject().put("schemaVersion", 1).put("recordID", recordID)
                .put("process", JSONObject(process.toString())).put("updatedUnixMs", System.currentTimeMillis())
                .put("cameraDiagnostics", snapshot)
            val bytes = record.toString().toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_RECORD_BYTES) { "Diagnostic snapshot too large" }
            check(root.isDirectory || root.mkdirs()) { "Diagnostic directory unavailable" }
            root.listFiles().orEmpty().filter { it.extension == "tmp" }.forEach { it.delete() }
            val target = File(root, "$recordID.json")
            temporary = File(root, "$recordID.tmp")
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING)
            // Always keep the just-written record, even if the wall clock moves backwards.
            files().filter { it != target }.sortedByDescending { it.lastModified() }
                .drop(MAX_RECORDS - 1).forEach { it.delete() }
            storageError = null
        } catch (error: Exception) {
            // No paths or exception messages in a network-visible diagnostic failure.
            storageError = error.javaClass.simpleName
        } finally {
            try { temporary?.delete() } catch (_: Exception) { /* Preserve the original storage error. */ }
        }
    }

    @Synchronized fun bundle(deviceID: String, exitInfo: JSONObject): JSONObject {
        val records = JSONArray()
        files().sortedByDescending { it.lastModified() }.take(MAX_RECORDS).forEach { file ->
            try {
                if (file.length() in 1..MAX_RECORD_BYTES.toLong()) {
                    val record = JSONObject(file.readText())
                    if (record.optInt("schemaVersion", 0) == 1 && record.optString("recordID", "") == file.nameWithoutExtension)
                        records.put(record)
                }
            } catch (_: Exception) { /* A damaged record must not hide the other evidence. */ }
        }
        return JSONObject().put("schemaVersion", 1).put("mode", MODE).put("deviceID", deviceID)
            .put("currentProcessID", process.getString("id")).put("records", records)
            .put("exitInfo", exitInfo).put("storageError", storageError ?: JSONObject.NULL)
    }

    private fun files() = root.listFiles().orEmpty().filter {
        it.isFile && it.extension == "json" && ID.matches(it.nameWithoutExtension)
    }

    companion object {
        const val MODE = "persistent-camera-v1"
        const val MAX_RECORDS = 12
        const val MAX_RECORD_BYTES = 96 * 1024
        private val ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
