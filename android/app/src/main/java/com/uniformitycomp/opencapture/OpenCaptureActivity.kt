// SPDX-License-Identifier: GPL-3.0-only
package com.uniformitycomp.opencapture

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import androidx.core.content.FileProvider
import com.uniformitycomp.capture.ApkUpdateVerifier
import com.uniformitycomp.capture.PreviewGeometry
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Inet4Address
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class OpenCaptureActivity : Activity(), TextureView.SurfaceTextureListener {
    private lateinit var camera: CameraController
    private lateinit var capture: OpenCaptureCamera
    private lateinit var session: CameraServerSession
    private lateinit var screen: OpenCaptureScreens
    private lateinit var deviceID: String
    private val texture get() = screen.texture
    private val ui = Handler(Looper.getMainLooper())
    private val updates = Executors.newSingleThreadExecutor()
    private val updating = AtomicBoolean(false)
    private var updater: OpenCaptureUpdates? = null
    private var updateCancel = AtomicBoolean(false)
    private var server: CameraServer? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var address: String? = null
    @Volatile private var resumed = false
    private var enabled = true
    private var opening = false
    @Volatile private var previewBytes: ByteArray? = null
    private val displayManager by lazy { getSystemService(DISPLAY_SERVICE) as DisplayManager }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (texture.display?.displayId == displayId) texture.post { transformPreview() }
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            if (!resumed) return
            session.expire()
            if (enabled) {
                val ip = lanAddress()
                if (ip != address) stopServer()
                if (camera.previewReady && server == null && ip != null) startServer(ip)
                if (camera.previewReady && texture.isAvailable) {
                    val height = (640.0 * texture.height / texture.width.coerceAtLeast(1)).toInt().coerceIn(1, 960)
                    texture.getBitmap(640, height)?.let { bitmap ->
                        val stream = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, stream)
                        previewBytes = stream.toByteArray(); bitmap.recycle()
                    }
                }
                if (camera.previewReady && ip == null) message("Waiting for Wi-Fi")
            }
            ui.postDelayed(this, 1000)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK
        val prefs = getSharedPreferences("opencapture", MODE_PRIVATE)
        deviceID = prefs.getString("deviceID", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("deviceID", it).apply() }
        camera = CameraController(this)
        val staging = File(filesDir, "server-transfers")
        capture = OpenCaptureCamera(camera, deviceID, staging)
        session = CameraServerSession(staging, capture::capture, { text -> runOnUiThread { if (server != null) message(text) } })
        screen = OpenCaptureScreens(this, ::toggle, ::showAbout)
        texture.surfaceTextureListener = this
        camera.onConnectionLost = { runOnUiThread { stopServer(); camera.close(); opening = false; message("Camera disconnected") } }
    }
    private fun message(text: String) = runOnUiThread {
        screen.status.text = if (text == "Ready" && address != null) "Ready at $address:8767" else text
    }
    private fun keepAwake() {
        if (enabled && resumed) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private fun toggle() {
        enabled = !enabled; screen.serving(enabled)
        keepAwake()
        if (enabled) { openCamera(); message("Opening camera") }
        else { stopServer(); camera.close(); opening = false; message("Stopped") }
    }
    private fun lanAddress(): String? {
        val manager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        return manager.allNetworks.firstNotNullOfOrNull { network ->
            val caps = manager.getNetworkCapabilities(network)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true && caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) != true) null
            else manager.getLinkProperties(network)?.linkAddresses?.map { it.address }
                ?.filterIsInstance<Inet4Address>()?.firstOrNull { CameraServer.privateIPv4(it.hostAddress ?: "") }?.hostAddress
        }
    }
    private fun info(): JSONObject = JSONObject().put("protocolVersion", 1).put("role", "camera")
        .put("deviceID", deviceID).put("name", android.os.Build.MODEL).put("capabilities", capture.capabilities())
        .put("cameras", JSONArray(camera.rawCameraChoices().map { JSONObject().put("id", it.first).put("name", it.second) }))
    private fun selectCamera(id: String): JSONObject {
        require(camera.rawCameraChoices().any { it.first == id }) { "Unavailable camera" }
        val ready = CountDownLatch(1)
        var failure: Exception? = null
        runOnUiThread {
            try {
                check(resumed && enabled) { "Camera server stopped" }
                camera.close(); opening = false; previewBytes = null; camera.selectRawCamera(id); openCamera()
            } catch (error: Exception) { failure = error }
            finally { ready.countDown() }
        }
        check(ready.await(5, TimeUnit.SECONDS)) { "Camera selection timed out" }
        failure?.let { throw it }
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!camera.previewReady) {
            check(resumed && System.nanoTime() < deadline) { "Camera is not ready" }; Thread.sleep(50)
        }
        return info()
    }
    private fun startServer(ip: String) {
        try {
            val listener = CameraServer(ip, session, ::info, ::selectCamera) { previewBytes }
            listener.start(5000, true); server = listener; address = ip
            val nsd = getSystemService(NSD_SERVICE) as NsdManager
            val advertised = NsdServiceInfo().apply {
                serviceName = "OpenCapture ${android.os.Build.MODEL}"; serviceType = "_oc-camera._tcp."; port = 8767
                setAttribute("protocol", "1"); setAttribute("role", "camera"); setAttribute("auth", "none"); setAttribute("deviceID", deviceID)
            }
            registration = object : NsdManager.RegistrationListener {
                override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) { message("Ready at $ip:8767") }
                override fun onServiceRegistered(info: NsdServiceInfo) {}
                override fun onServiceUnregistered(info: NsdServiceInfo) {}
                override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) {}
            }
            nsd.registerService(advertised, NsdManager.PROTOCOL_DNS_SD, registration)
            message("Ready at $ip:8767")
        } catch (error: Exception) { stopServer(); message(error.message ?: "Server unavailable") }
    }
    private fun stopServer() {
        server?.stop(); server = null; address = null; session.end(); previewBytes = null
        registration?.let { runCatching { (getSystemService(NSD_SERVICE) as NsdManager).unregisterService(it) } }
        registration = null
    }
    override fun onStart() { super.onStart(); displayManager.registerDisplayListener(displayListener, ui) }
    override fun onResume() {
        super.onResume(); resumed = true; keepAwake()
        if (enabled) openCamera()
        ui.removeCallbacks(tick); ui.post(tick)
    }
    override fun onStop() {
        resumed = false; displayManager.unregisterDisplayListener(displayListener)
        keepAwake()
        stopServer(); updateCancel.set(true); updater?.close(); ui.removeCallbacks(tick)
        camera.close(); opening = false; super.onStop()
    }
    override fun onDestroy() { session.close(); camera.shutdown(); updates.shutdown(); super.onDestroy() }
    private fun openCamera() {
        if (!resumed || !enabled || opening || !texture.isAvailable) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1); return
        }
        if (camera.rawCameraChoices().isEmpty()) { message("No supported rear RAW camera"); return }
        opening = true
        camera.open { success -> runOnUiThread {
            if (success && resumed && enabled) {
                camera.startPreview(texture, ::message); transformPreview()
                screen.cameraInfo.text = camera.rawCameraChoices().firstOrNull { it.first == camera.cameraID() }?.second ?: "OpenCapture"
                message("Starting camera server")
            } else { opening = false; message("Camera unavailable") }
        } }
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) openCamera() else message("Camera permission required")
    }
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) { openCamera() }
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) { transformPreview() }
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean { stopServer(); camera.close(); opening = false; return true }
    override fun onConfigurationChanged(configuration: Configuration) { super.onConfigurationChanged(configuration); texture.post { transformPreview() } }
    private fun transformPreview() {
        val size = camera.previewDimensions() ?: return
        val rotation = when (texture.display?.rotation) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
        val sensor = camera.selectedCharacteristics().get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        if (texture.width <= 0 || texture.height <= 0) return
        val transform = Matrix().apply { setValues(PreviewGeometry.textureFit(size.width, size.height, texture.width, texture.height, sensor, rotation)) }
        texture.setTransform(transform)
        screen.outline(android.graphics.RectF(0f, 0f, texture.width.toFloat(), texture.height.toFloat()).apply { transform.mapRect(this) })
    }
    private fun showAbout() {
        AlertDialog.Builder(this).setTitle("OpenCapture")
            .setMessage("Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})\nGPL-3.0-only")
            .setPositiveButton("Check for updates") { _, _ -> checkUpdate() }
            .setNeutralButton("Licenses") { _, _ ->
                val text = resources.openRawResource(R.raw.third_party_notices).bufferedReader().use { it.readText() }
                AlertDialog.Builder(this).setTitle("Licenses").setMessage(text).setPositiveButton("Close", null).show()
            }.setNegativeButton("Close", null).show()
    }
    private fun checkUpdate() {
        if (!updating.compareAndSet(false, true)) return
        message("Checking updates")
        val client = OpenCaptureUpdates(); updater = client; updateCancel = AtomicBoolean(false)
        updates.execute {
            try {
                val release = client.check()
                runOnUiThread {
                    if (!resumed) return@runOnUiThread
                    if (release.apk.versionCode <= BuildConfig.VERSION_CODE) AlertDialog.Builder(this)
                        .setTitle("OpenCapture").setMessage("You have the newest published Android build.").setPositiveButton("Close", null).show()
                    else AlertDialog.Builder(this).setTitle("OpenCapture ${release.apk.versionName}")
                        .setMessage("Stop the camera session and install this update?")
                        .setNegativeButton("Cancel", null).setPositiveButton("Update") { _, _ -> installUpdate(release) }.show()
                }
            } catch (error: Exception) { message(error.message ?: "Update check failed") }
            finally { client.close(); updater = null; updating.set(false) }
        }
    }
    private fun installUpdate(release: OpenCaptureUpdates.Release) {
        if (!updating.compareAndSet(false, true)) return
        enabled = false; screen.serving(false); stopServer(); camera.close(); opening = false
        keepAwake()
        val client = OpenCaptureUpdates(); updater = client; updateCancel = AtomicBoolean(false)
        updates.execute {
            try {
                val file = File(File(cacheDir, "updates").apply { mkdirs() }, "opencapture-${release.apk.versionCode}.apk")
                client.download(release, file, updateCancel) { message("Downloading update: $it%") }
                ApkUpdateVerifier.verifyPackage(this, file, release.apk)
                check(!updateCancel.get()) { "Update cancelled" }
                runOnUiThread {
                    if (!resumed) return@runOnUiThread
                    if (!packageManager.canRequestPackageInstalls()) {
                        message("Allow updates, then open About again")
                        startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                    } else {
                        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
                        startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    }
                }
            } catch (error: Exception) { message(error.message ?: "Update failed") }
            finally { client.close(); updater = null; updating.set(false) }
        }
    }
}
