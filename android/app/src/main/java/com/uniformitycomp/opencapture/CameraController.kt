// SPDX-License-Identifier: GPL-3.0-only
package com.uniformitycomp.opencapture

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.TextureView
import com.uniformitycomp.capture.DngCapture
import com.uniformitycomp.capture.DngFrame
import com.uniformitycomp.capture.DngLockedSettings
import com.uniformitycomp.capture.DngSequence
import com.uniformitycomp.capture.DngSettings
import com.uniformitycomp.capture.PreviewGeometry
import com.uniformitycomp.capture.DngVerifiedState
import com.uniformitycomp.capture.ManualCaptureCache
import com.uniformitycomp.capture.ManualCaptureScope
import com.uniformitycomp.capture.ManualRequestKey
import com.uniformitycomp.capture.ManualStateMismatch
import java.util.concurrent.atomic.AtomicBoolean

/** Camera lifecycle and DNG preview/capture only; this client speaks the generic OpenCapture protocol. */
class CameraController(private val context: Context, val events: CameraDiagnostics = CameraDiagnostics()) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val cameraThread = HandlerThread("OpenCaptureCamera").also { it.start() }
    private val resultThread = HandlerThread("OpenCaptureResults").also { it.start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val resultHandler = Handler(resultThread.looper)
    private var cameraDevice: CameraDevice? = null
    private var rawReader: ImageReader? = null
    private var rawSize: Size? = null
    private var previewSize: Size? = null
    private var previewView: TextureView? = null
    private var previewSession: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var selectedCameraId: String? = null
    @Volatile private var dngPreviewControls: DngLockedSettings? = null
    @Volatile private var shortPreview = false
    private val manualState = ManualCaptureCache<DngVerifiedState>()
    private var vendorFocusSeed: Pair<Float, Int>? = null
    @Volatile private var previewRequestGeneration = 0L
    @Volatile private var cameraGeneration = 0L
    @Volatile private var sessionGeneration = 0L
    @Volatile private var lastPreviewElapsedNs = 0L
    @Volatile var previewReady = false
        private set
    @Volatile var lastPreviewResult: TotalCaptureResult? = null
        private set
    @Volatile private var activeCaptureCancel: AtomicBoolean? = null
    @Volatile private var activeCaptureCleanup: (() -> Unit)? = null
    @Volatile private var pendingOpenCancel: AtomicBoolean? = null
    @Volatile var onConnectionLost: (() -> Unit)? = null
    val captureDrained: Boolean get() = activeCaptureCancel == null

    fun runtimeEvidence(): org.json.JSONObject = org.json.JSONObject()
        .put("cameraGeneration", cameraGeneration).put("sessionGeneration", sessionGeneration)
        .put("previewRequestGeneration", previewRequestGeneration).put("previewReady", previewReady)
        .put("captureActive", activeCaptureCancel != null).put("cameraOpen", cameraDevice != null)
        .put("sessionPresent", previewSession != null).put("cameraID", selectedCameraId)
        .put("aimingPreviewLastResultAgeMs", if (lastPreviewElapsedNs > 0)
            (android.os.SystemClock.elapsedRealtimeNanos()-lastPreviewElapsedNs)/1e6 else org.json.JSONObject.NULL)

    private fun selectBestCamera(): String {
        val id = rawCameraChoices().firstOrNull()?.first ?: error("No rear RAW camera is available")
        selectedCameraId = id
        return id
    }

    fun cameraID(): String = selectedCameraId ?: selectBestCamera()

    // Characteristics only: never open another lens or submit a capture to answer /info.
    fun diagnostics(): org.json.JSONObject {
        val entries = org.json.JSONArray()
        val report = org.json.JSONObject().put("schemaVersion", 1).put("source", "camera2Characteristics")
            .put("rawExposureLockTested", false).put("cameras", entries)
        try {
            for (id in cameraManager.cameraIdList) {
                try {
                    val c = cameraManager.getCameraCharacteristics(id)
                    if (c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK)
                        entries.put(CameraCapabilityReport.describe(id, c))
                } catch (_: Exception) {
                    entries.put(org.json.JSONObject().put("cameraID", id).put("error", "characteristicsUnavailable"))
                }
            }
        } catch (_: CameraAccessException) {
            report.put("error", "cameraListUnavailable")
        }
        return report.put("runtime", events.snapshot())
    }

    fun rawCameraChoices(): List<Pair<String, String>> = cameraManager.cameraIdList.mapNotNull { id ->
        val c = cameraManager.getCameraCharacteristics(id)
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        if (c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK ||
            !caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)) null
        else id to "Camera $id (${c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.joinToString()} mm)"
    }

    fun selectRawCamera(id: String) {
        require(rawCameraChoices().any { it.first == id }) { "Unavailable rear RAW camera" }
        check(cameraDevice == null) { "Close the camera before changing lenses" }
        selectedCameraId = id
        invalidateManualState("lens_changed")
    }

    fun selectedCharacteristics(): CameraCharacteristics = cameraManager.getCameraCharacteristics(
        selectedCameraId ?: selectBestCamera())

    fun previewDimensions(): Size? = previewSize

    fun experimentalSchedulingModes(): List<String> {
        val c = selectedCharacteristics()
        val jpeg = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.JPEG)
        val result = mutableListOf<String>()
        if (!jpeg.isNullOrEmpty()) result.add("raw-jpeg-still")
        if (SamsungCaptureCompatibility.available()) {
            result.add("samsung-vendor-preview")
            if (!jpeg.isNullOrEmpty()) result.add("samsung-vendor-still")
        }
        return result
    }

    /** Ordered original DNG capture with optional exposure overlap; the caller retains completed files. */
    fun captureDngOriginals(settings: DngSettings, count: Int, directory: java.io.File?, cancel: AtomicBoolean,
                            maxAttempts: Int = count, needsMore: (() -> Boolean)? = null,
                            overlap: Boolean = false, tryReserveNext: (() -> Boolean)? = null,
                            keepPreviewRunning: Boolean = false,
                            shortPreviewExposure: Boolean = false,
                            queueAhead: Boolean = false,
                            vendorCamera: Boolean = false,
                            separateStill: Boolean = false,
                            focusProbe: org.json.JSONObject? = null,
                            onFrame: (DngFrame) -> Unit): DngSequence {
        val camera = requireNotNull(cameraDevice) { "Camera is not open" }
        val connectionLost = onConnectionLost
        val size = requireNotNull(rawSize) { "No supported RAW size" }
        val reader = requireNotNull(rawReader) { "RAW reader is not ready" }
        check(reader.width == size.width && reader.height == size.height) { "RAW stream changed" }
        val surface = requireNotNull(previewSurface) { "Start the aiming preview first" }
        val configured = requireNotNull(previewSession) { "Preview session is not ready" }
        val experimental = vendorCamera || separateStill
        require(!vendorCamera || SamsungCaptureCompatibility.available()) {
            "Samsung vendor controls are unavailable on this camera/firmware"
        }
        require(!experimental || (!overlap && !queueAhead && !shortPreviewExposure))
        require(focusProbe == null || (vendorCamera && count == 2 && maxAttempts == 2 && !overlap && !queueAhead))
        val vendor = if (vendorCamera) SamsungCaptureCompatibility(events, selectedCharacteristics(),
            if (focusProbe == null) vendorFocusSeed else null, focusProbe) else null
        var jpegReader: ImageReader? = null
        var vendorAttempted = false
        var captureError: Exception? = null
        registerCapture(cancel) { cancel.set(true) }
        if (experimental) invalidateManualState("experimental_capture_setup")
        val ticket = manualState.begin(ManualRequestKey.from(settings.exposureNs, settings.iso,
            settings.focusFraction, settings.whiteBalance), ManualCaptureScope(camera, configured, reader, surface), count > 0)
        previewReady = false
        events.record("capture_started", runtimeEvidence().put("overlap", overlap)
            .put("repeatingPreviewDuringStill", keepPreviewRunning).put("queueAhead", queueAhead)
            .put("exposureNs", settings.exposureNs).put("iso", settings.iso).put("frames", count))
        var succeeded = false
        try {
            if (vendor != null) {
                events.record("samsung_compatibility_probe", SamsungCaptureCompatibility.diagnostics(selectedCharacteristics()))
                vendorAttempted = true
                vendor.configure(camera, true)
            }
            if (separateStill && count > 0) {
                val sizes = selectedCharacteristics().get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(ImageFormat.JPEG).orEmpty()
                val jpegSize = sizes.firstOrNull { it == size }
                    ?: sizes.filter { it.width.toLong()*it.height <= 13_000_000L }.maxByOrNull { it.width.toLong()*it.height }
                    ?: sizes.minByOrNull { it.width.toLong()*it.height }
                    ?: error("JPEG output is unavailable")
                jpegReader = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2).also { jpeg ->
                    jpeg.setOnImageAvailableListener({ source ->
                        runCatching { source.acquireNextImage() }.getOrNull()?.use { image ->
                            events.record("companion_jpeg_arrived", org.json.JSONObject()
                                .put("timestampNs", image.timestamp).put("width", image.width).put("height", image.height)
                                .put("bytes", image.planes.firstOrNull()?.buffer?.remaining()).put("retained", false))
                        }
                    }, resultHandler)
                }
            }
            val sequence = DngCapture(camera, reader, surface, selectedCharacteristics(), resultHandler, cancel, reportLensShading = true,
                existingSession = configured, previousLocked = dngPreviewControls,
                configureRequest = vendor?.let { compatibility -> { builder -> compatibility.request(builder) } },
                compatibilityReadback = vendor?.let { compatibility -> { result -> compatibility.readback(result) } },
                prepareFocus = vendor?.let { compatibility -> { result, target -> compatibility.prepareFocus(result, target) } },
                configureFocus = vendor?.let { compatibility -> { builder, target -> compatibility.applyFocus(builder, target) } },
                retryFocus = vendor?.takeIf { focusProbe == null }?.let { compatibility -> { result, target -> compatibility.retryFocus(result, target) } },
                diagnosticFocusReady = if (focusProbe == null) null else { result -> vendor!!.probeReady(result) },
                stillJpegReader = jpegReader,
                beforeStillSession = {
                    synchronized(this) {
                        check(previewSession === configured && cameraDevice === camera && !cancel.get())
                        previewSession = null
                        sessionGeneration++
                        events.record("preview_detached_for_still", runtimeEvidence())
                    }
                },
                previousVerified = ticket.reused, setupFallbackReason = ticket.reason,
                previousExposureState = ticket.exposureSource,
                diagnosticEvent = { event, details -> events.record(event, org.json.JSONObject(details),
                    event == "still_capture_failed" || event == "still_sequence_aborted" ||
                        event == "capture_setup_failure" ||
                        (event == "manual_state_mismatch" && details["fatal"] == true)) })
                .run(settings, count, directory, onFrame, maxAttempts, needsMore,
                    overlap = overlap, tryReserveNext = tryReserveNext, keepPreviewRunning = keepPreviewRunning,
                    shortPreviewExposure = shortPreviewExposure, queueAhead = queueAhead)
            synchronized(this) {
                check(cameraDevice === camera && (previewSession === configured || (separateStill && previewSession == null)) && rawReader === reader &&
                    previewSurface === surface && !cancel.get() && manualState.current(ticket)) { "Camera state invalidated during capture" }
                if (!experimental) manualState.complete(ticket, sequence.verified, sequence.verificationUnavailable) { !cancel.get() }
                dngPreviewControls = sequence.locked
                if (vendor != null) vendorFocusSeed = if (focusProbe == null) vendor.focusSeed else null
                shortPreview = shortPreviewExposure
            }
            succeeded = true
            return sequence
        } catch (error: Exception) {
            captureError = error
            events.record("capture_exception", runtimeEvidence().put("detail", error.toString().take(512))
                .put("errorChain", CameraDiagnostics.errorChain(error)), true)
            invalidateManualState(if (cancel.get()) "capture_cancelled" else
                (error as? ManualStateMismatch)?.reason ?: "capture_error")
            // A failed/cancelled lookahead can deliver a late buffer. Never let
            // that buffer enter the next job's reader or metadata matcher.
            reader.close()
            if (rawReader === reader) rawReader = null
            throw error
        } finally {
            runCatching { jpegReader?.setOnImageAvailableListener(null, null) }
            jpegReader?.close()
            val restoreError = if (vendorAttempted && cameraDevice === camera)
                runCatching { vendor!!.configure(camera, false) }.exceptionOrNull() else null
            if (experimental) invalidateManualState("experimental_capture_ended")
            synchronized(this) {
                if (activeCaptureCancel === cancel) { activeCaptureCancel = null; activeCaptureCleanup = null }
            }
            if (restoreError != null) {
                close("samsung_vendor_restore_failed")
                connectionLost?.invoke()
                if (captureError != null) captureError!!.addSuppressed(restoreError)
                else throw IllegalStateException("Samsung vendor state restoration failed; reconnect the camera", restoreError)
            }
            cameraHandler.post {
                if (cameraDevice === camera) {
                    if (succeeded && !experimental && previewSession === configured && previewSurface === surface) {
                        runCatching { repeatPreview(configured, surface) }.onFailure {
                            events.record("preview_restore_exception", org.json.JSONObject().put("detail", it.toString().take(512)), true)
                            invalidateManualState("preview_error")
                            previewReady = false; connectionLost?.invoke()
                        }
                    } else {
                        previewView?.takeIf { it.isAvailable }?.let { startPreview(it) }
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    @Synchronized fun open(onOpened: (Boolean) -> Unit) {
        val connectionLost = onConnectionLost
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            onOpened(false)
            return
        }

        val openedReported = AtomicBoolean(false)
        fun reportOpened(value: Boolean) {
            if (openedReported.compareAndSet(false, true)) onOpened(value)
        }
        try {
            cameraGeneration++
            events.record("camera_open_requested", runtimeEvidence())
            invalidateManualState("camera_opened")
            val openCancel = AtomicBoolean(false)
            pendingOpenCancel?.set(true)
            pendingOpenCancel = openCancel
            val id = selectedCameraId ?: selectBestCamera()
            val map = cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val rawSizes = map?.getOutputSizes(ImageFormat.RAW_SENSOR).orEmpty().toList()
            rawSize = rawSizes.filter { it.width.toLong() * it.height <= 13_000_000L }.maxByOrNull { it.width.toLong() * it.height }
                    ?: rawSizes.filter { it.width.toLong() * it.height <= 60_000_000L }.minByOrNull { it.width.toLong() * it.height }
            previewSize = map?.getOutputSizes(SurfaceTexture::class.java)
                ?.filter { it.width <= 1920 && it.height <= 1080 }
                ?.maxByOrNull { it.width.toLong() * it.height.toLong() }
                ?: map?.getOutputSizes(SurfaceTexture::class.java)?.firstOrNull()
            if (rawSize != null) {
                val sizes = map?.getOutputSizes(SurfaceTexture::class.java).orEmpty()
                PreviewGeometry.choose(sizes.map { PreviewGeometry.Size(it.width, it.height) }, rawSize!!.width, rawSize!!.height)
                    ?.let { previewSize = Size(it.width, it.height) }
            }
            if (rawSize == null) {
                pendingOpenCancel = null
                reportOpened(false)
                return
            }

            rawReader?.close()
            rawReader = rawSize?.let { ImageReader.newInstance(it.width, it.height, ImageFormat.RAW_SENSOR, 4) }

            cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    val accepted = synchronized(this@CameraController) {
                        if (openCancel.get() || pendingOpenCancel !== openCancel) {
                            camera.close()
                            false
                        } else {
                            cameraDevice = camera
                            pendingOpenCancel = null
                            true
                        }
                    }
                    reportOpened(accepted)
                    events.record("camera_opened", org.json.JSONObject().put("accepted", accepted))
                }

                override fun onDisconnected(camera: CameraDevice) {
                    val current = synchronized(this@CameraController) {
                        val owns = cameraDevice === camera || pendingOpenCancel === openCancel
                        if (owns) {
                            events.record("camera_disconnected", runtimeEvidence(), fault = true)
                            previewReady = false
                        }
                        if (cameraDevice === camera) { cancelCurrentCapture("camera_disconnected"); cameraDevice = null; invalidateManualState("camera_disconnected") }
                        if (pendingOpenCancel === openCancel) pendingOpenCancel = null
                        owns
                    }
                    camera.close()
                    reportOpened(false)
                    if (current) connectionLost?.invoke()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    val current = synchronized(this@CameraController) {
                        val owns = cameraDevice === camera || pendingOpenCancel === openCancel
                        if (owns) {
                            events.record("camera_error", runtimeEvidence().put("code", error)
                                .put("detail", CameraDiagnostics.errorName(error)).put("cameraID", camera.id), true)
                            previewReady = false
                        }
                        if (cameraDevice === camera) { cancelCurrentCapture("camera_error"); cameraDevice = null; invalidateManualState("camera_error") }
                        if (pendingOpenCancel === openCancel) pendingOpenCancel = null
                        owns
                    }
                    camera.close()
                    reportOpened(false)
                    if (current) connectionLost?.invoke()
                }
            }, cameraHandler)
        } catch (e: Exception) {
            events.record("camera_open_exception", org.json.JSONObject().put("detail", e.toString().take(512)), true)
            pendingOpenCancel = null
            reportOpened(false)
        }
    }

    fun startPreview(textureView: TextureView, onError: (String) -> Unit = {}) {
        sessionGeneration++
        events.record("preview_configure_requested", runtimeEvidence())
        val connectionLost = onConnectionLost
        invalidateManualState("session_recreated")
        previewReady = false
        previewView = textureView
        // The capture's finally block restarts preview after its writer/reader drain.
        // Never configure a replacement session around a still-owned RAW reader.
        activeCaptureCancel?.let { cancelCurrentCapture("preview_reconfiguration"); return }
        val camera = cameraDevice ?: return
        val texture = textureView.surfaceTexture ?: return
        val size = previewSize ?: Size(textureView.width.coerceAtLeast(640), textureView.height.coerceAtLeast(480))
        texture.setDefaultBufferSize(size.width, size.height)
        val surface = Surface(texture)

        try {
            val reader = rawReader ?: requireNotNull(rawSize).let {
                ImageReader.newInstance(it.width, it.height, ImageFormat.RAW_SENSOR, 4).also { replacement -> rawReader = replacement }
            }
            previewSession?.close()
            previewSession = null
            previewSurface?.release()
            previewSurface = surface
            camera.createCaptureSession(listOf(surface, reader.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (previewSurface !== surface || cameraDevice !== camera || rawReader !== reader) {
                        session.close()
                        return
                    }
                    previewSession = session
                    try {
                        repeatPreview(session, surface)
                    } catch (e: Exception) {
                        events.record("preview_exception", org.json.JSONObject().put("detail", e.toString().take(512)), true)
                        invalidateManualState("preview_error")
                        onError(e.message ?: "preview request failed")
                        connectionLost?.invoke()
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    session.close()
                    if (previewSurface === surface) {
                        events.record("preview_configuration_failed", fault = true)
                        invalidateManualState("session_configuration_failed")
                        previewSurface = null
                        surface.release()
                        onError("preview session configure failed")
                        connectionLost?.invoke()
                    }
                }
                override fun onClosed(session: CameraCaptureSession) {
                    if (previewSession === session) {
                        events.record("active_session_closed", runtimeEvidence(), fault = true)
                        previewReady = false
                        previewSession = null
                        invalidateManualState("session_closed")
                        cancelCurrentCapture("active_session_closed")
                    }
                }
            }, cameraHandler)
        } catch (e: Exception) {
            events.record("preview_start_exception", org.json.JSONObject().put("detail", e.toString().take(512)), true)
            if (previewSurface === surface) {
                previewSurface = null
                surface.release()
            }
            onError(e.message ?: "preview failed")
            connectionLost?.invoke()
        }
    }

    private fun repeatPreview(session: CameraCaptureSession, surface: Surface) {
        val connectionLost = onConnectionLost
        val camera = requireNotNull(cameraDevice)
        val generation = ++previewRequestGeneration
        lastPreviewResult = null
        lastPreviewElapsedNs = 0L
        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        builder.addTarget(surface)
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        val modes = selectedCharacteristics().get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        val previewAfMode = listOf(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO, CaptureRequest.CONTROL_AF_MODE_AUTO,
            CaptureRequest.CONTROL_AF_MODE_OFF).firstOrNull { it in modes }
            ?: error("Camera advertises no supported preview focus mode")
        builder.set(CaptureRequest.CONTROL_AF_MODE, previewAfMode)
        dngPreviewControls?.let { DngCapture.applyLocked(builder,
            it.copy(exposureNs=com.uniformitycomp.capture.ShortPreviewExposure.exposure(it.exposureNs, shortPreview)),
            selectedCharacteristics(), reportLensShading = true) }
        session.setRepeatingRequest(builder.build(), object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                if (previewSession === session && previewSurface === surface && cameraDevice === camera &&
                    previewRequestGeneration == generation && activeCaptureCancel == null) {
                    val first = lastPreviewElapsedNs == 0L
                    lastPreviewResult = result
                    lastPreviewElapsedNs = android.os.SystemClock.elapsedRealtimeNanos()
                    if (first) events.record("preview_first_result", runtimeEvidence().put("frameNumber", result.frameNumber))
                }
            }
            override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                if (previewSession === session && previewRequestGeneration == generation && activeCaptureCancel == null) {
                    events.record("preview_capture_failed", org.json.JSONObject().put("reason", failure.reason)
                        .put("frameNumber", failure.frameNumber).put("sequenceId", failure.sequenceId), true)
                    invalidateManualState("preview_capture_failed")
                    previewReady = false
                    connectionLost?.invoke()
                }
            }
        }, cameraHandler)
        previewReady = true
        events.record("preview_ready")
    }

    @Synchronized fun cancelCurrentCapture(reason: String = "unspecified") {
        events.record("capture_cancel_requested", runtimeEvidence().put("reason", reason.take(80))
            .put("alreadyCancelled", activeCaptureCancel?.get() ?: false))
        invalidateManualState("capture_cancelled")
        activeCaptureCancel?.set(true)
        activeCaptureCleanup?.invoke()
    }

    @Synchronized fun close(reason: String = "camera_close") {
        events.record("camera_close_requested", runtimeEvidence().put("reason", reason.take(80)))
        invalidateManualState("camera_closed_or_backgrounded")
        lastPreviewResult = null
        lastPreviewElapsedNs = 0L
        previewReady = false
        pendingOpenCancel?.set(true)
        pendingOpenCancel = null
        cancelCurrentCapture(reason)
        previewSession?.close()
        previewSession = null
        previewSurface?.release()
        previewSurface = null
        cameraDevice?.close()
        cameraDevice = null
        runCatching { rawReader?.setOnImageAvailableListener(null, null) }
        rawReader?.close()
        rawReader = null
    }

    fun shutdown() {
        close("activity_destroyed")
        cameraThread.quitSafely()
        resultThread.quitSafely()
    }

    private fun registerCapture(cancel: AtomicBoolean, cleanup: () -> Unit) {
        synchronized(this) {
            check(activeCaptureCancel == null) { "Previous capture is still draining" }
            activeCaptureCancel = cancel
            activeCaptureCleanup = cleanup
        }
    }

    @Synchronized private fun invalidateManualState(reason: String) {
        // A seed is only a starting request; fresh result verification remains mandatory.
        if (reason != "experimental_capture_setup" && reason != "experimental_capture_ended" &&
            reason != "session_recreated") vendorFocusSeed = null
        manualState.invalidate(reason)
        dngPreviewControls = null
        shortPreview = false
    }

}
