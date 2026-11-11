package com.uniformitycomp.opencapture

import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.Comparator

/** Image deletion never deletes the small ID ledger used to reject replayed jobs. */
class CaptureArchives(private val root: File, private val exports: File? = null) {
    private val ledger = File(root.parentFile, "capture-history")
    private fun directory(id: String): File {
        require(OpenCaptureProtocol.jobId.matches(id)) { "Invalid capture ID" }
        val directory = File(root, id)
        require(!Files.isSymbolicLink(directory.toPath()) && directory.canonicalFile.parentFile == root.canonicalFile) { "Capture path is outside the archive" }
        return directory
    }
    fun create(id: String): File {
        val directory = directory(id)
        require(!directory.exists() && !File(ledger, id).exists()) { "Capture ID was already used; originals cannot be replayed" }
        check(root.isDirectory || root.mkdirs()) { "Cannot create capture archive" }
        check(ledger.isDirectory || ledger.mkdirs()) { "Cannot create capture history" }
        check(File(ledger, id).createNewFile()) { "Capture ID was already used" }
        check(directory.mkdir()) { "Cannot create capture directory" }
        return directory
    }
    fun delete(id: String) {
        val directory = directory(id)
        require(directory.isDirectory) { "Capture no longer exists" }
        check(ledger.isDirectory || ledger.mkdirs()) { "Cannot preserve capture history" }
        val marker = File(ledger, id)
        check(marker.exists() || marker.createNewFile()) { "Cannot preserve capture history" }
        exports?.let { Files.deleteIfExists(File(it, "$id.zip").toPath()) }
        // Files.walk does not follow links. A link inside the archive is removed,
        // never traversed into another capture or another application directory.
        Files.walk(directory.toPath()).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
    }
    fun completeUpload(id: String, confirmation: JSONObject, deleteAfter: Boolean) {
        confirmUpload(id, confirmation)
        val directory = directory(id)
        require(File(directory, "manifest.json").isFile) { "Capture manifest is missing; phone files retained" }
        OpenCaptureProtocol.saveJson(File(directory, "uploaded.json"), confirmation)
        if (deleteAfter) delete(id)
    }
    companion object {
        fun confirmUpload(id: String, confirmation: JSONObject) {
            require(OpenCaptureProtocol.jobId.matches(id))
            val protocol = confirmation.get("protocolVersion")
            require(protocol is Number && protocol.toDouble() == 1.0 &&
                confirmation.getString("id") == id && confirmation.getString("status") == "complete") {
                "Windows did not confirm this complete upload; phone originals retained"
            }
        }
    }
}
