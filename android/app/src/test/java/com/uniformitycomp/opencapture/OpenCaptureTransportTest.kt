package com.uniformitycomp.opencapture

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class OpenCaptureTransportTest {
    internal data class Request(val line: String, val headers: Map<String, String>, val body: ByteArray)
    internal data class Reply(val status: Int = 200, val body: String = "{}", val location: String? = null, val length: Int? = null)
    internal class Server(private val handler: (Request) -> Reply) : AutoCloseable {
        private val socket = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        val port = socket.localPort
        val failure = AtomicReference<Throwable?>()
        private val worker = thread {
            try {
                while (!socket.isClosed) socket.accept().use { client ->
                    client.soTimeout = 5000
                    val input = client.getInputStream().buffered()
                    fun line(): String {
                        val out = StringBuilder()
                        while (true) { val c = input.read(); if (c < 0 || c == 10) break; if (c != 13) out.append(c.toChar()); check(out.length < 8192) }
                        return out.toString()
                    }
                    val first = line()
                    val headers = linkedMapOf<String, String>()
                    while (true) { val text = line(); if (text.isEmpty()) break; headers[text.substringBefore(':').lowercase()] = text.substringAfter(':').trim() }
                    val body = ByteArray(headers["content-length"]?.toInt() ?: 0)
                    var offset = 0
                    while (offset < body.size) { val n = input.read(body, offset, body.size - offset); check(n > 0); offset += n }
                    val response = try { handler(Request(first, headers, body)) } catch (e: Throwable) { failure.set(e); Reply(500) }
                    val bytes = response.body.toByteArray()
                    val head = "HTTP/1.1 ${response.status} Response\r\nContent-Type: application/json\r\nContent-Length: ${response.length ?: bytes.size}\r\nConnection: close\r\n" +
                        (response.location?.let { "Location: $it\r\n" } ?: "") + "\r\n"
                    client.getOutputStream().write(head.toByteArray()); client.getOutputStream().write(bytes)
                }
            } catch (e: Throwable) { if (!socket.isClosed) failure.set(e) }
        }
        override fun close() { socket.close(); worker.join(2000); failure.get()?.let { throw AssertionError(it) } }
    }

    @Test fun originalUploadUsesLeaseHashesAndVerifiesReceipt() {
        val folder = Files.createTempDirectory("oc-upload-test").toFile()
        val file = folder.resolve("frame-0001.dng").apply { writeText("original bytes") }
        val receipt = OpenCaptureProtocol.receipt(file)
        val requests = AtomicInteger()
        val mismatch = AtomicBoolean(false)
        val server = Server { request ->
            assertTrue(request.line.startsWith("PUT /oc/v1/jobs/00000000-0000-0000-0000-000000000001/files/frame-0001.dng "))
            assertEquals("Bearer " + "t".repeat(24), request.headers["authorization"])
            assertEquals("lease", request.headers["x-opencapture-lease"])
            assertEquals(receipt.getString("sha256"), request.headers["x-content-sha256"])
            assertArrayEquals(file.readBytes(), request.body)
            requests.incrementAndGet()
            Reply(body = JSONObject(receipt.toString()).apply { if (mismatch.get()) put("sha256", "0".repeat(64)) }.toString())
        }
        val client = OpenCaptureTransport("127.0.0.1:${server.port}", "t".repeat(24))
        try {
            client.upload("00000000-0000-0000-0000-000000000001", "lease", file, receipt, AtomicBoolean(false))
            mismatch.set(true)
            try { client.upload("00000000-0000-0000-0000-000000000001", "lease", file, receipt, AtomicBoolean(false)); fail() }
            catch (_: IllegalArgumentException) { }
            assertEquals(2, requests.get())
        } finally { client.close(); server.close(); folder.deleteRecursively() }
    }

    @Test fun redirectNeverReceivesPairingCredentials() {
        val forwarded = AtomicBoolean(false)
        val server = Server { request ->
            if (request.line.startsWith("GET /oc/v1/lease ")) Reply(302, location = "/leak")
            else { forwarded.set(true); Reply() }
        }
        val client = OpenCaptureTransport("127.0.0.1:${server.port}", "t".repeat(24))
        try {
            try { client.json("GET", "/oc/v1/lease"); fail() } catch (_: java.io.IOException) { }
            assertFalse(forwarded.get())
            client.close()
            try { client.json("GET", "/oc/v1/lease"); fail() } catch (_: IllegalStateException) { }
        } finally { client.close(); server.close() }
    }
}
