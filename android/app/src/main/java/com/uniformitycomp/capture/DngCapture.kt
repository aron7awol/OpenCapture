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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
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

/** Sparse RAW16 diagnostics only; never alters pixels, buffer position or capture decisions. */
internal object RawBufferDiagnostics {
    fun sample(buffer: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int): Map<String, Any> {
        require(width >= 2 && height >= 2 && pixelStride >= 2 && rowStride >= width.toLong()*pixelStride)
        val data = buffer.duplicate().order(ByteOrder.nativeOrder())
        require((height-1L)*rowStride+(width-1L)*pixelStride+2 <= data.limit())
        val nx = minOf(96, width/2)
        val ny = minOf(64, height/2)
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0
        var mean = 0.0
        var m2 = 0.0
        var low = 65535
        var high = 0
        // Sample all four Bayer sites, including both greens, in each block.
        for (gy in 0 until ny) for (gx in 0 until nx) {
            val x = (gx.toLong()*(width/2-1)/(nx-1).coerceAtLeast(1)).toInt()*2
            val y = (gy.toLong()*(height/2-1)/(ny-1).coerceAtLeast(1)).toInt()*2
            for (dy in 0..1) for (dx in 0..1) {
                val value = data.getShort((y+dy)*rowStride+(x+dx)*pixelStride).toInt() and 65535
                count++
                val delta = value-mean
                mean += delta/count
                m2 += delta*(value-mean)
                low = minOf(low,value); high = maxOf(high,value)
                // Canonical low-byte-first hash independent of the buffer view.
                digest.update(value.toByte()); digest.update((value ushr 8).toByte())
            }
        }
        return mapOf("policy" to "raw16-sparse-bayer-blocks-v1", "sampleCount" to count,
            "gridColumns" to nx, "gridRows" to ny, "width" to width, "height" to height,
            "rowStrideBytes" to rowStride, "pixelStrideBytes" to pixelStride,
            "bufferLimitBytes" to data.limit(), "byteOrder" to ByteOrder.nativeOrder().toString(),
            "minimum" to low, "maximum" to high, "mean" to mean, "spatialStd" to kotlin.math.sqrt(m2/count),
            "sampleSha256" to digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
    }
}

/** One original at a time, bounded queues, no averaging and no demosaicing. Called off the UI thread. */
internal class DngCapture(
    private val camera: CameraDevice,
    private val reader: ImageReader,
    private val preview: Surface,
    private val characteristics: CameraCharacteristics,
    private val handler: Handler,
    private val cancel: AtomicBoolean
) {
    private val failure = AtomicReference<Throwable?>()
    private data class RawArrival(val image: Image, val elapsedNs: Long)
    private val images = ArrayBlockingQueue<RawArrival>(2)
    private val results = ArrayBlockingQueue<TotalCaptureResult>(16)
    private val timings = ConcurrentHashMap<Int, ConcurrentHashMap<String, Any>>()
    private var session: CameraCaptureSession? = null
    private val ended = AtomicBoolean(false)

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

    fun run(settings: DngSettings, count: Int, directory: File?, onFrame: (DngFrame) -> Unit): DngSequence {
        require(count in 0..32)
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
                val image = runCatching { source.acquireNextImage() }.getOrNull()
                if (image != null && (ended.get() || cancel.get())) image.close()
                else if (image != null && !images.offer(RawArrival(image,SystemClock.elapsedRealtimeNanos()))) {
                    image.close()
                    failure.compareAndSet(null, IllegalStateException("RAW queue overflow"))
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
            applySupportedProcessing(auto, characteristics)
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
            applyLocked(manual, locked, characteristics)
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
                repeat(count) { index ->
                    check()
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                    request.addTarget(reader.surface)
                    applyLocked(request, locked, characteristics)
                    request.setTag(index)
                    val timing = ConcurrentHashMap<String, Any>()
                    timing["diagnosticsVersion"] = 1
                    timing["requestIndex"] = index+1
                    timing["requestSubmittedElapsedNs"] = SystemClock.elapsedRealtimeNanos()
                    previousCloseElapsed?.let { timing["previousImageClosedElapsedNs"] = it }
                    timings[index] = timing
                    val sequenceId = active.capture(request.build(), callback, handler)
                    timing["captureSequenceId"] = sequenceId
                    val arrival = await(images)
                    val image = arrival.image
                    try {
                        var result: TotalCaptureResult
                        do { result = await(results) } while (result.get(CaptureResult.SENSOR_TIMESTAMP) != image.timestamp)
                        check(result.request.tag == index) { "RAW image/result identity mismatch" }
                        timing["imageArrivedElapsedNs"] = arrival.elapsedNs
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
                        fun rawSample(): Map<String, Any> = runCatching {
                            val plane = image.planes.single()
                            RawBufferDiagnostics.sample(plane.buffer,image.width,image.height,plane.rowStride,plane.pixelStride)
                        }.getOrElse { mapOf("unavailable" to (it.message ?: it.javaClass.simpleName)) }
                        timing["rawBeforeDng"] = rawSample()
                        timing["dngWriteStartedElapsedNs"] = SystemClock.elapsedRealtimeNanos()
                        DngCreator(characteristics, result).use { creator ->
                            // Pixels retain native sensor coordinates. Preview orientation is independent.
                            creator.setOrientation(1)
                            pending.outputStream().use { creator.writeImage(it, image) }
                        }
                        timing["dngWriteEndedElapsedNs"] = SystemClock.elapsedRealtimeNanos()
                        timing["rawAfterDng"] = rawSample()
                        check(pending.length() in 1..(128L * 1024 * 1024)) { "DNG exceeds file limit" }
                        check(pending.renameTo(file)) { "Cannot finalize DNG original" }
                        timing["dngBytes"] = file.length()
                        val frame = DngFrame(file, result, image.width, image.height, image.timestamp, timing.toMap())
                        frames.add(frame)
                        onFrame(frame)
                    } finally {
                        image.close()
                        previousCloseElapsed = SystemClock.elapsedRealtimeNanos()
                        timings.remove(index)
                    }
                }
            }
            return DngSequence(locked, observed, frames)
        } finally {
            ended.set(true)
            session?.close()
            sessions.poll()?.close()
            reader.setOnImageAvailableListener(null, null)
            while (true) (images.poll() ?: break).image.close()
        }
    }

    companion object {
        fun isBayer(c: CameraCharacteristics): Boolean = c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) in setOf(
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB,
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG,
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG,
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR)

        fun applyLocked(builder: CaptureRequest.Builder, value: DngLockedSettings, c: CameraCharacteristics) {
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
            applySupportedProcessing(builder, c)
        }

        private fun applySupportedProcessing(builder: CaptureRequest.Builder, c: CameraCharacteristics) {
            fun mode(key: CaptureRequest.Key<Int>, available: CameraCharacteristics.Key<IntArray>, wanted: Int) {
                if (c.availableCaptureRequestKeys.contains(key) && c.get(available)?.contains(wanted) == true) builder.set(key, wanted)
            }
            mode(CaptureRequest.NOISE_REDUCTION_MODE, CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
            mode(CaptureRequest.HOT_PIXEL_MODE, CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES, CaptureRequest.HOT_PIXEL_MODE_OFF)
            mode(CaptureRequest.EDGE_MODE, CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES, CaptureRequest.EDGE_MODE_OFF)
            mode(CaptureRequest.SHADING_MODE, CameraCharacteristics.SHADING_AVAILABLE_MODES, CaptureRequest.SHADING_MODE_OFF)
            mode(CaptureRequest.DISTORTION_CORRECTION_MODE, CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES, CaptureRequest.DISTORTION_CORRECTION_MODE_OFF)
            mode(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
            mode(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            // Do not add a manufacturer lens-gain map. Hardware RAW shading may
            // already be applied; SENSOR_INFO_LENS_SHADING_APPLIED records that.
            mode(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES, CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_OFF)
        }
    }
}
