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
class CameraController(private val context: Context) {
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
    private val manualState = ManualCaptureCache<DngVerifiedState>()
    @Volatile private var previewRequestGeneration = 0L
    @Volatile var previewReady = false
        private set
    @Volatile var lastPreviewResult: TotalCaptureResult? = null
        private set
    @Volatile private var activeCaptureCancel: AtomicBoolean? = null
    @Volatile private var activeCaptureCleanup: (() -> Unit)? = null
    @Volatile private var pendingOpenCancel: AtomicBoolean? = null
    @Volatile var onConnectionLost: (() -> Unit)? = null

    private fun selectBestCamera(): String {
        val id = rawCameraChoices().firstOrNull()?.first ?: error("No rear RAW camera is available")
        selectedCameraId = id
        return id
    }

    fun cameraID(): String = selectedCameraId ?: selectBestCamera()

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

    /** Ordered original DNG capture with optional exposure overlap; the caller retains completed files. */
    fun captureDngOriginals(settings: DngSettings, count: Int, directory: java.io.File?, cancel: AtomicBoolean,
                            maxAttempts: Int = count, needsMore: (() -> Boolean)? = null,
                            overlap: Boolean = false, tryReserveNext: (() -> Boolean)? = null,
                            onFrame: (DngFrame) -> Unit): DngSequence {
        val camera = requireNotNull(cameraDevice) { "Camera is not open" }
        val connectionLost = onConnectionLost
        val size = requireNotNull(rawSize) { "No supported RAW size" }
        val reader = requireNotNull(rawReader) { "RAW reader is not ready" }
        check(reader.width == size.width && reader.height == size.height) { "RAW stream changed" }
        val surface = requireNotNull(previewSurface) { "Start the aiming preview first" }
        val configured = requireNotNull(previewSession) { "Preview session is not ready" }
        registerCapture(cancel) { cancel.set(true) }
        val ticket = manualState.begin(ManualRequestKey.from(settings.exposureNs, settings.iso,
            settings.focusFraction, settings.whiteBalance), ManualCaptureScope(camera, configured, reader, surface), count > 0)
        previewReady = false
        var succeeded = false
        try {
            val sequence = DngCapture(camera, reader, surface, selectedCharacteristics(), resultHandler, cancel, reportLensShading = true,
                existingSession = configured, previousLocked = dngPreviewControls,
                previousVerified = ticket.reused, setupFallbackReason = ticket.reason,
                previousExposureState = ticket.exposureSource)
                .run(settings, count, directory, onFrame, maxAttempts, needsMore,
                    overlap = overlap, tryReserveNext = tryReserveNext)
            synchronized(this) {
                check(cameraDevice === camera && previewSession === configured && rawReader === reader &&
                    previewSurface === surface && !cancel.get() && manualState.current(ticket)) { "Camera state invalidated during capture" }
                manualState.complete(ticket, sequence.verified, sequence.verificationUnavailable) { !cancel.get() }
                dngPreviewControls = sequence.locked
            }
            succeeded = true
            return sequence
        } catch (error: Exception) {
            invalidateManualState(if (cancel.get()) "capture_cancelled" else
                (error as? ManualStateMismatch)?.reason ?: "capture_error")
            // A failed/cancelled lookahead can deliver a late buffer. Never let
            // that buffer enter the next job's reader or metadata matcher.
            reader.close()
            if (rawReader === reader) rawReader = null
            throw error
        } finally {
            synchronized(this) {
                if (activeCaptureCancel === cancel) { activeCaptureCancel = null; activeCaptureCleanup = null }
            }
            cameraHandler.post {
                if (cameraDevice === camera) {
                    if (succeeded && previewSession === configured && previewSurface === surface) {
                        runCatching { repeatPreview(configured, surface) }.onFailure {
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
    fun open(onOpened: (Boolean) -> Unit) {
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
                    if (openCancel.get()) {
                        camera.close()
                        reportOpened(false)
                        return
                    }
                    cameraDevice = camera
                    if (pendingOpenCancel === openCancel) pendingOpenCancel = null
                    reportOpened(true)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    val current = cameraDevice === camera || pendingOpenCancel === openCancel
                    if (cameraDevice === camera) { cancelCurrentCapture(); cameraDevice = null; invalidateManualState("camera_disconnected") }
                    if (pendingOpenCancel === openCancel) pendingOpenCancel = null
                    reportOpened(false)
                    if (current) connectionLost?.invoke()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    val current = cameraDevice === camera || pendingOpenCancel === openCancel
                    if (cameraDevice === camera) { cancelCurrentCapture(); cameraDevice = null; invalidateManualState("camera_error") }
                    if (pendingOpenCancel === openCancel) pendingOpenCancel = null
                    reportOpened(false)
                    if (current) connectionLost?.invoke()
                }
            }, cameraHandler)
        } catch (e: Exception) {
            pendingOpenCancel = null
            reportOpened(false)
        }
    }

    fun startPreview(textureView: TextureView, onError: (String) -> Unit = {}) {
        val connectionLost = onConnectionLost
        invalidateManualState("session_recreated")
        previewReady = false
        previewView = textureView
        // The capture's finally block restarts preview after its writer/reader drain.
        // Never configure a replacement session around a still-owned RAW reader.
        activeCaptureCancel?.let { it.set(true); return }
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
                        invalidateManualState("preview_error")
                        onError(e.message ?: "preview request failed")
                        connectionLost?.invoke()
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    session.close()
                    if (previewSurface === surface) {
                        invalidateManualState("session_configuration_failed")
                        previewSurface = null
                        surface.release()
                        onError("preview session configure failed")
                        connectionLost?.invoke()
                    }
                }
                override fun onClosed(session: CameraCaptureSession) {
                    if (previewSession === session) {
                        previewReady = false
                        previewSession = null
                        invalidateManualState("session_closed")
                        activeCaptureCancel?.set(true)
                    }
                }
            }, cameraHandler)
        } catch (e: Exception) {
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
        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        builder.addTarget(surface)
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        val modes = selectedCharacteristics().get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        val previewAfMode = listOf(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO, CaptureRequest.CONTROL_AF_MODE_AUTO,
            CaptureRequest.CONTROL_AF_MODE_OFF).firstOrNull { it in modes }
            ?: error("Camera advertises no supported preview focus mode")
        builder.set(CaptureRequest.CONTROL_AF_MODE, previewAfMode)
        dngPreviewControls?.let { DngCapture.applyLocked(builder, it, selectedCharacteristics(), reportLensShading = true) }
        session.setRepeatingRequest(builder.build(), object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                if (previewSession === session && previewSurface === surface && cameraDevice === camera &&
                    previewRequestGeneration == generation && activeCaptureCancel == null) lastPreviewResult = result
            }
            override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                if (previewSession === session && previewRequestGeneration == generation && activeCaptureCancel == null) {
                    invalidateManualState("preview_capture_failed")
                    previewReady = false
                    connectionLost?.invoke()
                }
            }
        }, cameraHandler)
        previewReady = true
    }

    @Synchronized fun cancelCurrentCapture() {
        invalidateManualState("capture_cancelled")
        activeCaptureCancel?.set(true)
        activeCaptureCleanup?.invoke()
    }

    fun close() {
        invalidateManualState("camera_closed_or_backgrounded")
        lastPreviewResult = null
        previewReady = false
        pendingOpenCancel?.set(true)
        pendingOpenCancel = null
        cancelCurrentCapture()
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
        close()
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
        manualState.invalidate(reason)
        dngPreviewControls = null
    }

}
