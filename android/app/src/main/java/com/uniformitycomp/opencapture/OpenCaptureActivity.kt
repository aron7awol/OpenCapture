package com.uniformitycomp.opencapture

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.core.content.FileProvider
import com.uniformitycomp.capture.ApkUpdateManifest
import com.uniformitycomp.capture.ApkUpdateVerifier
import com.uniformitycomp.capture.PreviewGeometry
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OpenCaptureActivity : Activity(), TextureView.SurfaceTextureListener {
    private lateinit var camera: CameraController
    private lateinit var capture: OpenCaptureCamera
    private lateinit var screen: OpenCaptureScreens
    private val texture get() = screen.texture
    private val worker = Executors.newSingleThreadExecutor()
    private val updateWorker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private val updating = AtomicBoolean(false)
    private var stop = AtomicBoolean(true)
    private var localCancel = AtomicBoolean(false)
    private var updateCancel = AtomicBoolean(false)
    @Volatile private var connection: OpenCaptureConnection? = null
    @Volatile private var updater: OpenCaptureUpdates? = null
    @Volatile private var previewBytes: ByteArray? = null
    private var update: OpenCaptureUpdates.Release? = null
    private var downloadedUpdate: File? = null
    private var resumed = false
    private var opening = false
    private val ui = Handler(Looper.getMainLooper())
    private var discovery: NsdManager.DiscoveryListener? = null
    private val discovered = linkedMapOf<String, String>()
    private val previewTick = object : Runnable {
        override fun run() {
            if (!resumed) return
            if (texture.isAvailable && camera.previewReady && !stop.get()) {
                val height = (640.0 * texture.height / texture.width.coerceAtLeast(1)).toInt().coerceIn(1, 960)
                texture.getBitmap(640, height)?.let { bitmap ->
                    val stream = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 70, stream)
                    previewBytes = stream.toByteArray(); bitmap.recycle()
                }
            }
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        camera = CameraController(this)
        camera.onConnectionLost = { localCancel.set(true); stop.set(true); message("Camera disconnected") }
        val prefs = getSharedPreferences("opencapture", MODE_PRIVATE)
        val deviceId = prefs.getString("deviceID", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("deviceID", it).apply() }
        capture = OpenCaptureCamera(camera, deviceId, File(filesDir, "captures"))
        screen = OpenCaptureScreens(this, object : OpenCaptureScreens.Actions {
            override fun capture() { if (busy.get()) cancelCapture() else local(false) }
            override fun apply() = local(true)
            override fun useCurrent() {
                if (busy.get() || updating.get()) return
                try { controls(capture.currentReading()); message("Current settings copied. Apply controls to lock them.") }
                catch (error: Exception) { message(error.message ?: "No current camera settings") }
            }
            override fun restart() {
                if (busy.get() || updating.get()) return
                camera.close(); opening = false; previewBytes = null; openCamera()
            }
            override fun connect() { if (!stop.get()) disconnect() else startConnection() }
            override fun discover() = discoverControllers()
            override fun selectController(address: String) {
                if (busy.get() || updating.get()) return
                screen.address.setText(address); screen.connectionStatus.text = "Controller selected. Tap Connect."
            }
            override fun refreshCaptures() = history()
            override fun exportCapabilities() = this@OpenCaptureActivity.exportCapabilities()
            override fun checkUpdate() = this@OpenCaptureActivity.checkUpdate()
            override fun installUpdate() = this@OpenCaptureActivity.installUpdate()
        })
        texture.surfaceTextureListener = this
        screen.address.setText(prefs.getString("address", ""))
        val choices = camera.rawCameraChoices()
        screen.lenses.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, choices.map { it.second })
        screen.lenses.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (busy.get() || updating.get()) return
                camera.close(); opening = false; previewBytes = null
                camera.selectRawCamera(choices[position].first); openCamera()
            }
        }
        if (choices.isEmpty()) message("No rear camera advertises RAW support on this phone")
        buttons()
    }

    private fun message(text: String) = runOnUiThread {
        screen.status.text = text
        screen.connectionStatus.text = text
    }
    private fun controls(value: JSONObject) = runOnUiThread {
        screen.exposure.setText((value.getDouble("deviceExposureSeconds") * 1000).toString())
        screen.iso.setText(value.getDouble("deviceISO").toString())
        screen.focus.setText(value.getDouble("lensPosition").toString())
        val wb = value.getJSONObject("whiteBalance")
        screen.wbRed.setText(wb.getDouble("red").toString())
        screen.wbGreen.setText(wb.getDouble("green").toString())
        screen.wbBlue.setText(wb.getDouble("blue").toString())
        screen.manualExposure.isChecked = true; screen.manualFocus.isChecked = true; screen.manualWB.isChecked = true
    }
    private fun buttons() = runOnUiThread {
        screen.setState(busy.get(), !stop.get(), updating.get(), update != null)
    }
    private fun awaitPreview(cancel: AtomicBoolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!camera.previewReady) {
            check(!cancel.get() && System.nanoTime() < deadline) { "Camera preview is not ready" }
            Thread.sleep(50)
        }
    }
    private fun cancelCapture() {
        localCancel.set(true); connection?.cancel(); message("Cancelling; originals are retained")
    }
    private fun local(controlOnly: Boolean) {
        if (updating.get()) return
        val request = try {
            OpenCaptureProtocol.localRequest(if (controlOnly) 1 else screen.frames.text.toString().toInt(),
                if (screen.manualExposure.isChecked) screen.exposure.text.toString().toDouble() else null,
                if (screen.manualExposure.isChecked) screen.iso.text.toString().toDouble() else null,
                if (screen.manualFocus.isChecked) screen.focus.text.toString().toDouble() else null).also {
                if (screen.manualWB.isChecked) it.put("whiteBalance", JSONObject()
                    .put("red", screen.wbRed.text.toString().toDouble()).put("green", screen.wbGreen.text.toString().toDouble())
                    .put("blue", screen.wbBlue.text.toString().toDouble()))
                it.put("settleMilliseconds", screen.settle.text.toString().toInt())
                if (controlOnly) it.put("operation", "applyControls")
                OpenCaptureProtocol.request(it)
            }
        } catch (error: Exception) { message(error.message ?: "Invalid capture settings"); return }
        if (!busy.compareAndSet(false, true)) return
        localCancel = AtomicBoolean(false); buttons()
        worker.execute {
            try {
                awaitPreview(localCancel)
                val (result, directory) = capture.capture(request, localCancel, ::message)
                if (directory == null) { controls(result); message("Controls locked") }
                else message("Saved ${request.getInt("frameCount")} DNG originals. Open Captures to export.")
            } catch (error: Exception) { message(error.message ?: "Capture failed") }
            finally { busy.set(false); buttons(); runOnUiThread { if (screen.selectedTab == 1) history() } }
        }
    }
    private fun startConnection() {
        if (updating.get()) return
        val transport = try { OpenCaptureTransport(screen.address.text.toString(), screen.token.text.toString()) }
            catch (error: Exception) { message(error.message ?: "Invalid PC address"); return }
        if (!busy.compareAndSet(false, true)) { transport.close(); return }
        getSharedPreferences("opencapture", MODE_PRIVATE).edit().putString("address", screen.address.text.toString()).apply()
        stop = AtomicBoolean(false); buttons()
        worker.execute {
            try {
                awaitPreview(stop)
                connection = OpenCaptureConnection(transport, capture, stop, { previewBytes }, ::message, ::controls)
                connection!!.run()
            } catch (error: Exception) { message(error.message ?: "Connection failed") }
            finally { transport.close(); stop.set(true); connection = null; busy.set(false); buttons() }
        }
    }
    private fun disconnect() {
        val wasActive = !stop.get() || busy.get()
        stop.set(true); connection?.cancel(); localCancel.set(true)
        if (wasActive) message("Disconnecting…")
        buttons()
    }

    override fun onResume() { super.onResume(); resumed = true; openCamera(); ui.post(previewTick); buttons() }
    override fun onStop() {
        resumed = false; disconnect(); updateCancel.set(true); updater?.close()
        ui.removeCallbacks(previewTick); stopDiscovery(); camera.close(); opening = false; previewBytes = null
        super.onStop()
    }
    override fun onDestroy() { camera.shutdown(); worker.shutdown(); updateWorker.shutdown(); super.onDestroy() }
    private fun openCamera() {
        if (!resumed || opening || !texture.isAvailable) return
        if (camera.rawCameraChoices().isEmpty()) { message("This phone exposes no rear RAW camera to Camera2"); return }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1); return
        }
        opening = true
        camera.open { success -> runOnUiThread {
            if (success && resumed) {
                camera.startPreview(texture, ::message); transformPreview()
                val caps = capture.capabilities()
                screen.cameraInfo.text = "${caps.getInt("maxPhotoWidth")} × ${caps.getInt("maxPhotoHeight")} DNG · ISO ${caps.optInt("minISO")}–${caps.optInt("maxISO")}"
                message("Ready for local capture or Windows connection")
            } else { opening = false; message("Cannot open a RAW camera. Check camera permission and other camera apps.") }
        } }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) openCamera() else message("Camera permission is required. Enable it in Android app settings.")
    }
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) { openCamera() }
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) { transformPreview() }
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean { disconnect(); camera.close(); opening = false; return true }
    override fun onConfigurationChanged(configuration: Configuration) { super.onConfigurationChanged(configuration); texture.post { transformPreview() } }
    private fun transformPreview() {
        val size = camera.previewDimensions() ?: return
        val displayRotation = when (windowManager.defaultDisplay.rotation) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
        val sensorOrientation = camera.selectedCharacteristics().get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val angle = (sensorOrientation - displayRotation + 360) % 360
        val w = texture.width; val h = texture.height
        if (w <= 0 || h <= 0) return
        screen.setPreviewAspect(if (angle % 180 == 0) size.width.toDouble() / size.height else size.height.toDouble() / size.width)
        texture.setTransform(Matrix().apply { setValues(PreviewGeometry.fit(size.width, size.height, w, h, angle)) })
    }

    private fun discoverControllers() {
        if (busy.get() || updating.get()) { message("Disconnect before changing controllers"); return }
        if (discovery != null) return
        discovered.clear(); screen.showControllers(discovered)
        val nsd = getSystemService(NSD_SERVICE) as NsdManager
        discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onStartDiscoveryFailed(type: String, error: Int) { discovery = null; message("Discovery unavailable; enter the PC address manually") }
            override fun onStopDiscoveryFailed(type: String, error: Int) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onServiceLost(service: NsdServiceInfo) { runOnUiThread { discovered.remove(service.serviceName); screen.showControllers(discovered) } }
            override fun onServiceFound(service: NsdServiceInfo) {
                nsd.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, error: Int) {}
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val host = info.host?.hostAddress ?: return
                        if (!OpenCaptureProtocol.privateHost(host) || info.attributes["protocol"]?.toString(Charsets.UTF_8) != "1") return
                        runOnUiThread { discovered[info.serviceName] = "$host:${info.port}"; screen.showControllers(discovered) }
                    }
                })
            }
        }
        try { nsd.discoverServices("_opencapture._tcp.", NsdManager.PROTOCOL_DNS_SD, discovery) }
        catch (error: Exception) { discovery = null; message("Discovery unavailable; enter the PC address manually") }
    }
    private fun stopDiscovery() {
        discovery?.let { runCatching { (getSystemService(NSD_SERVICE) as NsdManager).stopServiceDiscovery(it) } }
        discovery = null
    }
    private fun history() {
        screen.showingArchive = false; screen.captures.removeAllViews()
        val directories = File(filesDir, "captures").listFiles()?.filter { it.isDirectory && OpenCaptureProtocol.jobId.matches(it.name) }?.sortedByDescending { it.lastModified() }.orEmpty()
        if (directories.isEmpty()) screen.captures.addView(screen.text("No captures yet"))
        for (directory in directories) {
            val count = directory.listFiles()?.count { it.extension == "dng" } ?: 0
            val completed = File(directory, "manifest.json").isFile
            screen.button(screen.section(screen.captures, null), "${if (completed) "Bayer RAW" else "Partial capture"} · $count frames\n${directory.name.take(8)}") { archiveDetail(directory) }
        }
    }
    private fun archiveDetail(directory: File) {
        screen.showingArchive = true; screen.captures.removeAllViews()
        screen.button(screen.captures, "Back to captures") { history() }
        val summary = screen.section(screen.captures, "Capture ${directory.name.take(8)}")
        screen.button(summary, "Export originals and metadata") { exportArchive(directory) }
        val manifest = runCatching { JSONObject(File(directory, "manifest.json").readText()) }.getOrNull()
        if (manifest == null) summary.addView(screen.text("Partial capture; original files retained."))
        else {
            summary.addView(screen.text(manifest.getJSONObject("camera").getString("name")))
            summary.addView(screen.text(manifest.getString("startedAt"), 13f))
        }
        val records = manifest?.optJSONArray("frames")
        directory.listFiles().orEmpty().filter { it.extension == "dng" && OpenCaptureProtocol.fileName.matches(it.name) }.sortedBy { it.name }.forEach { file ->
            val row = screen.section(screen.captures, file.name)
            if (records != null) for (i in 0 until records.length()) {
                val record = records.getJSONObject(i)
                if (record.getJSONObject("file").getString("name") == file.name) {
                    row.addView(screen.text("${record.getInt("width")} × ${record.getInt("height")} · ${record.getDouble("actualExposureSeconds") * 1000} ms · ISO ${record.getDouble("actualISO")}", 14f))
                    row.addView(screen.text("Focus ${record.getDouble("lensPosition")} (0 infinity, 1 nearest)", 13f))
                }
            }
            screen.button(row, "Open original") {
                if (busy.get() || updating.get()) { message("Finish or disconnect before opening originals"); return@button }
                try { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(FileProvider.getUriForFile(this, "$packageName.files", file), "image/dng").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                catch (error: Exception) { message("No DNG viewer is installed. Use Export originals and metadata.") }
            }
        }
    }
    private fun exportArchive(directory: File) {
        if (updating.get() || !busy.compareAndSet(false, true)) { message("Finish or disconnect before exporting captures"); return }
        buttons()
        worker.execute {
            try {
                val archive = File(File(cacheDir, "exports").apply { mkdirs() }, "${directory.name}.zip")
                ZipOutputStream(archive.outputStream()).use { zip ->
                    directory.listFiles().orEmpty().filter { it.isFile && (OpenCaptureProtocol.fileName.matches(it.name) || it.name in setOf("manifest.json", "failure.json")) }.forEach { file ->
                        zip.putNextEntry(ZipEntry(file.name)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                    }
                }
                runOnUiThread { if (resumed) share(archive, "application/zip", "Export originals") }
            } catch (error: Exception) { message(error.message ?: "Export failed") }
            finally { busy.set(false); buttons() }
        }
    }
    private fun share(file: File, type: String, title: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), title))
    }
    private fun exportCapabilities() {
        if (busy.get() || updating.get()) { message("Finish or disconnect before exporting capabilities"); return }
        try {
            val report = File(File(cacheDir, "exports").apply { mkdirs() }, "opencapture-camera-capabilities.json")
            report.writeText(capture.capabilities().put("reportedAt", java.time.Instant.now().toString()).toString())
            share(report, "application/json", "Export camera capabilities")
        } catch (error: Exception) { message(error.message ?: "Cannot read camera capabilities") }
    }

    private fun checkUpdate() {
        if (updating.get() || (busy.get() && stop.get())) return
        val client = OpenCaptureUpdates()
        updating.set(true); updateCancel = AtomicBoolean(false); updater = client
        update = null; downloadedUpdate = null; screen.updateStatus.text = "Checking…"; buttons()
        updateWorker.execute {
            try {
                val release = client.check()
                val manifest = release.apk
                check(!updateCancel.get()) { "Update cancelled" }
                runOnUiThread {
                    if (release.minSdk > android.os.Build.VERSION.SDK_INT) {
                        screen.updateStatus.text = "The GitHub update requires a newer Android version."
                    } else if (manifest.versionCode > BuildConfig.VERSION_CODE) {
                        update = release; screen.updateInstall.text = "Download and install"
                        screen.updateStatus.text = "${manifest.versionName} (build ${manifest.versionCode}) is available. Disconnect before installing."
                    } else screen.updateStatus.text = "You have the newest Android build published on GitHub."
                }
            } catch (error: Exception) { runOnUiThread { screen.updateStatus.text = error.message ?: "Update check failed" } }
            finally { client.close(); updater = null; updating.set(false); buttons() }
        }
    }
    private fun installUpdate() {
        val release = update ?: return
        val manifest = release.apk
        if (busy.get() || updating.get()) return
        val ready = downloadedUpdate
        if (ready != null && ready.isFile) { launchInstaller(ready, manifest); return }
        val client = OpenCaptureUpdates()
        updating.set(true); updateCancel = AtomicBoolean(false); updater = client; buttons()
        screen.progress.visibility = View.VISIBLE; screen.progress.progress = 0
        screen.updateStatus.text = "Downloading and verifying…"
        updateWorker.execute {
            try {
                val file = File(File(cacheDir, "updates").apply { mkdirs() }, "opencapture-${manifest.versionCode}.apk")
                client.download(release, file, updateCancel) { value -> runOnUiThread { screen.progress.progress = value } }
                ApkUpdateVerifier.verifyPackage(this, file, manifest)
                check(!updateCancel.get()) { "Update cancelled" }
                runOnUiThread {
                    downloadedUpdate = file; screen.updateInstall.text = "Install downloaded update"
                    screen.updateStatus.text = "Verified. Ready to install."
                    if (resumed) launchInstaller(file, manifest)
                }
            } catch (error: Exception) { runOnUiThread { screen.updateStatus.text = error.message ?: "Update failed" } }
            finally {
                client.close(); updater = null; updating.set(false)
                runOnUiThread { screen.progress.visibility = View.GONE; buttons() }
            }
        }
    }
    private fun launchInstaller(file: File, manifest: ApkUpdateManifest) {
        try {
            require(manifest.versionCode > BuildConfig.VERSION_CODE) { "This build is already installed" }
            ApkUpdateVerifier.verifyPackage(this, file, manifest)
            if (!packageManager.canRequestPackageInstalls()) {
                screen.updateStatus.text = "Allow OpenCapture to install updates, then return and tap Install downloaded update."
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                return
            }
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (error: Exception) { screen.updateStatus.text = error.message ?: "Cannot start the Android installer" }
    }
}
