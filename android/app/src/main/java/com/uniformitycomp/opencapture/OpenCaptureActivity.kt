// SPDX-License-Identifier: GPL-3.0-only
package com.uniformitycomp.opencapture

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
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
    private val captureActive = AtomicBoolean(false)
    private var brightnessVisible = false
    private var dimScreenDuringCapture = false
    private var savedScreenBrightness: Float? = null
    private var updater: OpenCaptureUpdates? = null
    private var updateCancel = AtomicBoolean(false)
    private var server: CameraServer? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var address: String? = null
    @Volatile private var resumed = false
    private var enabled = true
    private val recovery = CameraRecovery()
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
                if (ip != address) stopServer(preserveSession = true)
                if (server == null && ip != null && CameraRecovery.canBindListener(
                        camera.previewReady, session.state().optBoolean("connected"))) startServer(ip)
                if (camera.previewReady && texture.isAvailable) updatePreviewJpeg()
                if (camera.previewReady && ip == null) message("Waiting for Wi-Fi")
            }
            ui.postDelayed(this, 1000)
        }
    }
    private fun updatePreviewJpeg() {
        if (texture.width <= 0 || texture.height <= 0) return
        val size = PreviewGeometry.thumbnailSize(texture.width, texture.height)
        val source = texture.getBitmap(size.width, size.height) ?: return
        try {
            // getBitmap copies the surface, not the view's fit/rotation transform.
            // Apply that transform once, in thumbnail coordinates, without UI guides.
            val values = FloatArray(9).also { texture.getTransform(Matrix()).getValues(it) }
            val transform = Matrix().apply { setValues(PreviewGeometry.thumbnailTransform(values,
                texture.width, texture.height, size.width, size.height)) }
            val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
            try {
                Canvas(bitmap).apply {
                    drawColor(Color.BLACK)
                    drawBitmap(source, transform, Paint(Paint.FILTER_BITMAP_FLAG))
                }
                val stream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 70, stream)
                previewBytes = stream.toByteArray()
            } finally { bitmap.recycle() }
        } finally { source.recycle() }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK
        val prefs = getSharedPreferences("opencapture", MODE_PRIVATE)
        dimScreenDuringCapture = prefs.getBoolean("dimScreenDuringCapture", false)
        deviceID = prefs.getString("deviceID", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("deviceID", it).apply() }
        camera = CameraController(this, (application as OpenCaptureApplication).events)
        val staging = File(filesDir, "server-transfers")
        capture = OpenCaptureCamera(camera, deviceID, staging)
        session = CameraServerSession(staging,
            { request, cancel, progress -> withDimmedCapture { capture.capture(request, cancel, progress) } },
            { text -> runOnUiThread { if (server != null) message(text) } },
            streamCapture={ request, cancel, progress, stream ->
                withDimmedCapture { capture.captureStreaming(request, cancel, progress, stream) }
            }, diagnostic = { event, detail -> camera.events.record(event, detail) })
        screen = OpenCaptureScreens(this, ::toggle, ::showAbout)
        texture.surfaceTextureListener = this
    }
    private fun closeCamera(resetAttempts: Boolean = false, reason: String = "activity_camera_close") {
        recovery.invalidate(resetAttempts)
        camera.onConnectionLost = null
        camera.close(reason)
        previewBytes = null
    }
    private fun recoverCamera(failedGeneration: Long) {
        // Keep the reservation and completed transfers while the failed camera
        // request drains. A renderer/controller can then resume the same job.
        if (!resumed || !enabled) return
        val ticket = recovery.beginRecovery(failedGeneration) ?: return
        camera.events.record("recovery_started", JSONObject().put("attempt", recovery.attempts)
            .put("pending", recovery.pending))
        camera.onConnectionLost = null
        camera.close("camera_recovery"); previewBytes = null
        if (!recovery.pending) {
            message("Camera recovery failed. Tap Stop, then Resume.")
            return
        }
        val cleanupDeadline = System.nanoTime() + 90_000_000_000L
        ui.postDelayed(object : Runnable {
            override fun run() {
                if (!recovery.current(ticket)) return
                if (!resumed || !enabled) { recovery.abandonRetry(ticket); return }
                if (!session.state().optBoolean("cameraReady", false)) {
                    if (System.nanoTime() < cleanupDeadline) ui.postDelayed(this, 250)
                    else {
                        recovery.abandonRetry(ticket)
                        camera.events.record("recovery_cleanup_timeout", fault = true)
                        message("Camera cleanup stalled. Tap Stop, then Resume.")
                    }
                    return
                }
                if (recovery.takeRetry(ticket)) {
                    camera.events.record("recovery_reopen", JSONObject().put("attempt", recovery.attempts))
                    message("Reopening camera (${recovery.attempts}/3)")
                    openCamera()
                }
            }
        }, 1000)
    }
    private fun message(text: String) = runOnUiThread {
        screen.status.text = if (text == "Ready" && address != null) "Ready at $address:8767" else text
    }
    private fun keepAwake() {
        if (shouldKeepScreenAwake(resumed, enabled, updating.get())) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private fun updateScreenBrightness() {
        val attributes = window.attributes
        if (dimScreenDuringCapture && captureActive.get() && brightnessVisible && enabled) {
            if (savedScreenBrightness == null) savedScreenBrightness = attributes.screenBrightness
            attributes.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF
        } else {
            attributes.screenBrightness = savedScreenBrightness ?: return
            savedScreenBrightness = null
        }
        window.attributes = attributes
    }
    private fun <T> withDimmedCapture(action: () -> T): T {
        captureActive.set(true)
        runOnUiThread { updateScreenBrightness() }
        try { return action() }
        finally {
            captureActive.set(false)
            runOnUiThread { updateScreenBrightness() }
        }
    }
    private fun logLifecycle(event: String) {
        val awakeFlag = window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
        val interactive = (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
        if (::camera.isInitialized) camera.events.record("activity_$event", JSONObject()
            .put("visible", resumed).put("enabled", enabled).put("updating", updating.get())
            .put("keepScreenOn", awakeFlag).put("interactive", interactive))
        Log.i("OpenCaptureLifecycle", "$event visible=$resumed enabled=$enabled updating=${updating.get()} keepScreenOn=$awakeFlag interactive=$interactive")
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        keepAwake()
        logLifecycle("focus=$hasFocus")
    }
    private fun toggle() {
        enabled = !enabled; screen.serving(enabled)
        updateScreenBrightness()
        keepAwake()
        logLifecycle("toggle")
        if (enabled) { recovery.invalidate(resetAttempts = true); openCamera(); message("Opening camera") }
        else { stopServer(reason="user_stop"); closeCamera(reason="user_stop"); message("Stopped") }
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
        .put("cameraPreviewReady", camera.previewReady && camera.lastPreviewResult != null)
        .put("diagnosticModes", JSONArray().put(PersistentDiagnostics.MODE))
        .put("deviceID", deviceID).put("name", android.os.Build.MODEL).put("capabilities", capture.capabilities())
        .put("cameras", JSONArray(camera.rawCameraChoices().map { JSONObject().put("id", it.first).put("name", it.second) }))
        .also { info ->
            // Read the sticky OS snapshot; no receiver, camera request or capture interruption.
            val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val percent = if (scale > 0 && level in 0..scale)
                kotlin.math.round(level * 100.0 / scale).toInt() else null
            val charging = when (battery?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)) {
                BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL -> true
                BatteryManager.BATTERY_STATUS_DISCHARGING, BatteryManager.BATTERY_STATUS_NOT_CHARGING -> false
                else -> null
            }
            info.put("batteryPercent", percent ?: JSONObject.NULL).put("batteryCharging", charging ?: JSONObject.NULL)
        }
    private fun selectCamera(id: String): JSONObject {
        require(camera.rawCameraChoices().any { it.first == id }) { "Unavailable camera" }
        val ready = CountDownLatch(1)
        var failure: Exception? = null
        runOnUiThread {
            try {
                check(resumed && enabled) { "Camera server stopped" }
                closeCamera(resetAttempts = true, reason="lens_change")
                previewBytes = null; camera.selectRawCamera(id); openCamera()
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
            val listener = CameraServer(ip, session, ::info, ::selectCamera, camera::diagnostics,
                { (application as OpenCaptureApplication).diagnosticBundle(deviceID) }) { previewBytes }
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
        } catch (error: Exception) { stopServer(preserveSession = true); message(error.message ?: "Server unavailable") }
    }
    private fun stopServer(preserveSession: Boolean = false, reason: String = "server_stopped") {
        server?.stop(); server = null; address = null
        if (!preserveSession) session.end(reason)
        previewBytes = null
        registration?.let { runCatching { (getSystemService(NSD_SERVICE) as NsdManager).unregisterService(it) } }
        registration = null
    }
    override fun onStart() { super.onStart(); displayManager.registerDisplayListener(displayListener, ui) }
    override fun onResume() {
        super.onResume(); resumed = true; keepAwake()
        brightnessVisible = true; updateScreenBrightness()
        recovery.resetAttempts()
        logLifecycle("resume")
        if (enabled) openCamera()
        ui.removeCallbacks(tick); ui.post(tick)
    }
    override fun onPause() {
        brightnessVisible = false; updateScreenBrightness()
        super.onPause()
    }
    override fun onStop() {
        resumed = false; displayManager.unregisterDisplayListener(displayListener)
        keepAwake()
        logLifecycle("stop")
        stopServer(reason="backgrounded"); updateCancel.set(true); updater?.close(); ui.removeCallbacks(tick)
        closeCamera(reason="backgrounded"); super.onStop()
    }
    override fun onDestroy() { session.close(); camera.shutdown(); updates.shutdown(); super.onDestroy() }
    private fun openCamera() {
        if (!resumed || !enabled || recovery.opening || recovery.pending || !texture.isAvailable) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1); return
        }
        if (camera.rawCameraChoices().isEmpty()) { message("No supported rear RAW camera"); return }
        val ticket = recovery.beginOpen() ?: return
        val cleanupDeadline = System.nanoTime() + 90_000_000_000L
        ui.post(object : Runnable {
            override fun run() {
                if (!recovery.current(ticket) || !resumed || !enabled) return
                // Stop/Resume may arrive before the previous capture has unwound.
                if (!camera.captureDrained) {
                    if (System.nanoTime() < cleanupDeadline) ui.postDelayed(this, 250)
                    else { recovery.invalidate(); message("Camera cleanup stalled. Tap Stop, then Resume.") }
                    return
                }
                camera.onConnectionLost = { runOnUiThread { recoverCamera(ticket) } }
                camera.open { success -> runOnUiThread {
                    if (!recovery.current(ticket)) return@runOnUiThread
                    if (success && resumed && enabled) {
                        camera.startPreview(texture) { error -> runOnUiThread {
                            if (recovery.current(ticket)) { message(error); recoverCamera(ticket) }
                        } }; transformPreview()
                        screen.cameraInfo.text = camera.rawCameraChoices().firstOrNull { it.first == camera.cameraID() }?.second ?: "OpenCapture"
                        message("Starting camera server")
                    } else { if (resumed && enabled) recoverCamera(ticket) else message("Camera unavailable") }
                } }
            }
        })
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) openCamera() else message("Camera permission required")
    }
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) { openCamera() }
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) { transformPreview() }
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean { logLifecycle("surface_destroyed"); stopServer(reason="surface_destroyed"); closeCamera(reason="surface_destroyed"); return true }
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
        val content = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            addView(android.widget.TextView(this@OpenCaptureActivity).apply {
                text = "Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})\nGPL-3.0-only"
            })
            addView(android.widget.Switch(this@OpenCaptureActivity).apply {
                text = "Dim screen during capture"
                isChecked = dimScreenDuringCapture
                setOnCheckedChangeListener { _, checked ->
                    dimScreenDuringCapture = checked
                    getSharedPreferences("opencapture", MODE_PRIVATE).edit().putBoolean("dimScreenDuringCapture", checked).apply()
                    updateScreenBrightness()
                }
            })
            addView(android.widget.TextView(this@OpenCaptureActivity).apply {
                text = "Use minimum screen brightness while preparing, capturing and processing photos. Restore brightness when finished or when leaving the app. Camera exposure is unchanged."
            })
        }
        AlertDialog.Builder(this).setTitle("OpenCapture")
            .setView(content)
            .setPositiveButton("Check for updates") { _, _ -> checkUpdate() }
            .setNeutralButton("Licenses") { _, _ ->
                val text = resources.openRawResource(R.raw.third_party_notices).bufferedReader().use { it.readText() }
                AlertDialog.Builder(this).setTitle("Licenses").setMessage(text).setPositiveButton("Close", null).show()
            }.setNegativeButton("Close", null).show()
    }
    private fun checkUpdate() {
        if (!updating.compareAndSet(false, true)) return
        keepAwake()
        message("Checking updates")
        val client = OpenCaptureUpdates(); updater = client
        val cancelled = AtomicBoolean(false); updateCancel = cancelled
        updates.execute {
            var release: OpenCaptureUpdates.Release? = null
            var failure: Exception? = null
            try { release = client.check() }
            catch (error: Exception) { failure = error }
            finally { client.close() }
            val result = release
            val error = failure
            runOnUiThread {
                if (updater !== client) return@runOnUiThread
                updater = null; updating.set(false); keepAwake()
                if (!resumed || cancelled.get()) return@runOnUiThread
                if (error != null) showUpdateFailure("Update check failed", error)
                else if (result != null) {
                    val release = result
                    if (release.apk.versionCode <= BuildConfig.VERSION_CODE) AlertDialog.Builder(this)
                        .setTitle("OpenCapture").setMessage("You have the newest published Android build.").setPositiveButton("Close", null).show()
                    else AlertDialog.Builder(this).setTitle("OpenCapture ${release.apk.versionName}")
                        .setMessage("Stop the camera session and install this update?")
                        .setNegativeButton("Cancel", null).setPositiveButton("Update") { _, _ -> installUpdate(release) }.show()
                }
            }
        }
    }
    private fun showUpdateFailure(title: String, error: Exception) {
        val detail = OpenCaptureUpdates.failureMessage(error)
        message(title)
        Log.w("OpenCaptureUpdate", "$title: $detail")
        AlertDialog.Builder(this).setTitle(title).setMessage(detail)
            .setPositiveButton("Retry") { _, _ -> checkUpdate() }
            .setNegativeButton("Close", null).show()
    }
    private fun installUpdate(release: OpenCaptureUpdates.Release) {
        if (!updating.compareAndSet(false, true)) return
        enabled = false; screen.serving(false); stopServer(reason="app_update"); closeCamera(reason="app_update")
        keepAwake()
        val client = OpenCaptureUpdates(); updater = client
        val cancelled = AtomicBoolean(false); updateCancel = cancelled
        updates.execute {
            var downloaded: File? = null
            var failure: Exception? = null
            try {
                val file = File(File(cacheDir, "updates").apply { mkdirs() }, "opencapture-${release.apk.versionCode}.apk")
                client.download(release, file, cancelled) { message("Downloading update: $it%") }
                ApkUpdateVerifier.verifyPackage(this, file, release.apk)
                check(!cancelled.get()) { "Update cancelled" }
                downloaded = file
            } catch (error: Exception) { failure = error }
            finally { client.close() }
            val file = downloaded
            val error = failure
            runOnUiThread {
                if (updater !== client) return@runOnUiThread
                updater = null; updating.set(false); keepAwake()
                if (!resumed || cancelled.get()) return@runOnUiThread
                if (error != null) showUpdateFailure("Update failed", error)
                else if (file != null) {
                    if (!packageManager.canRequestPackageInstalls()) {
                        message("Allow updates, then open About again")
                        startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                    } else {
                        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
                        startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    }
                }
            }
        }
    }
}

internal fun shouldKeepScreenAwake(visible: Boolean, serverEnabled: Boolean, updateInProgress: Boolean): Boolean =
    visible && (serverEnabled || updateInProgress)
