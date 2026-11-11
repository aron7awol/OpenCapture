package com.uniformitycomp.opencapture

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CaptureArchivesTest {
    private val id = "00000000-0000-4000-8000-000000000001"
    @Test fun deletionPreservesReplayProtectionAndOtherArchives() {
        val base = Files.createTempDirectory("opencapture-archive-test").toFile()
        try {
            val root = File(base, "captures")
            val store = CaptureArchives(root)
            val directory = store.create(id)
            File(directory, "frame-0001.dng").writeText("original")
            val second = store.create("00000000-0000-4000-8000-000000000002")
            store.delete(id)
            assertFalse(directory.exists()); assertTrue(second.isDirectory)
            try { store.create(id); fail("Deleted capture must not be replayed") } catch (_: IllegalArgumentException) { }
            try { store.delete("../captures"); fail("Path traversal") } catch (_: IllegalArgumentException) { }
            assertTrue(second.isDirectory)
        } finally { base.deleteRecursively() }
    }
    @Test fun oldCapturesAlsoGainReplayProtectionWhenDeleted() {
        val base = Files.createTempDirectory("opencapture-old-archive-test").toFile()
        try {
            val root = File(base, "captures")
            File(root, id).mkdirs()
            CaptureArchives(root).delete(id)
            try { CaptureArchives(root).create(id); fail("Old deleted job must not replay") } catch (_: IllegalArgumentException) { }
        } finally { base.deleteRecursively() }
    }
    @Test fun cleanupRequiresMatchingCompletedJobAcknowledgement() {
        fun ack() = JSONObject().put("protocolVersion", 1).put("id", id).put("status", "complete")
        CaptureArchives.confirmUpload(id, ack())
        for (invalid in listOf(ack().put("status", "running"), ack().put("id", "different"), ack().put("protocolVersion", 2), JSONObject())) {
            try { CaptureArchives.confirmUpload(id, invalid); fail("Unconfirmed originals must stay") } catch (_: Exception) { }
        }
    }
    @Test fun failedUnconfirmedAndOptedOutCapturesStayButConfirmedCleanupRemovesCachedExport() {
        val base = Files.createTempDirectory("opencapture-retention-test").toFile()
        try {
            val exports = File(base, "exports").apply { mkdir() }
            val store = CaptureArchives(File(base, "captures"), exports)
            val directory = store.create(id)
            val original = File(directory, "frame-0001.dng").apply { writeText("original") }
            File(directory, "manifest.json").writeText("{}")
            val exported = File(exports, "$id.zip").apply { writeText("export") }
            try { store.completeUpload(id, JSONObject(), true); fail("Missing confirmation") } catch (_: Exception) { }
            assertTrue(original.isFile); assertTrue(exported.isFile)
            val confirmation = JSONObject().put("protocolVersion", 1).put("id", id).put("status", "complete")
            store.completeUpload(id, confirmation, false)
            assertTrue(original.isFile); assertTrue(File(directory, "uploaded.json").isFile)
            store.completeUpload(id, confirmation, true)
            assertFalse(directory.exists()); assertFalse(exported.exists())
            try { store.create(id); fail("Uploaded/deleted job cannot replay") } catch (_: IllegalArgumentException) { }
        } finally { base.deleteRecursively() }
    }
}
