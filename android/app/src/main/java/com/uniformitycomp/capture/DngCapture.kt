package com.uniformitycomp.capture

import android.hardware.camera2.*
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.SystemClock
import android.view.Surface
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Settings are camera requests. Only timestamp-matched TotalCaptureResults are evidence. */
data class DngSettings(
    val exposureNs: Long? = null,
    val iso: Int? = null,
    val focusFraction: Float? = null,
    val whiteBalance: FloatArray? = null,
    val settleMs: Long = 750
)

data class DngLockedSettings(
    val exposureNs: Long,
    val iso: Int,
    val focusDiopters: Float,
    val gains: RggbChannelVector,
    val transform: ColorSpaceTransform
)

data class DngFrame(val file: File, val result: TotalCaptureResult, val width: Int, val height: Int,
                    val imageTimestampNs: Long, val diagnostics: Map<String, Any> = emptyMap())
data class DngSequence(val locked: DngLockedSettings, val reading: TotalCaptureResult, val frames: List<DngFrame>)

/** One original at a time, bounded queues, no averaging and no demosaicing. Called off the UI thread. */
internal class DngCapture(
    private val camera: CameraDevice,
    private val reader: ImageReader,
    private val preview: Surface,
    private val characteristics: CameraCharacteristics,
    private val handler: Handler,
    private val cancel: AtomicBoolean,
    private val reportLensShading: Boolean = false
) {
    private val failure = AtomicReference<Throwable?>()
    private data class RawArrival(val image: Image, val elapsedNs: Long,
        val sampledElapsedNs: Long, val samples: Map<String, Any>, val queueDepth: Int)
    private val images = ArrayBlockingQueue<RawArrival>(2)
    private val results = ArrayBlockingQueue<TotalCaptureResult>(16)
    private val timings = ConcurrentHashMap<Int, ConcurrentHashMap<String, Any>>()
    private var session: CameraCaptureSession? = null
    private val ended = AtomicBoolean(false)

    private fun rawSample(image: Image): Map<String, Any> = runCatching {
        val plane = image.planes.single()
        RawBufferDiagnostics.sample(plane.buffer, image.width, image.height, plane.rowStride, plane.pixelStride)
    }.getOrElse { mapOf("unavailable" to (it.message ?: it.javaClass.simpleName).take(240)) }

    private fun check() {
        if (cancel.get()) throw InterruptedException("Capture cancelled; partial originals retained")
        failure.get()?.let { throw IllegalStateException("Camera capture failed", it) }
    }

    private fun <T> await(queue: ArrayBlockingQueue<T>, seconds: Long = 45): T {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (System.nanoTime() < deadline) {
            check()
            queue.poll(100, TimeUnit.MILLISECONDS)?.let { return it }
        }
        throw IllegalStateException("Camera timed out; partial originals retained")
    }

    private val callback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureStarted(s: CameraCaptureSession, r: CaptureRequest, timestamp: Long, frameNumber: Long) {
            (r.tag as? Int)?.let { tag -> timings[tag]?.apply {
                put("captureStartedElapsedNs", SystemClock.elapsedRealtimeNanos())
                put("captureStartedSensorTimestampNs", timestamp)
                put("captureStartedFrameNumber", frameNumber)
            } }
        }
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
            (r.tag as? Int)?.let { tag -> timings[tag]?.apply {
                put("resultArrivedElapsedNs", SystemClock.elapsedRealtimeNanos())
                put("resultFrameNumber", result.frameNumber)
            } }
            if (!results.offer(result)) { results.poll(); results.offer(result) }
        }
        override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, f: CaptureFailure) {
            failure.compareAndSet(null, IllegalStateException("Capture failure ${f.reason}"))
        }
        override fun onCaptureSequenceAborted(s: CameraCaptureSession, sequenceId: Int) {
            failure.compareAndSet(null, IllegalStateException("Capture sequence aborted"))
        }
    }

    fun run(settings: DngSettings, count: Int, directory: File?, onFrame: (DngFrame) -> Unit,
            maxAttempts: Int = count, needsMore: (() -> Boolean)? = null): DngSequence {
        require(count in 0..32)
        require(maxAttempts in count..64 && (needsMore != null || maxAttempts == count))
        val caps = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        require(caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) &&
            caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) &&
            caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING)) {
            "This camera must support RAW, manual sensor and manual post-processing controls"
        }
        require(isBayer(characteristics)) { "This camera does not advertise an RGB Bayer sensor" }
        val sessions = ArrayBlockingQueue<CameraCaptureSession>(1)
        try {
            reader.setOnImageAvailableListener({ source ->
                val image = try { source.acquireNextImage() } catch (error: Exception) {
                    if (!ended.get() && !cancel.get()) failure.compareAndSet(null, error)
                    null
                }
                if (image != null) {
                    val acquired = SystemClock.elapsedRealtimeNanos()
                    val samples = if (ended.get() || cancel.get()) emptyMap() else rawSample(image)
                    val sampled = SystemClock.elapsedRealtimeNanos()
                    synchronized(images) {
                        if (ended.get() || cancel.get()) image.close()
                        else if (!images.offer(RawArrival(image, acquired, sampled, samples, images.size))) {
                            image.close()
                            failure.compareAndSet(null, IllegalStateException("RAW queue overflow"))
                        }
                    }
                }
            }, handler)
            camera.createCaptureSession(listOf(preview, reader.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (cancel.get() || ended.get()) s.close() else sessions.offer(s)
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    s.close(); failure.set(IllegalStateException("RAW + preview session is unsupported"))
                }
            }, handler)
            val active = await(sessions)
            session = active
            val auto = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            auto.addTarget(preview)
            auto.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            val modes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
            val autoFocusMode = when {
                modes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                modes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) -> CaptureRequest.CONTROL_AF_MODE_AUTO
                else -> CaptureRequest.CONTROL_AF_MODE_OFF
            }
            auto.set(CaptureRequest.CONTROL_AF_MODE, autoFocusMode)
            applySupportedProcessing(auto, characteristics, reportLensShading)
            // A fully specified job never meters a new exposure on the next display pattern.
            settings.exposureNs?.let { auto.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF); auto.set(CaptureRequest.SENSOR_EXPOSURE_TIME, it) }
            settings.iso?.let { auto.set(CaptureRequest.SENSOR_SENSITIVITY, it) }
            val minFocus = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            settings.focusFraction?.let {
                auto.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                auto.set(CaptureRequest.LENS_FOCUS_DISTANCE, it * minFocus)
            }
            active.setRepeatingRequest(auto.build(), callback, handler)
            if (settings.focusFraction == null && autoFocusMode == CaptureRequest.CONTROL_AF_MODE_AUTO) {
                auto.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                active.capture(auto.build(), callback, handler)
            }
            val settleStart = System.nanoTime()
            var observed: TotalCaptureResult
            do {
                observed = await(results)
                val ae = observed.get(CaptureResult.CONTROL_AE_STATE)
                val af = observed.get(CaptureResult.CONTROL_AF_STATE)
                val awb = observed.get(CaptureResult.CONTROL_AWB_STATE)
                val ready = (settings.exposureNs != null || ae == CaptureResult.CONTROL_AE_STATE_CONVERGED || ae == CaptureResult.CONTROL_AE_STATE_LOCKED) &&
                    (settings.focusFraction != null || minFocus == 0f || af == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED || af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED) &&
                    (settings.whiteBalance != null || awb == CaptureResult.CONTROL_AWB_STATE_CONVERGED || awb == CaptureResult.CONTROL_AWB_STATE_LOCKED)
                val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - settleStart)
                if (ready && elapsed >= settings.settleMs.coerceAtLeast(250)) break
                check(elapsed < 30_000) { "Auto controls did not settle; choose manual settings" }
            } while (true)
            val gains = settings.whiteBalance?.let { RggbChannelVector(it[0], it[1], it[1], it[2]) }
                ?: requireNotNull(observed.get(CaptureResult.COLOR_CORRECTION_GAINS)) { "Missing actual white balance gains" }
            val locked = DngLockedSettings(
                settings.exposureNs ?: requireNotNull(observed.get(CaptureResult.SENSOR_EXPOSURE_TIME)),
                settings.iso ?: requireNotNull(observed.get(CaptureResult.SENSOR_SENSITIVITY)),
                settings.focusFraction?.times(minFocus) ?: requireNotNull(observed.get(CaptureResult.LENS_FOCUS_DISTANCE)),
                gains, requireNotNull(observed.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)) { "Missing color transform" })
            val manual = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            manual.addTarget(preview)
            applyLocked(manual, locked, characteristics, reportLensShading)
            active.stopRepeating()
            results.clear()
            active.setRepeatingRequest(manual.build(), callback, handler)
            // Reject queued auto results; wait for controls actually reported by the locked request.
            val lockStart = System.nanoTime()
            do {
                observed = await(results)
                if (observed.request.get(CaptureRequest.CONTROL_AE_MODE) == CaptureRequest.CONTROL_AE_MODE_OFF &&
                    observed.request.get(CaptureRequest.CONTROL_AWB_MODE) == CaptureRequest.CONTROL_AWB_MODE_OFF &&
                    System.nanoTime() - lockStart >= TimeUnit.MILLISECONDS.toNanos(settings.settleMs.coerceAtLeast(250))) break
                check(System.nanoTime() - lockStart < TimeUnit.SECONDS.toNanos(45)) { "Manual controls did not settle" }
            } while (true)
            val frames = mutableListOf<DngFrame>()
            if (count > 0) {
                requireNotNull(directory)
                check(directory.isDirectory || directory.mkdirs()) { "Cannot create capture archive" }
                active.stopRepeating()
                results.clear()
                var physicalId: String? = null
                var previousSensorTimestamp: Long? = null
                var previousCloseElapsed: Long? = null
                var previousSampleHash: String? = null
                for (index in 0 until maxAttempts) {
                    if (needsMore != null && !needsMore()) break
                    check()
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                    request.addTarget(reader.surface)
                    applyLocked(request, locked, characteristics, reportLensShading)
                    request.setTag(index)
                    val timing = ConcurrentHashMap<String, Any>()
                    timing["diagnosticsVersion"] = 2
                    timing["requestIndex"] = index+1
                    timing["imageReaderMaxImages"] = reader.maxImages
                    timing["requestTemplate"] = "TEMPLATE_STILL_CAPTURE"
                    timing["repeatingPreviewDuringStill"] = false
                    timing["requestedExposureNs"] = locked.exposureNs
                    timing["requestedISO"] = locked.iso
                    timing["requestSubmittedElapsedNs"] = SystemClock.elapsedRealtimeNanos()
                    previousCloseElapsed?.let { timing["previousImageClosedElapsedNs"] = it }
                    timings[index] = timing
                    val sequenceId = active.capture(request.build(), callback, handler)
                    timing["captureSequenceId"] = sequenceId
                    val arrival = await(images)
                    val image = arrival.image
                    val frame = try {
                        var result: TotalCaptureResult
                        do { result = await(results) } while (result.get(CaptureResult.SENSOR_TIMESTAMP) != image.timestamp)
                        check(result.request.tag == index) { "RAW image/result identity mismatch" }
                        timing["imageArrivedElapsedNs"] = arrival.elapsedNs
                        timing["acquisitionSamplingEndedElapsedNs"] = arrival.sampledElapsedNs
                        timing["imageResultMatchedElapsedNs"] = SystemClock.elapsedRealtimeNanos()
                        timing["imageQueueDepthAtArrival"] = arrival.queueDepth
                        timing["rawAtAcquisition"] = arrival.samples
                        val sampleHash = arrival.samples["sampleSha256"] as? String
                        if (sampleHash != null && previousSampleHash != null) {
                            timing["sameSamplesAsPreviousFrame"] = sampleHash == previousSampleHash
                        }
                        previousSampleHash = sampleHash
                        timing["imageTimestampNs"] = image.timestamp
                        timing["imageFormat"] = image.format
                        timing["imagePlaneCount"] = image.planes.size
                        previousSensorTimestamp?.let { timing["sensorIntervalNs"] = image.timestamp-it }
                        previousSensorTimestamp = image.timestamp
                        if (android.os.Build.VERSION.SDK_INT >= 29) {
                            val actualId = result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
                            if (index == 0) physicalId = actualId
                            check(physicalId == actualId) { "Physical camera changed during burst" }
                        }
                        val file = File(directory, "frame-%04d.dng".format(java.util.Locale.ROOT, index + 1))
                        val pending = File(directory, file.name + ".partial")
                        val before = rawSample(image)
                        timing["rawBeforeDng"] = before
                        timing["dngWriteStartedElapsedNs"] = SystemClock.elapsedRealtimeNanos()
                        DngCreator(characteristics, result).use { creator ->
                            // Pixels retain native sensor coordinates. Preview orientation is independent.
                            creator.setOrientation(1)
                            pending.outputStream().use { creator.writeImage(it, image) }
                        }
                        timing["dngWriteEndedElapsedNs"] = SystemClock.elapsedRealtimeNanos()
                        val after = rawSample(image)
                        timing["rawAfterDng"] = after
                        check(pending.length() in 1..(128L * 1024 * 1024)) { "DNG exceeds file limit" }
                        check(pending.renameTo(file)) { "Cannot finalize DNG original" }
                        timing["dngBytes"] = file.length()
                        timing["dngReadbackStartedElapsedNs"] = SystemClock.elapsedRealtimeNanos()
                        val saved = runCatching { DngSampleDiagnostics.sample(file, image.width, image.height) }
                            .getOrElse { mapOf("unavailable" to (it.message ?: it.javaClass.simpleName).take(240)) }
                        timing["dngReadbackEndedElapsedNs"] = SystemClock.elapsedRealtimeNanos()
                        timing["rawSavedDng"] = saved
                        timing["sampleIntegrity"] = RawBufferDiagnostics.compare(arrival.samples, before, after, saved)
                        DngFrame(file, result, image.width, image.height, image.timestamp)
                    } finally {
                        image.close()
                        previousCloseElapsed = SystemClock.elapsedRealtimeNanos()
                        timing["imageClosedElapsedNs"] = previousCloseElapsed!!
                        timings.remove(index)
                    }
                    val recorded = frame.copy(diagnostics = timing.toMap())
                    frames.add(recorded)
                    onFrame(recorded)
                }
            }
            return DngSequence(locked, observed, frames)
        } finally {
            ended.set(true)
            session?.close()
            sessions.poll()?.close()
            reader.setOnImageAvailableListener(null, null)
            // A callback already sampling an image may outlive listener removal.
            synchronized(images) { while (true) (images.poll() ?: break).image.close() }
        }
    }

    companion object {
        fun isBayer(c: CameraCharacteristics): Boolean = c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) in setOf(
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB,
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG,
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG,
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR)

        fun applyLocked(builder: CaptureRequest.Builder, value: DngLockedSettings, c: CameraCharacteristics,
                        reportLensShading: Boolean = false) {
            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, value.exposureNs)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, value.iso)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, value.focusDiopters)
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, value.gains)
            builder.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, value.transform)
            applySupportedProcessing(builder, c, reportLensShading)
        }

        fun lensShadingMode(c: CameraCharacteristics): Int? {
            if (!c.availableCaptureRequestKeys.contains(CaptureRequest.SHADING_MODE) ||
                !c.availableCaptureRequestKeys.contains(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE) ||
                c.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES)
                    ?.contains(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON) != true) return null
            val modes = c.get(CameraCharacteristics.SHADING_AVAILABLE_MODES) ?: intArrayOf()
            return listOf(CaptureRequest.SHADING_MODE_HIGH_QUALITY, CaptureRequest.SHADING_MODE_FAST).firstOrNull { it in modes }
        }

        private fun applySupportedProcessing(builder: CaptureRequest.Builder, c: CameraCharacteristics, reportLensShading: Boolean) {
            fun mode(key: CaptureRequest.Key<Int>, available: CameraCharacteristics.Key<IntArray>, wanted: Int) {
                if (c.availableCaptureRequestKeys.contains(key) && c.get(available)?.contains(wanted) == true) builder.set(key, wanted)
            }
            mode(CaptureRequest.NOISE_REDUCTION_MODE, CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
            mode(CaptureRequest.HOT_PIXEL_MODE, CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES, CaptureRequest.HOT_PIXEL_MODE_OFF)
            mode(CaptureRequest.EDGE_MODE, CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES, CaptureRequest.EDGE_MODE_OFF)
            val shading = if (reportLensShading) lensShadingMode(c) else null
            mode(CaptureRequest.SHADING_MODE, CameraCharacteristics.SHADING_AVAILABLE_MODES, shading ?: CaptureRequest.SHADING_MODE_OFF)
            mode(CaptureRequest.DISTORTION_CORRECTION_MODE, CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES, CaptureRequest.DISTORTION_CORRECTION_MODE_OFF)
            mode(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
            mode(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            // OFF shading can yield an identity map. With FAST/HQ, Camera2
            // reports the remaining RAW correction, including on devices that
            // already apply some shading. DngCreator embeds it; never bake it
            // into original samples here. Legacy callers retain the old policy.
            mode(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES,
                if (shading != null) CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON else CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_OFF)
        }
    }
}
