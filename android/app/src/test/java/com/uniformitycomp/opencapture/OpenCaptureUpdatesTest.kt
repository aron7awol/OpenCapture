package com.uniformitycomp.opencapture

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException
import com.uniformitycomp.opencapture.OpenCaptureTransportTest.Server
import com.uniformitycomp.opencapture.OpenCaptureTransportTest.Reply

class OpenCaptureUpdatesTest {
    private val payload = "synthetic APK bytes for streaming tests"
    private val download = OpenCaptureUpdates.downloadUrl("0.2.1", 3L)
    private fun manifest() = JSONObject().put("schemaVersion", 1)
        .put("packageName", OpenCaptureUpdates.PACKAGE).put("minSdk", 28)
        .put("downloadUrl", download).put("size", payload.length).put("versionCode", 3).put("versionName", "0.2.1")
        .put("sha256", MessageDigest.getInstance("SHA-256").digest(payload.toByteArray()).joinToString("") { "%02x".format(it) })

    // The transport is injected; production URL validation still runs on the real HTTPS URLs.
    private fun client(server: Server, visited: MutableList<URI> = mutableListOf()) = OpenCaptureUpdates { url ->
        visited.add(url)
        URL("http://127.0.0.1:" + server.port + url.rawPath + (url.rawQuery?.let { "?" + it } ?: ""))
            .openConnection() as HttpURLConnection
    }

    @Test fun feedRejectsWrongPackageURLsVersionsAndBounds() {
        assertEquals(3L, OpenCaptureUpdates.parse(manifest()).apk.versionCode)
        for ((key, value) in listOf("schemaVersion" to 2, "schemaVersion" to 1.5,
            "versionCode" to 0, "versionCode" to 3.5, "versionCode" to 4, "versionName" to "../0.2.1",
            "size" to 0, "size" to 134217729L, "minSdk" to 27, "minSdk" to 28.5,
            "packageName" to "com.uniformitycomp.capture", "downloadUrl" to "https://evil.invalid/app.apk",
            "downloadUrl" to download.replace("aron7awol", "someone"), "downloadUrl" to download + "?other=1",
            "downloadUrl" to download.replace("https:", "http:"), "sha256" to "x")) {
            try { OpenCaptureUpdates.parse(manifest().put(key,value)); fail("Accepted $key=$value") } catch (_: IllegalArgumentException) {}
        }
        for (url in listOf("http://github.com/aron7awol/OpenCapture/releases/download/tag/a",
            "https://github.com.evil.invalid/a", "https://github.com/someone/OpenCapture/releases/download/tag/a",
            "https://evil.invalid/a", "https://release-assets.githubusercontent.com.evil.invalid/a",
            "https://user@release-assets.githubusercontent.com/a", "https://objects.githubusercontent.com:8443/a",
            "https://github.com/aron7awol/OpenCapture/releases/download/../a",
            "https://github.com/aron7awol/OpenCapture/releases/download/%2e%2e/a")) {
            assertFalse(url, OpenCaptureUpdates.isAllowedRedirect(URI(url)))
        }
    }

    @Test fun feedAndAPKFollowGitHubCDNRedirectsWithoutPairingCredentials() {
        val count = AtomicInteger()
        val visited = mutableListOf<URI>()
        Server { request ->
            assertNull(request.headers["authorization"])
            assertEquals("OpenCapture-Android", request.headers["user-agent"])
            count.incrementAndGet()
            when {
                request.line.startsWith("GET " + URI(OpenCaptureUpdates.FEED_URL).rawPath + " ") ->
                    Reply(302, location="https://release-assets.githubusercontent.com/feed?signature=fixture")
                request.line.startsWith("GET /feed?signature=fixture ") -> Reply(body=manifest().toString())
                request.line.startsWith("GET " + URI(download).rawPath + " ") ->
                    Reply(302, location="https://objects.githubusercontent.com/apk?signature=fixture")
                request.line.startsWith("GET /apk?signature=fixture ") -> Reply(body=payload)
                else -> throw AssertionError("Unexpected update URL")
            }
        }.use { server ->
            val client = client(server, visited)
            val folder = Files.createTempDirectory("oc-update-test").toFile()
            try {
                val update = client.check(); val file = folder.resolve("update.apk"); val progress = mutableListOf<Int>()
                client.download(update,file,AtomicBoolean(false)) { progress.add(it) }
                assertEquals(payload,file.readText()); assertEquals(100,progress.last()); assertEquals(4,count.get())
                assertEquals(listOf("github.com", "release-assets.githubusercontent.com", "github.com", "objects.githubusercontent.com"),
                    visited.map { it.host })
                assertFalse(folder.resolve("update.apk.partial").exists())
            } finally { client.close(); folder.deleteRecursively() }
        }
    }

    @Test fun corruptTruncatedWrongLengthAndCancelledDownloadsNeverBecomeInstallable() {
        val replies = listOf(Reply(body=payload.replace('A','B')), Reply(body=payload.dropLast(2),length=payload.length), Reply(body=payload+"extra"))
        for (reply in replies) Server { reply }.use { server ->
            val client = client(server)
            val folder = Files.createTempDirectory("oc-bad-update-test").toFile()
            try {
                try { client.download(OpenCaptureUpdates.parse(manifest()),folder.resolve("update.apk"),AtomicBoolean(false)) {}; fail() } catch (_: Exception) {}
                assertTrue(folder.listFiles().orEmpty().isEmpty())
            } finally { client.close(); folder.deleteRecursively() }
        }
        Server { Reply(body=payload) }.use { server ->
            val client = client(server)
            val folder = Files.createTempDirectory("oc-cancel-update-test").toFile()
            try {
                val cancelled = AtomicBoolean(false)
                try { client.download(OpenCaptureUpdates.parse(manifest()),folder.resolve("update.apk"),cancelled) { cancelled.set(true) }; fail() } catch (_: IllegalStateException) {}
                assertTrue(folder.listFiles().orEmpty().isEmpty())
            } finally { client.close(); folder.deleteRecursively() }
        }
    }

    @Test fun forbiddenRedirectsAreRejectedBeforeConnectingAndLoopsAreBounded() {
        for (location in listOf("http://release-assets.githubusercontent.com/apk", "https://evil.invalid/apk",
            "https://github.com/someone/OpenCapture/releases/download/a/apk", "https://release-assets.githubusercontent.com.evil.invalid/apk")) {
            val visited = mutableListOf<URI>()
            Server { Reply(302, location=location) }.use { server ->
                val client = client(server, visited)
                try {
                    try { client.check(); fail() } catch (_: IllegalArgumentException) {}
                    assertEquals(1,visited.size)
                } finally { client.close() }
            }
        }
        val visited = mutableListOf<URI>()
        Server { Reply(302, location=OpenCaptureUpdates.FEED_URL) }.use { server ->
            val client = client(server, visited)
            try {
                try { client.check(); fail() } catch (_: IllegalArgumentException) {}
                assertEquals(6,visited.size)
            } finally { client.close() }
        }
    }

    @Test fun missingAndOversizedFeedsFailClearlyAndClosedClientsCannotBeReused() {
        Server { Reply(404) }.use { server ->
            val client = client(server)
            try {
                try { client.check(); fail() } catch (e: java.io.IOException) { assertTrue(e.message!!.contains("not been published")) }
                client.close()
                try { client.check(); fail() } catch (_: IllegalStateException) {}
            } finally { client.close() }
        }
        Server { Reply(body=" ".repeat(65537)) }.use { server ->
            val client = client(server)
            try { try { client.check(); fail() } catch (_: IllegalArgumentException) {} }
            finally { client.close() }
        }
    }

    @Test fun transientConnectionFailureRetriesTheFeedOnce() {
        for (failure in listOf(UnknownHostException("fixture"), SocketTimeoutException("fixture"), EOFException("fixture"))) {
            val attempts = AtomicInteger()
            Server { Reply(body=manifest().toString()) }.use { server ->
                val client = OpenCaptureUpdates { url ->
                    if (attempts.incrementAndGet() == 1) throw failure
                    URL("http://127.0.0.1:${server.port}${url.rawPath}").openConnection() as HttpURLConnection
                }
                try { assertEquals(3L, client.check().apk.versionCode); assertEquals(2,attempts.get()) }
                finally { client.close() }
            }
        }
    }

    @Test fun transientHttpFailuresAreBoundedButAuthorizationAndMissingAssetsAreNotRetried() {
        for (status in listOf(408,429,500,502,503,504,401,403,404)) {
            val attempts = AtomicInteger()
            Server { attempts.incrementAndGet(); Reply(status) }.use { server ->
                val client = client(server)
                try {
                    try { client.check(); fail("Accepted HTTP $status") } catch (_: IOException) {}
                    assertEquals(if (status in listOf(401,403,404)) 1 else 2, attempts.get())
                } finally { client.close() }
            }
        }
    }

    @Test fun incompleteFeedRetriesWithoutAcceptingPartialJson() {
        val attempts = AtomicInteger()
        val body = manifest().toString()
        Server { if (attempts.incrementAndGet() == 1) Reply(body=body,length=body.length+8) else Reply(body=body) }.use { server ->
            val client = client(server)
            try { assertEquals(3L,client.check().apk.versionCode); assertEquals(2,attempts.get()) }
            finally { client.close() }
        }
    }

    @Test fun tlsErrorsAndCancellationNeverStartAnotherConnection() {
        val attempts = AtomicInteger()
        val tls = OpenCaptureUpdates { attempts.incrementAndGet(); throw SSLHandshakeException("fixture") }
        try {
            try { tls.check(); fail() } catch (_: SSLHandshakeException) {}
            assertEquals(1, attempts.get())
        } finally { tls.close() }
        attempts.set(0)
        lateinit var cancelled: OpenCaptureUpdates
        cancelled = OpenCaptureUpdates { attempts.incrementAndGet(); cancelled.close(); throw IOException("cancelled") }
        try {
            try { cancelled.check(); fail() } catch (_: IOException) {}
            assertEquals(1, attempts.get())
        } finally { cancelled.close() }
    }

    @Test fun errorsIdentifyDnsTimeoutAndTlsWithoutEchoingSignedUrls() {
        val privateDetail = "https://release-assets.githubusercontent.com/file?secret=fixture"
        assertTrue(OpenCaptureUpdates.failureMessage(UnknownHostException(privateDetail)).contains("DNS"))
        assertTrue(OpenCaptureUpdates.failureMessage(SocketTimeoutException(privateDetail)).contains("timed out"))
        assertTrue(OpenCaptureUpdates.failureMessage(SSLHandshakeException(privateDetail)).contains("Certificate verification was not bypassed"))
        for (error in listOf(UnknownHostException(privateDetail), SocketTimeoutException(privateDetail),
            SSLHandshakeException(privateDetail), IOException(privateDetail))) {
            assertFalse(OpenCaptureUpdates.failureMessage(error).contains(privateDetail))
        }
    }
}
