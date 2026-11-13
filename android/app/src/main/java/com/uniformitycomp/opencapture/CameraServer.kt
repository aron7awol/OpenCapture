// SPDX-License-Identifier: GPL-3.0-only
package com.uniformitycomp.opencapture

import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

class CameraServer(
    private val address: String,
    private val session: CameraServerSession,
    private val info: () -> JSONObject,
    private val select: (String) -> JSONObject,
    private val preview: () -> ByteArray?,
) : NanoHTTPD(address, 8767) {
    init {
        val clients = ConcurrentHashMap.newKeySet<ClientHandler>()
        val pool = Executors.newCachedThreadPool()
        val slots = Semaphore(8)
        setAsyncRunner(object : AsyncRunner {
            override fun exec(client: ClientHandler) {
                if (!slots.tryAcquire()) { client.close(); return }
                clients.add(client)
                try { pool.execute(client) }
                catch (_: Exception) { client.close(); closed(client) }
            }
            override fun closed(client: ClientHandler) { if (clients.remove(client)) slots.release() }
            override fun closeAll() { clients.toList().forEach { it.close() }; pool.shutdownNow() }
        })
    }
    override fun serve(request: IHTTPSession): Response {
        val response = try {
            require(request.headers["host"] == "$address:8767") { "Invalid Host" }
            require(request.headers["origin"] == null && request.headers["sec-fetch-site"] == null) { "Browser requests are not supported" }
            require(privateIPv4(request.remoteIpAddress)) { "Trusted LAN only" }
            require(request.headers["transfer-encoding"] == null && request.queryParameterString.isNullOrEmpty()) { "Unsupported request encoding" }
            if (request.method !in listOf(Method.GET, Method.POST)) throw ServerFault(405, "Method not allowed")
            val body = if (request.method == Method.POST) {
                require(request.headers["content-type"]?.substringBefore(';') == "application/json") { "Expected JSON" }
                val length = request.headers["content-length"]?.toIntOrNull() ?: throw ServerFault(411, "Content-Length required")
                if (length !in 2..16384) throw ServerFault(413, "Request too large")
                val bytes = ByteArray(length)
                var offset = 0
                while (offset < length) {
                    val count = request.inputStream.read(bytes, offset, length - offset)
                    require(count > 0) { "Incomplete request" }; offset += count
                }
                JSONObject(bytes.toString(Charsets.UTF_8))
            } else {
                require((request.headers["content-length"] ?: "0") == "0") { "Unexpected request body" }
                JSONObject()
            }
            if (request.method == Method.POST && request.uri == "/oc/v1/camera") {
                require(body.keys().asSequence().toSet() == setOf("cameraID")) { "Invalid camera request" }
                json(200, session.select(request.headers["x-opencapture-session"]) { select(body.getString("cameraID")) })
            } else synchronized(session) {
                val path = request.uri
                when {
                    request.method == Method.GET && path == "/oc/v1/info" -> json(200, info())
                    request.method == Method.POST && path == "/oc/v1/connect" -> json(200, session.connect(body))
                    else -> {
                        session.authorize(request.headers["x-opencapture-session"])
                        when {
                            request.method == Method.GET && path == "/oc/v1/state" -> json(200, info().put("session", session.state()))
                            request.method == Method.POST && path == "/oc/v1/disconnect" -> { session.end(); json(200, JSONObject().put("protocolVersion", 1)) }
                            request.method == Method.POST && path == "/oc/v1/jobs" -> json(202, session.submit(body))
                            request.method == Method.GET && path == "/oc/v1/preview.jpg" -> {
                                val bytes = preview() ?: throw ServerFault(404, "No preview")
                                newFixedLengthResponse(Response.Status.OK, "image/jpeg", bytes.inputStream(), bytes.size.toLong())
                            }
                            else -> jobRoute(request, body)
                        }
                    }
                }
            }
        } catch (error: Exception) {
            json(if (error is ServerFault) error.status else 400, JSONObject().put("error", (error.message ?: "Request failed").take(256)))
        }
        response.addHeader("Connection", "close")
        response.addHeader("Cache-Control", "no-store")
        response.addHeader("X-Content-Type-Options", "nosniff")
        response.setKeepAlive(false)
        return response
    }
    private fun jobRoute(request: IHTTPSession, body: JSONObject): Response {
        val parts = request.uri.split('/')
        if (parts.size !in 5..7 || parts.take(4) != listOf("", "oc", "v1", "jobs") || !OpenCaptureProtocol.jobId.matches(parts[4]))
            throw ServerFault(404, "Unknown endpoint")
        val id = parts[4]
        if (request.method == Method.GET) {
            if (parts.size == 5) return json(200, session.jobState(id))
            if (parts.size == 6 && parts[5] == "result") {
                val bytes = session.result(id)
                return newFixedLengthResponse(Response.Status.OK, "application/json", bytes.inputStream(), bytes.size.toLong())
            }
            if (parts.size == 7 && parts[5] == "files") {
                val file = session.file(id, parts[6])
                return newFixedLengthResponse(Response.Status.OK, "application/octet-stream", file.inputStream(), file.length())
            }
        } else if (parts.size == 6) {
            if (parts[5] == "verdict") return json(200,session.verdict(id,body))
            if (parts[5] == "ack") {
                require(body.keys().asSequence().toSet() == setOf("resultSha256")) { "Invalid receipt" }
                return json(200, session.ack(id, body.getString("resultSha256")))
            }
            if (parts[5] == "cancel") return json(200, session.cancel(id))
        }
        throw ServerFault(404, "Unknown endpoint")
    }
    private fun json(code: Int, value: JSONObject): Response = newFixedLengthResponse(
        object : Response.IStatus {
            override fun getRequestStatus() = code
            override fun getDescription() = "$code ${if (code < 300) "OK" else "Error"}"
        }, "application/json", value.toString())
    companion object {
        fun privateIPv4(text: String): Boolean {
            val p = text.split('.').map { it.toIntOrNull() ?: return false }
            return p.size == 4 && p.all { it in 0..255 } &&
                (p[0] == 10 || p[0] == 127 || p[0] == 192 && p[1] == 168 ||
                 p[0] == 172 && p[1] in 16..31 || p[0] == 169 && p[1] == 254)
        }
    }
}
