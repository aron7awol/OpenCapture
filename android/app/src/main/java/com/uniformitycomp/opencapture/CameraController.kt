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
        dngPreviewControls = null
    }

    fun selectedCharacteristics(): CameraCharacteristics = cameraManager.getCameraCharacteristics(
        selectedCameraId ?: selectBestCamera())

    fun previewDimensions(): Size? = previewSize

    /** Sequential original DNG capture; the caller retains each completed file. */
    fun captureDngOriginals(settings: DngSettings, count: Int, directory: java.io.File?, cancel: AtomicBoolean,
                            onFrame: (DngFrame) -> Unit): DngSequence {
        val camera = requireNotNull(cameraDevice) { "Camera is not open" }
        val reader = requireNotNull(rawReader) { "No supported RAW size" }
        val surface = requireNotNull(previewSurface) { "Start the aiming preview first" }
        registerCapture(cancel) { cancel.set(true) }
        previewSession?.close()
        previewSession = null
        previewReady = false
        try {
            val sequence = DngCapture(camera, reader, surface, selectedCharacteristics(), resultHandler, cancel)
                .run(settings, count, directory, onFrame)
            dngPreviewControls = sequence.locked
            return sequence
        } finally {
            activeCaptureCancel = null
            activeCaptureCleanup = null
            cameraHandler.post { if (cameraDevice === camera) previewView?.takeIf { it.isAvailable }?.let { startPreview(it) } }
        }
    }

    @SuppressLint("MissingPermission")
    fun open(onOpened: (Boolean) -> Unit) {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            onOpened(false)
            return
        }

        val openedReported = AtomicBoolean(false)
        fun reportOpened(value: Boolean) {
            if (openedReported.compareAndSet(false, true)) onOpened(value)
        }
        try {
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
                    if (cameraDevice === camera) cameraDevice = null
                    if (pendingOpenCancel === openCancel) pendingOpenCancel = null
                    reportOpened(false)
                    onConnectionLost?.invoke()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (cameraDevice === camera) cameraDevice = null
                    if (pendingOpenCancel === openCancel) pendingOpenCancel = null
                    reportOpened(false)
                    onConnectionLost?.invoke()
                }
            }, cameraHandler)
        } catch (e: Exception) {
            pendingOpenCancel = null
            reportOpened(false)
        }
    }

    fun startPreview(textureView: TextureView, onError: (String) -> Unit = {}) {
        previewReady = false
        previewView = textureView
        val camera = cameraDevice ?: return
        val texture = textureView.surfaceTexture ?: return
        val size = previewSize ?: Size(textureView.width.coerceAtLeast(640), textureView.height.coerceAtLeast(480))
        texture.setDefaultBufferSize(size.width, size.height)
        val surface = Surface(texture)

        try {
            previewSession?.close()
            previewSession = null
            previewSurface?.release()
            previewSurface = surface
            camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (previewSurface !== surface) {
                        session.close()
                        return
                    }
                    previewSession = session
                    try {
                        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        builder.addTarget(surface)
                        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        val modes = selectedCharacteristics().get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
                        val previewAfMode = listOf(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO, CaptureRequest.CONTROL_AF_MODE_AUTO,
                            CaptureRequest.CONTROL_AF_MODE_OFF).firstOrNull { it in modes }
                            ?: error("Camera advertises no supported preview focus mode")
                        builder.set(CaptureRequest.CONTROL_AF_MODE, previewAfMode)
                        dngPreviewControls?.let { DngCapture.applyLocked(builder, it, selectedCharacteristics()) }
                        session.setRepeatingRequest(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureCompleted(
                                session: CameraCaptureSession,
                                request: CaptureRequest,
                                result: TotalCaptureResult
                            ) {
                                lastPreviewResult = result
                            }
                        }, cameraHandler)
                        previewReady = true
                    } catch (e: Exception) {
                        onError(e.message ?: "preview request failed")
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    session.close()
                    if (previewSurface === surface) {
                        previewSurface = null
                        surface.release()
                    }
                    onError("preview session configure failed")
                }
            }, cameraHandler)
        } catch (e: Exception) {
            if (previewSurface === surface) {
                previewSurface = null
                surface.release()
            }
            onError(e.message ?: "preview failed")
        }
    }

    fun cancelCurrentCapture() {
        activeCaptureCancel?.set(true)
        activeCaptureCleanup?.invoke()
    }

    fun close() {
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
            activeCaptureCancel?.set(true)
            activeCaptureCleanup?.invoke()
            activeCaptureCancel = cancel
            activeCaptureCleanup = cleanup
        }
    }

}
