package com.uniformitycomp.opencapture

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class OpenCaptureTransport(address: String, private val token: String) {
    val origin = OpenCaptureProtocol.origin(address, token)
    private val connections = ConcurrentHashMap.newKeySet<HttpURLConnection>()
    @Volatile private var closed = false

    fun close() { closed = true; connections.toList().forEach { it.disconnect() } }

    fun json(method: String, path: String, value: JSONObject? = null, lease: String? = null,
             cancel: AtomicBoolean? = null): JSONObject = JSONObject(String(
        send(method, path, "application/json", value?.toString()?.toByteArray(Charsets.UTF_8), null, null, lease, cancel), Charsets.UTF_8))

    fun preview(bytes: ByteArray) { require(bytes.size <= 2 * 1024 * 1024); send("POST", "/oc/v1/preview", "image/jpeg", bytes, null, null, null, null) }

    fun upload(id: String, lease: String, file: File, receipt: JSONObject, cancel: AtomicBoolean) {
        require(OpenCaptureProtocol.jobId.matches(id) && OpenCaptureProtocol.fileName.matches(file.name))
        require(receipt.getString("name") == file.name && receipt.get("bytes") == file.length())
        val response = JSONObject(String(send("PUT", "/oc/v1/jobs/$id/files/${file.name}", receipt.getString("mediaType"),
            null, file, receipt.getString("sha256"), lease, cancel), Charsets.UTF_8))
        val actual = response.optJSONObject("file") ?: response
        for (key in listOf("name", "bytes", "sha256", "mediaType")) require(actual.get(key).toString() == receipt.get(key).toString()) { "Upload receipt mismatch" }
    }

    private fun send(method: String, path: String, type: String, bytes: ByteArray?, file: File?, hash: String?,
                     lease: String?, cancel: AtomicBoolean?): ByteArray {
        require(path.startsWith("/oc/v1/") && !path.contains("..") && !path.contains('?') && !path.contains('#'))
        check(!closed && cancel?.get() != true) { "Connection cancelled" }
        val connection = origin.resolve(path).toURL().openConnection() as HttpURLConnection
        connections.add(connection)
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10000
            connection.readTimeout = 45000
            connection.requestMethod = method
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            if (token.isNotEmpty()) connection.setRequestProperty("Authorization", "Bearer $token")
            if (lease != null) {
                require(lease.length in 1..128 && lease.all { it.code in 33..126 })
                connection.setRequestProperty("X-OpenCapture-Lease", lease)
            }
            if (bytes != null || file != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", type)
                if (hash != null) connection.setRequestProperty("X-Content-SHA256", hash)
                connection.setFixedLengthStreamingMode(file?.length() ?: bytes!!.size.toLong())
                connection.outputStream.use { output ->
                    if (file == null) output.write(bytes!!) else file.inputStream().use { input ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            if (closed || cancel?.get() == true) throw InterruptedException("Upload cancelled")
                            val size = input.read(buffer); if (size < 0) break
                            output.write(buffer, 0, size)
                        }
                    }
                }
            }
            val status = connection.responseCode
            if (status !in 200..299) throw ControllerHttpException(status)
            return connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                val output = java.io.ByteArrayOutputStream()
                while (output.size() <= 1024 * 1024) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                val result = output.toByteArray()
                require(result.size <= 1024 * 1024) { "Controller response is too large" }
                result
            }
        } finally { connections.remove(connection); connection.disconnect() }
    }
}
