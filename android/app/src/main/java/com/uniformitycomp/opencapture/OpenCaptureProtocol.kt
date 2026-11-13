package com.uniformitycomp.opencapture

import com.uniformitycomp.capture.DngSettings
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.UUID

object OpenCaptureProtocol {
    val jobId = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    val fileName = Regex("frame-[0-9]{4}\\.(dng|json)|average\\.f32|average-clips\\.u8")
    private val required = setOf("protocolVersion", "id", "format", "quality", "frameCount", "settleMilliseconds", "context")
    private val optional = setOf("operation", "exposureSeconds", "iso", "focusPosition", "whiteBalance", "averaging", "streaming")

    internal data class Averaging(val policy: String, val keepOriginals: Boolean)
    internal fun averaging(value: JSONObject): Averaging? {
        if (!value.has("averaging")) return null
        val a = value.getJSONObject("averaging")
        require(a.keys().asSequence().toSet() == setOf("mode", "emptyFramePolicy", "keepOriginals"))
        require(a.getString("mode") == BayerAverage.MODE && a.get("keepOriginals") is Boolean)
        require(a.getString("emptyFramePolicy") in setOf("relative", "illuminated", "dark"))
        require(value.isNull("operation") || value.getString("operation") == "capture") { "Averaging is capture-only" }
        return Averaging(a.getString("emptyFramePolicy"), a.get("keepOriginals") as Boolean)
    }

    fun request(value: JSONObject): DngSettings {
        val keys = value.keys().asSequence().toSet()
        require(keys.containsAll(required) && (keys - required - optional).isEmpty()) { "Missing or unknown capture fields" }
        require(integer(value, "protocolVersion", 1, 1) == 1)
        require(jobId.matches(value.getString("id"))) { "Invalid request ID" }
        require(value.getString("format") == "bayerRaw" && value.getString("quality") == "speed") { "This client supports Bayer RAW originals only" }
        val frames = integer(value, "frameCount", 1, 32)
        val operation = if (value.isNull("operation")) "capture" else value.getString("operation")
        require(operation in setOf("capture", "applyControls")) { "Unknown operation" }
        require(operation != "applyControls" || frames == 1)
        averaging(value)
        if (value.has("streaming")) {
            val stream=value.getJSONObject("streaming")
            require(stream.keys().asSequence().toSet()==setOf("mode") && stream.getString("mode")=="originals-v1" &&
                !value.has("averaging") && operation=="capture") { "Streaming requires original capture" }
        }
        val settle = integer(value, "settleMilliseconds", 0, 10000)
        val exposure = optionalNumber(value, "exposureSeconds", 1e-9, 30.0)
        val iso = optionalNumber(value, "iso", 1.0, 204800.0)
        require((exposure == null) == (iso == null)) { "Specify both exposure and ISO" }
        val focus = optionalNumber(value, "focusPosition", 0.0, 1.0)
        val wb = if (value.isNull("whiteBalance")) null else value.getJSONObject("whiteBalance").let { gains ->
            require(gains.keys().asSequence().toSet() == setOf("red", "green", "blue"))
            floatArrayOf(number(gains, "red", 1.0, 64.0).toFloat(), number(gains, "green", 1.0, 64.0).toFloat(), number(gains, "blue", 1.0, 64.0).toFloat())
        }
        val context = value.getJSONObject("context")
        require(context.keys().asSequence().count() <= 16)
        context.keys().forEach { key ->
            val text = context.get(key)
            require(text is String && key.toByteArray(Charsets.UTF_8).size <= 64 && text.toByteArray(Charsets.UTF_8).size <= 256)
        }
        return DngSettings(exposure?.let { (it * 1e9).toLong() }, iso?.toInt(), focus?.toFloat(), wb, settle.toLong())
    }

    fun localRequest(frames: Int, exposureMs: Double?, iso: Double?, focus: Double?) = JSONObject()
        .put("protocolVersion", 1).put("id", UUID.randomUUID().toString()).put("format", "bayerRaw")
        .put("quality", "speed").put("frameCount", frames).put("settleMilliseconds", 750).put("context", JSONObject())
        .put("exposureSeconds", exposureMs?.div(1000)).put("iso", iso).put("focusPosition", focus)

    private fun integer(value: JSONObject, key: String, low: Int, high: Int): Int {
        val result = number(value, key, low.toDouble(), high.toDouble())
        require(result == result.toInt().toDouble()) { "$key must be an integer" }
        return result.toInt()
    }
    private fun optionalNumber(value: JSONObject, key: String, low: Double, high: Double): Double? =
        if (value.isNull(key)) null else number(value, key, low, high)
    private fun number(value: JSONObject, key: String, low: Double, high: Double): Double {
        val number = value.get(key)
        require(number is Number && number.toDouble().isFinite() && number.toDouble() in low..high) { "Invalid $key" }
        return number.toDouble()
    }

    fun privateHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".local", ignoreCase = true)) return true
        val parts = host.split('.')
        if (parts.size != 4 || parts.any { !it.matches(Regex("0|[1-9][0-9]{0,2}")) || it.toInt() > 255 }) return false
        val a = parts.map { it.toInt() }
        return a[0] == 10 || a[0] == 127 || (a[0] == 192 && a[1] == 168) ||
            (a[0] == 172 && a[1] in 16..31) || (a[0] == 169 && a[1] == 254)
    }

    fun origin(input: String, token: String): URI {
        val text = input.trim()
        val explicitScheme = text.contains("://")
        val uri = URI(if (explicitScheme) text else "http://$text")
        val host = requireNotNull(uri.host) { "Enter a controller IP or hostname" }
        require(uri.scheme in setOf("http", "https") && uri.userInfo == null && uri.query == null && uri.fragment == null && uri.path in setOf("", "/")) { "Enter only the controller address" }
        require(uri.port == -1 || uri.port in 1..65535)
        require(token.isEmpty() || (token.length in 24..256 && token.all { it.code in 33..126 })) { "Pairing token must contain 24–256 printable characters" }
        require(privateHost(host) || (uri.scheme == "https" && token.isNotEmpty())) { "Public controllers require HTTPS and a token" }
        val port = if (uri.port >= 0) uri.port else if (!explicitScheme || uri.scheme == "http") 8765 else -1
        return URI(uri.scheme, null, host, port, null, null, null)
    }

    fun receipt(file: File): JSONObject {
        require(fileName.matches(file.name)) { "Invalid original filename" }
        require(file.length() in 1..(128L * 1024 * 1024)) { "Original exceeds file bounds" }
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val size = input.read(buffer); if (size < 0) break; hash.update(buffer, 0, size) }
        }
        return JSONObject().put("name", file.name).put("bytes", file.length())
            .put("sha256", hash.digest().joinToString("") { "%02x".format(it) })
            .put("mediaType", when (file.extension) { "dng" -> "image/dng"; "json" -> "application/json"; else -> "application/octet-stream" })
    }

    internal fun transferReceipts(manifest: JSONObject): List<JSONObject> {
        val aggregation = manifest.optJSONObject("aggregation")
        val retained = aggregation?.getJSONArray("retainedOriginalIndices")?.let { a ->
            (0 until a.length()).map { a.getInt(it) }.toSet()
        }
        val result = mutableListOf<JSONObject>()
        val frames = manifest.getJSONArray("frames")
        for (i in 0 until frames.length()) {
            val frame = frames.getJSONObject(i)
            for (key in listOf("file", "metadataFile", "previewFile")) {
                if (key == "file" && retained != null && i+1 !in retained) continue
                frame.optJSONObject(key)?.let { result.add(it) }
            }
        }
        aggregation?.getJSONObject("files")?.let { a -> a.keys().forEach { result.add(a.getJSONObject(it)) } }
        require(result.map { it.getString("name") }.let { names -> names.distinct().size == names.size && names.all(fileName::matches) })
        return result
    }

    fun saveJson(file: File, value: JSONObject) {
        val partial = File(file.parentFile, file.name + ".partial")
        partial.writeText(value.toString(), Charsets.UTF_8)
        check(partial.renameTo(file)) { "Cannot save capture metadata" }
    }
}
