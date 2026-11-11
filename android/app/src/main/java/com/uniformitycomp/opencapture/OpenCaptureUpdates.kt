package com.uniformitycomp.opencapture

import com.uniformitycomp.capture.ApkUpdateManifest
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** GitHub release updates are independent of camera pairing and never receive its credentials. */
class OpenCaptureUpdates internal constructor(private val connect: (URI) -> HttpURLConnection) {
    constructor() : this({ it.toURL().openConnection() as HttpURLConnection })

    data class Release(val apk: ApkUpdateManifest, val downloadUrl: URI, val minSdk: Int)
    @Volatile private var active: HttpURLConnection? = null
    @Volatile private var closed = false

    fun close() { closed = true; active?.disconnect() }

    private fun open(initial: URI, label: String): HttpURLConnection {
        require(isRepositoryAsset(initial)) { "Unexpected GitHub update URL" }
        var url = initial
        for (hop in 0..5) {
            check(!closed) { "Update cancelled" }
            val connection = connect(url)
            active = connection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10000
                connection.readTimeout = 30000
                connection.useCaches = false
                connection.setRequestProperty("User-Agent", "OpenCapture-Android")
                if (closed) error("Update cancelled")
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    require(hop < 5) { "Too many GitHub update redirects" }
                    val location = connection.getHeaderField("Location") ?: throw IOException("Missing update redirect")
                    val next = url.resolve(location)
                    require(isAllowedRedirect(next)) { "Update redirect is outside GitHub's HTTPS download hosts" }
                    connection.disconnect(); active = null
                    url = next
                    continue
                }
                if (status != 200) throw IOException(when (status) {
                    404 -> if (label == "feed") "The Android GitHub release feed has not been published yet."
                        else "The Android APK is missing from its GitHub release."
                    401, 403 -> "GitHub refused the update request. Try again later."
                    else -> "GitHub update $label returned HTTP $status"
                })
                return connection
            } catch (error: Exception) {
                connection.disconnect(); active = null
                throw error
            }
        }
        error("Too many GitHub update redirects")
    }

    fun check(): Release {
        val connection = open(URI(FEED_URL), "feed")
        try {
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    check(!closed) { "Update cancelled" }
                    val n = input.read(buffer); if (n < 0) break
                    require(output.size() + n <= 65536) { "Update feed is too large" }
                    output.write(buffer, 0, n)
                }
                output.toByteArray()
            }
            check(!closed) { "Update cancelled" }
            return parse(JSONObject(String(bytes, Charsets.UTF_8)))
        } finally { connection.disconnect(); active = null }
    }

    fun download(release: Release, destination: File, cancelled: AtomicBoolean, progress: (Int) -> Unit) {
        val manifest = release.apk
        require(manifest.packageName == PACKAGE && manifest.sizeBytes in 1..MAX_BYTES)
        require(release.downloadUrl.toString() == downloadUrl(requireNotNull(manifest.versionName), manifest.versionCode))
        check(!cancelled.get()) { "Update cancelled" }
        val partial = File(destination.parentFile, destination.name + ".partial")
        val connection = open(release.downloadUrl, "APK")
        try {
            val length = connection.contentLengthLong
            require(length < 0 || length == manifest.sizeBytes) { "Update length does not match its feed" }
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            var reported = -1
            connection.inputStream.use { input -> partial.outputStream().use { output ->
                val buffer = ByteArray(65536)
                while (true) {
                    check(!closed && !cancelled.get()) { "Update cancelled" }
                    val n = input.read(buffer); if (n < 0) break
                    total += n
                    require(total <= manifest.sizeBytes) { "Update exceeds its advertised size" }
                    output.write(buffer, 0, n); digest.update(buffer, 0, n)
                    val percent = (total * 100 / manifest.sizeBytes).toInt()
                    if (percent != reported) { reported = percent; progress(percent) }
                }
            } }
            require(total == manifest.sizeBytes) { "Update download was incomplete" }
            val sha = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            require(sha == manifest.sha256) { "Update SHA-256 verification failed" }
            check(!closed && !cancelled.get()) { "Update cancelled" }
            check(partial.renameTo(destination)) { "Cannot finalize downloaded update" }
        } finally { partial.delete(); connection.disconnect(); active = null }
    }

    companion object {
        const val PACKAGE = "com.uniformitycomp.opencapture"
        const val ASSET = "OpenCapture-android.apk"
        const val RELEASES = "https://github.com/aron7awol/OpenCapture/releases/download/"
        const val FEED_URL = RELEASES + "android-updates/android.json"
        const val MAX_BYTES = 128L * 1024 * 1024

        fun downloadUrl(version: String, build: Long): String {
            require(version.length <= 80 && version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) { "Invalid Android release version" }
            require(build in 1..2_100_000_000)
            return RELEASES + "android-v$version-b$build/" + ASSET
        }

        private fun isHTTPS(url: URI) = url.scheme == "https" && url.rawUserInfo == null &&
            url.port in listOf(-1, 443) && url.rawFragment == null

        private fun isRepositoryAsset(url: URI): Boolean = isHTTPS(url) && url.host == "github.com" &&
            url.rawQuery == null && url.rawPath.startsWith("/aron7awol/OpenCapture/releases/download/") &&
            !url.rawPath.contains('%') && !url.rawPath.split('/').contains("..")

        internal fun isAllowedRedirect(url: URI): Boolean = isHTTPS(url) && (isRepositoryAsset(url) ||
            url.host in setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com"))

        fun parse(json: JSONObject): Release {
            fun integer(name: String, min: Long, max: Long): Long {
                val value = json.get(name)
                require(value is Number && value.toDouble().isFinite() && value.toDouble() == value.toLong().toDouble() &&
                    value.toLong() in min..max) { "Invalid update " + name }
                return value.toLong()
            }
            require(integer("schemaVersion", 1, 1) == 1L)
            require(json.getString("packageName") == PACKAGE) { "Wrong update package" }
            val version = json.getString("versionName")
            val build = integer("versionCode", 1, 2_100_000_000)
            val url = json.getString("downloadUrl")
            require(url == downloadUrl(version, build)) { "Unexpected APK release URL" }
            val hash = json.getString("sha256")
            require(hash.matches(Regex("[0-9a-f]{64}"))) { "Invalid update hash" }
            val minSdk = integer("minSdk", 28, 10000).toInt()
            return Release(ApkUpdateManifest(2, true, ASSET, integer("size", 1, MAX_BYTES), hash,
                PACKAGE, build, version, url), URI(url), minSdk)
        }
    }
}
