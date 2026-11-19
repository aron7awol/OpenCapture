package com.uniformitycomp.capture

import android.graphics.Rect
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
import java.util.concurrent.atomic.AtomicLong
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
    val transform: ColorSpaceTransform,
    val crop: Rect? = null,
    val opticalStabilization: Int? = null,
    val videoStabilization: Int? = null
)

data class DngFrame(val file: File, val result: TotalCaptureResult, val width: Int, val height: Int,
                    val imageTimestampNs: Long, val diagnostics: Map<String, Any> = emptyMap())
data class DngSequence(val locked: DngLockedSettings, val reading: TotalCaptureResult, val frames: List<DngFrame>) {
    internal var verified: DngVerifiedState? = null
    internal var verificationUnavailable = "no_verified_still"
}

internal data class DngVerifiedState(val locked: DngLockedSettings, val actual: ManualStateReading,
    val frameNumber: Long, val sensorTimestampNs: Long, val verifiedElapsedNs: Long,
    val source: String = "previous_completed_still") {
    fun provenance(): Map<String, Any> = mapOf("source" to source,
        "frameNumber" to frameNumber, "sensorTimestampNs" to sensorTimestampNs,
        "verifiedElapsedNs" to verifiedElapsedNs)
}

internal class ManualStateMismatch(val reason: String) : IllegalStateException(
    "Manual state verification failed: $reason; normal setup required on the next job")

/** At most two unfinished originals, bounded queues, no averaging or demosaicing. Called off the UI thread. */
internal class DngCapture(
    private val camera: CameraDevice,
    private val reader: ImageReader,
    private val preview: Surface,
    private val characteristics: CameraCharacteristics,
    private val handler: Handler,
    private val cancel: AtomicBoolean,
    private val reportLensShading: Boolean = false,
    private val existingSession: CameraCaptureSession? = null,
    private val previousLocked: DngLockedSettings? = null,
    private val previousVerified: DngVerifiedState? = null,
    private val setupFallbackReason: String = "no_verified_still",
    private val previousExposureState: DngVerifiedState? = null,
    private val diagnosticEvent: ((String, Map<String, Any>) -> Unit)? = null
) {
    private val failure = AtomicReference<Throwable?>()
    private data class RawArrival(val image: Image, val elapsedNs: Long,
        val sampledElapsedNs: Long, val samples: Map<String, Any>, val queueDepth: Int)
    private val images = ArrayBlockingQueue<RawArrival>(2)
    private val results = ArrayBlockingQueue<TotalCaptureResult>(16)
    private val timings = ConcurrentHashMap<Int, ConcurrentHashMap<String, Any>>()
    private var session: CameraCaptureSession? = null
    private val ended = AtomicBoolean(false)
    private val callbackGate = CaptureCallbackGate()
    private val previewResultsDuringStills = AtomicLong(0)
    private val latestPreviewDuringStills = AtomicReference<TotalCaptureResult?>()
    private val timeline = CaptureTimeline()
    private val shadingEvidence = LensShadingDiagnostics()

    private fun record(event: String, fields: Map<String, Any>) {
        runCatching { diagnosticEvent?.invoke(event, fields) }
    }
    private fun checkpoint(index: Int, stage: String, fields: Map<String, Any> = emptyMap(),
                           at: Long = SystemClock.elapsedRealtimeNanos()) {
        record("raw_request_checkpoint", timeline.mark(index+1, stage, at, fields))
    }
    private fun shading(result: TotalCaptureResult, source: String) {
        runCatching {
            val map = result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)
            if (map == null) { record("lens_shading_missing", mapOf("source" to source, "frameNumber" to result.frameNumber)); return }
            val gains = FloatArray(map.gainFactorCount).also { map.copyGainFactors(it, 0) }
            record("lens_shading_evidence", shadingEvidence.observe(map.rowCount, map.columnCount, gains,
                mapOf("source" to source, "frameNumber" to result.frameNumber,
                    "sensorTimestampNs" to (result.get(CaptureResult.SENSOR_TIMESTAMP) ?: "missing"),
                    "shadingMode" to (result.get(CaptureResult.SHADING_MODE) ?: "missing"),
                    "lensShadingMapMode" to (result.get(CaptureResult.STATISTICS_LENS_SHADING_MAP_MODE) ?: "missing"),
                    "rawLensShadingApplied" to (characteristics.get(CameraCharacteristics.SENSOR_INFO_LENS_SHADING_APPLIED) ?: "unknown"))))
        }.onFailure { record("lens_shading_unavailable", mapOf("detail" to it.toString().take(240), "source" to source)) }
    }

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
        record("capture_wait_timeout", mapOf("waitingFor" to when (queue) {
            images -> "raw_image"; results -> "capture_result"; else -> "session_configuration"
        }, "timeoutSeconds" to seconds, "imageQueueDepth" to images.size, "resultQueueDepth" to results.size))
        throw IllegalStateException("Camera timed out; partial originals retained")
    }

    private val callback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureStarted(s: CameraCaptureSession, r: CaptureRequest, timestamp: Long, frameNumber: Long) {
            if (s !== session || !callbackGate.accepts(r.tag)) return
            (r.tag as? CaptureCallbackGate.Tag)?.index?.let { tag -> timings[tag]?.apply {
                put("captureStartedElapsedNs", SystemClock.elapsedRealtimeNanos())
                put("captureStartedSensorTimestampNs", timestamp)
                put("captureStartedFrameNumber", frameNumber)
                checkpoint(tag, "started", mapOf("frameNumber" to frameNumber, "sensorTimestampNs" to timestamp))
            } }
        }
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
            if (s !== session || !callbackGate.accepts(r.tag)) return
            // Continuous preview must never evict a timestamp-matched RAW result.
            if (!callbackGate.queuesResult(r.tag)) {
                latestPreviewDuringStills.set(result)
                if (previewResultsDuringStills.incrementAndGet() == 1L)
                    diagnosticEvent?.invoke("preview_during_raw_first_result", setupReading(result))
                return
            }
            (r.tag as? CaptureCallbackGate.Tag)?.index?.let { tag -> timings[tag]?.apply {
                put("resultArrivedElapsedNs", SystemClock.elapsedRealtimeNanos())
                put("resultFrameNumber", result.frameNumber)
                checkpoint(tag, "result", mapOf("frameNumber" to result.frameNumber,
                    "sensorTimestampNs" to (result.get(CaptureResult.SENSOR_TIMESTAMP) ?: "missing")))
            } }
            if (!results.offer(result)) { results.poll(); results.offer(result) }
        }
        override fun onCaptureProgressed(s: CameraCaptureSession, r: CaptureRequest, result: CaptureResult) {
            if (s !== session || !callbackGate.accepts(r.tag)) return
            (r.tag as? CaptureCallbackGate.Tag)?.index?.let { tag -> timings[tag]?.apply {
                val now = SystemClock.elapsedRealtimeNanos()
                val count = (get("partialResultCount") as? Int ?: 0) + 1
                put("partialResultCount", count)
                if (count == 1) {
                    put("firstPartialResultElapsedNs", now)
                    put("firstPartialResultKeys", result.keys.map { it.name }.sorted())
                    checkpoint(tag, "firstPartial", mapOf("frameNumber" to result.frameNumber))
                }
                put("lastPartialResultElapsedNs", now)
                if (result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP) != null) {
                    putIfAbsent("partialShadingMapElapsedNs", now)
                }
            } }
        }
        override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, f: CaptureFailure) {
            if (s !== session || !callbackGate.accepts(r.tag)) return
            diagnosticEvent?.invoke("still_capture_failed", mapOf("reason" to f.reason,
                "frameNumber" to f.frameNumber, "sequenceId" to f.sequenceId,
                "imageCaptured" to f.wasImageCaptured()))
            (r.tag as? CaptureCallbackGate.Tag)?.index?.let { checkpoint(it, "failed",
                mapOf("reason" to f.reason, "sequenceId" to f.sequenceId, "imageCaptured" to f.wasImageCaptured())) }
            failure.compareAndSet(null, IllegalStateException("Capture failure ${f.reason}"))
        }
        override fun onCaptureSequenceAborted(s: CameraCaptureSession, sequenceId: Int) {
            if (s !== session || ended.get()) return
            diagnosticEvent?.invoke("still_sequence_aborted", mapOf("sequenceId" to sequenceId))
            failure.compareAndSet(null, IllegalStateException("Capture sequence aborted"))
        }
        override fun onCaptureBufferLost(s: CameraCaptureSession, r: CaptureRequest, target: Surface, frameNumber: Long) {
            if (s !== session || !callbackGate.accepts(r.tag)) return
            record("capture_buffer_lost", mapOf("frameNumber" to frameNumber,
                "requestIndex" to ((r.tag as? CaptureCallbackGate.Tag)?.index?.plus(1) ?: "preview"),
                "target" to if (target === preview) "preview" else "raw_or_other"))
        }
        override fun onCaptureSequenceCompleted(s: CameraCaptureSession, sequenceId: Int, frameNumber: Long) {
            if (s !== session || ended.get()) return
            record("capture_sequence_completed", mapOf("sequenceId" to sequenceId, "lastFrameNumber" to frameNumber))
        }
    }

    fun run(settings: DngSettings, count: Int, directory: File?, onFrame: (DngFrame) -> Unit,
            maxAttempts: Int = count, needsMore: (() -> Boolean)? = null,
            overlap: Boolean = false,
            tryReserveNext: (() -> Boolean)? = null,
            keepPreviewRunning: Boolean = false, shortPreviewExposure: Boolean = false): DngSequence {
        check()
        require(count in 0..32)
        require(maxAttempts in count..64 && (needsMore != null || maxAttempts == count))
        require(!overlap || needsMore == null || tryReserveNext != null) { "Overlap requires nonblocking attempt admission" }
        require(!overlap || reader.maxImages >= 2) { "Overlap requires two owned RAW buffers" }
        require(!keepPreviewRunning || !overlap) { "Continuous-preview compatibility requires sequential RAW captures" }
        require(!shortPreviewExposure || keepPreviewRunning) { "Short preview requires continuous sequential preview" }
        val caps = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        require(caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) &&
            caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) &&
            caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING)) {
            "This camera must support RAW, manual sensor and manual post-processing controls"
        }
        require(isBayer(characteristics)) { "This camera does not advertise an RGB Bayer sensor" }
        val minFocus = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        // Exposure-only changes retain verified manual color state, but must
        // obtain fresh manual readback. Omitted controls still mean auto.
        val fast = previousVerified?.takeIf { count > 0 && existingSession != null &&
            ManualRequestKey.from(settings.exposureNs, settings.iso, settings.focusFraction, settings.whiteBalance) != null }
        val exposureSource = previousExposureState?.takeIf { fast == null && existingSession != null &&
            ManualRequestKey.from(settings.exposureNs, settings.iso, settings.focusFraction, settings.whiteBalance) != null &&
            matchesColor(settings, it.locked, minFocus) }
        val exposureLocked = exposureSource?.locked?.copy(
            exposureNs = requireNotNull(settings.exposureNs), iso = requireNotNull(settings.iso))
        val reusable = fast?.locked ?: exposureLocked ?: previousLocked?.takeIf { matches(settings, it, minFocus) }
        val setup = linkedMapOf<String, Any>(
            "policy" to when { fast != null -> "verified-manual-still-reuse-v1"
                exposureLocked != null -> "manual-exposure-change-locked-color-v1"
                reusable != null -> "manual-reuse-identical-controls-v1"; else -> "auto-then-lock-v1" },
            "fastPathUsed" to (fast != null), "manualPreviewSkipped" to (fast != null),
            "reason" to if (fast != null) "verified_identical_manual" else
                if (keepPreviewRunning) "continuous_preview_fresh_manual_readback" else setupFallbackReason,
            "keepPreviewRunning" to keepPreviewRunning,
            "sessionRecreated" to (existingSession == null),
            "previousLockedAvailable" to (previousLocked != null),
            "setupStartedElapsedNs" to SystemClock.elapsedRealtimeNanos())
        fast?.let { setup["reusedVerification"] = it.provenance() }
        exposureSource?.let {
            setup["retainedColorState"] = it.provenance()
            setup["automaticColorSetupSkipped"] = true
        }
        characteristics.get(CameraCharacteristics.REQUEST_PARTIAL_RESULT_COUNT)?.let {
            setup["advertisedPartialResultCount"] = it
        }
        characteristics.get(CameraCharacteristics.REQUEST_PIPELINE_MAX_DEPTH)?.let {
            setup["advertisedPipelineMaxDepth"] = it.toInt()
        }
        setup["controlDiagnosticsPolicy"] = "manual-control-comparison-v1"
        setup["requestedControls"] = mapOf("exposureNs" to (settings.exposureNs ?: "auto"),
            "iso" to (settings.iso ?: "auto"), "focusFraction" to (settings.focusFraction ?: "auto"),
            "whiteBalanceRGB" to (settings.whiteBalance?.toList() ?: "auto"))
        setup["maximumFocusDiopters"] = minFocus
        characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let {
            setup["advertisedISORange"] = listOf(it.lower, it.upper)
        }
        characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let {
            setup["advertisedExposureRangeNs"] = listOf(it.lower, it.upper)
        }
        val sessions = ArrayBlockingQueue<CameraCaptureSession>(1)
        var submittedStills = 0
        var completedStills = 0
        val preparationStarted = SystemClock.elapsedRealtimeNanos()
        var succeeded = false
        try {
            reader.setOnImageAvailableListener({ source ->
                // Cleanup must wait for an acquisition sample before the owner
                // can close/replace the reader after cancellation or failure.
                synchronized(images) {
                    val image = try { source.acquireNextImage() } catch (error: Exception) {
                        if (!ended.get() && !cancel.get()) failure.compareAndSet(null, error)
                        null
                    }
                    if (image != null) {
                        val acquired = SystemClock.elapsedRealtimeNanos()
                        val samples = if (ended.get() || cancel.get()) emptyMap() else rawSample(image)
                        val sampled = SystemClock.elapsedRealtimeNanos()
                        record("raw_image_arrived", timeline.image(acquired, image.timestamp, images.size) +
                            mapOf("samples" to samples, "closingWithoutDelivery" to (ended.get() || cancel.get())))
                        if (ended.get() || cancel.get()) image.close()
                        else if (!images.offer(RawArrival(image, acquired, sampled, samples, images.size))) {
                            image.close()
                            failure.compareAndSet(null, IllegalStateException("RAW queue overflow"))
                        }
                    }
                }
            }, handler)
            if (existingSession == null) setup["sessionRequestedElapsedNs"] = SystemClock.elapsedRealtimeNanos()
            if (existingSession == null) camera.createCaptureSession(listOf(preview, reader.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (cancel.get() || ended.get()) s.close() else sessions.offer(s)
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    s.close(); failure.set(IllegalStateException("RAW + preview session is unsupported"))
                }
            }, handler)
            val active = existingSession ?: await(sessions)
            session = active
            val sessionReady = SystemClock.elapsedRealtimeNanos()
            setup[if (existingSession == null) "sessionConfiguredElapsedNs" else "sessionReusedElapsedNs"] = sessionReady
            var observed: TotalCaptureResult? = null
            var locked = if (reusable != null) reusable else {
                val auto = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                auto.addTarget(preview)
                auto.setTag(callbackGate.tag())
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
                settings.exposureNs?.let {
                    auto.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    applyExposure(auto, it, characteristics)
                }
                settings.iso?.let { auto.set(CaptureRequest.SENSOR_SENSITIVITY, it) }
                settings.focusFraction?.let {
                    auto.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    auto.set(CaptureRequest.LENS_FOCUS_DISTANCE, it * minFocus)
                }
                // AE may meter a new scene while explicit focus/WB retain the
                // color transform established by the previous healthy setup.
                // CameraController clears previousLocked on lifecycle/errors.
                val meteringColor = previousLocked?.takeIf { existingSession != null &&
                    matchesColor(settings, it, minFocus) }
                meteringColor?.let {
                    auto.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
                    auto.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                    auto.set(CaptureRequest.COLOR_CORRECTION_GAINS, it.gains)
                    auto.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, it.transform)
                    setup["autoExposureRetainedManualColor"] = true
                }
                setup["autoRequestSubmittedElapsedNs"] = SystemClock.elapsedRealtimeNanos()
                setup["autoSubmittedRequest"] = requestReading(auto.build())
                active.setRepeatingRequest(auto.build(), callback, handler)
                if (settings.focusFraction == null && autoFocusMode == CaptureRequest.CONTROL_AF_MODE_AUTO) {
                    auto.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                    active.capture(auto.build(), callback, handler)
                }
                val settleStart = System.nanoTime()
                var autoResults = 0
                do {
                    observed = await(results)
                    setup["autoLatestResult"] = setupReading(observed)
                    setup["autoResultsSeen"] = autoResults + 1
                    if (autoResults++ == 0) {
                        setup["autoFirstResultElapsedNs"] = SystemClock.elapsedRealtimeNanos()
                        setup["autoFirstResult"] = setupReading(observed)
                        diagnosticEvent?.invoke("auto_first_result", setupReading(observed))
                    }
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
                setup["autoSettledElapsedNs"] = SystemClock.elapsedRealtimeNanos()
                setup["autoResultsSeen"] = autoResults
                setup["autoSettledResult"] = setupReading(observed)
                shading(observed, "auto_settled")
                diagnosticEvent?.invoke("auto_settled_result", setupReading(observed))
                val gains = settings.whiteBalance?.let { RggbChannelVector(it[0], it[1], it[1], it[2]) }
                    ?: requireNotNull(observed.get(CaptureResult.COLOR_CORRECTION_GAINS)) { "Missing actual white balance gains" }
                DngLockedSettings(
                    settings.exposureNs ?: requireNotNull(observed.get(CaptureResult.SENSOR_EXPOSURE_TIME)),
                    settings.iso ?: requireNotNull(observed.get(CaptureResult.SENSOR_SENSITIVITY)),
                    settings.focusFraction?.times(minFocus) ?: requireNotNull(observed.get(CaptureResult.LENS_FOCUS_DISTANCE)),
                    gains, requireNotNull(observed.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)) { "Missing color transform" })
            }
            setup["lockedControls"] = lockedReading(locked)
            diagnosticEvent?.invoke("manual_lock_selected", mapOf("controls" to lockedReading(locked),
                "advertisedISORange" to (setup["advertisedISORange"] ?: "missing"),
                "advertisedExposureRangeNs" to (setup["advertisedExposureRangeNs"] ?: "missing")))
            if (fast == null) {
                val manual = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                manual.addTarget(preview)
                val lockTag = callbackGate.tag()
                manual.setTag(lockTag)
                applyLocked(manual, locked, characteristics, reportLensShading)
                if (reusable == null && !keepPreviewRunning) active.stopRepeating()
                results.clear()
                setup["manualRequestSubmittedElapsedNs"] = SystemClock.elapsedRealtimeNanos()
                setup["manualSubmittedRequest"] = requestReading(manual.build())
                active.setRepeatingRequest(manual.build(), callback, handler)
                // Reject queued auto results; wait for controls actually reported by the locked request.
                val lockStart = System.nanoTime()
                var manualResults = 0
                var manualReadbackReported = false
                do {
                    observed = await(results)
                    if (!manualReadbackReported && observed.request.tag === lockTag) {
                        diagnosticEvent?.invoke("manual_first_result", setupReading(observed))
                        manualReadbackReported = true
                    }
                    val retainedReference = exposureSource?.actual?.copy(exposureNs = locked.exposureNs, iso = locked.iso)
                    val retainedDifference = retainedReference?.difference(stateReading(observed), minFocus)
                    val checks = controlChecks(observed, locked, minFocus)
                    setup["manualLatestResult"] = setupReading(observed)
                    setup["manualLatestComparison"] = mapOf("checks" to checks,
                        "requestTagMatches" to (observed.request.tag === lockTag),
                        "retainedStateDifference" to (retainedDifference ?: "matched_or_not_required"),
                        "retainedReference" to (retainedReference?.diagnosticFields() ?: "not_required"))
                    setup["manualLatestResultElapsedNs"] = SystemClock.elapsedRealtimeNanos()
                    val isManual = observed.request.tag === lockTag && checks.all { it["status"] == "matched" } && retainedDifference == null
                    if (isManual) {
                        if (manualResults++ == 0) {
                            setup["manualFirstResultElapsedNs"] = SystemClock.elapsedRealtimeNanos()
                            setup["manualFirstResult"] = setupReading(observed)
                        }
                        if (System.nanoTime() - lockStart >= TimeUnit.MILLISECONDS.toNanos(settings.settleMs.coerceAtLeast(250))) break
                    }
                    check(System.nanoTime() - lockStart < TimeUnit.SECONDS.toNanos(45)) { "Manual controls did not settle" }
                } while (true)
                setup["manualSettledElapsedNs"] = SystemClock.elapsedRealtimeNanos()
                setup["manualResultsSeen"] = manualResults
                setup["manualSettledResult"] = setupReading(observed)
                shading(observed, "manual_settled")
                diagnosticEvent?.invoke("manual_settled_result", setupReading(observed))
                // Carry verified geometry and the same requested stabilization policy across templates.
                if (reportLensShading) locked = locked.copy(
                    crop = observed.get(CaptureResult.SCALER_CROP_REGION)?.takeIf {
                        characteristics.availableCaptureRequestKeys.contains(CaptureRequest.SCALER_CROP_REGION) }?.let { Rect(it) },
                    opticalStabilization = manual.get(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE),
                    videoStabilization = manual.get(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE))
            } else {
                // Scene settling is independent of control verification. No preview round trip
                // and no fabricated manualSettled timestamp for a skipped phase.
                if (keepPreviewRunning) {
                    // Keep the driver fed without waiting through another identical
                    // preview pipeline. This callback owns the new operation's tags.
                    val manual = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    manual.addTarget(preview)
                    manual.setTag(callbackGate.tag())
                    applyLocked(manual, locked, characteristics, reportLensShading)
                    active.setRepeatingRequest(manual.build(), callback, handler)
                } else active.stopRepeating()
                val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(settings.settleMs.coerceAtLeast(250))
                setup["sceneWaitStartedElapsedNs"] = SystemClock.elapsedRealtimeNanos()
                while (System.nanoTime() < until) { check(); Thread.sleep(10) }
                setup["sceneWaitEndedElapsedNs"] = SystemClock.elapsedRealtimeNanos()
            }
            val setupEnded = SystemClock.elapsedRealtimeNanos()
            diagnosticEvent?.invoke("setup_completed", mapOf("exposureNs" to locked.exposureNs,
                "iso" to locked.iso, "reusedVerifiedState" to (fast != null)))
            setup["setupDurationNs"] = setupEnded - (setup["setupStartedElapsedNs"] as Long)
            record("setup_timing", mapOf("setupDurationNs" to setup["setupDurationNs"]!!,
                "controlsReused" to (fast != null), "startedElapsedNs" to preparationStarted,
                "endedElapsedNs" to setupEnded, "stages" to setup.filterKeys {
                    it.endsWith("ElapsedNs") || it == "autoResultsSeen" || it == "manualResultsSeen"
                }))
            if (reportLensShading) android.util.Log.i("OpenCaptureSetup", "fast=${fast != null} reason=${setup["reason"]} " +
                "sourceFrame=${fast?.frameNumber} setupMs=${(setup["setupDurationNs"] as Long)/1_000_000}")
            val setupEvidence = setup.toMap()
            val controlsLocked = setup["manualSettledElapsedNs"] as? Long
            val reference = fast?.actual ?: observed?.let { stateReading(it) }
            var verified: DngVerifiedState? = null
            var verificationUnavailable: String? = reference?.incomplete() ?: if (reference == null) "no_setup_readback" else null
            if (locked.crop == null) verificationUnavailable = "crop_not_lockable"
            if (caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) && reference?.physicalCamera == null)
                verificationUnavailable = "physical_camera_not_verified"
            if (locked.opticalStabilization != null && reference?.opticalStabilization != locked.opticalStabilization)
                verificationUnavailable = "optical_stabilization_not_verified"
            if (locked.videoStabilization != null && reference?.videoStabilization != locked.videoStabilization)
                verificationUnavailable = "video_stabilization_not_verified"
            if (count == 0 && verificationUnavailable == null) {
                // A controls-only job can establish control state, never RAW quality.
                val reading = requireNotNull(observed)
                verified = DngVerifiedState(locked, requireNotNull(reference), reading.frameNumber,
                    requireNotNull(reading.get(CaptureResult.SENSOR_TIMESTAMP)), SystemClock.elapsedRealtimeNanos(),
                    source = "previous_manual_preview")
            }
            val frames = mutableListOf<DngFrame>()
            if (count > 0) {
                requireNotNull(directory)
                check(directory.isDirectory || directory.mkdirs()) { "Cannot create capture archive" }
                if (fast == null && !keepPreviewRunning) active.stopRepeating()
                val previewExposureNs = ShortPreviewExposure.exposure(locked.exposureNs, shortPreviewExposure)
                if (shortPreviewExposure) {
                    // Verify setup at the full requested exposure first. Only the
                    // repeating preview changes; still validation keeps the full tuple.
                    val previewRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    previewRequest.addTarget(preview)
                    previewRequest.setTag(callbackGate.tag())
                    applyLocked(previewRequest, locked.copy(exposureNs=previewExposureNs), characteristics, reportLensShading)
                    active.setRepeatingRequest(previewRequest.build(), callback, handler)
                }
                callbackGate.beginStills()
                results.clear()
                synchronized(images) { while (true) (images.poll() ?: break).image.close() }
                diagnosticEvent?.invoke("raw_preview_policy", mapOf(
                    "repeatingPreviewDuringStill" to keepPreviewRunning, "overlap" to overlap,
                    "exposureNs" to locked.exposureNs, "iso" to locked.iso,
                    "shortPreviewExposure" to shortPreviewExposure, "requestedPreviewExposureNs" to previewExposureNs))
                var physicalId: String? = null
                var previousSensorTimestamp: Long? = null
                var previousCloseElapsed: Long? = null
                var previousSampleHash: String? = null
                val pendingResults = mutableMapOf<Long, TotalCaptureResult>()
                fun matchedResult(timestamp: Long): TotalCaptureResult {
                    pendingResults.remove(timestamp)?.let { return it }
                    while (true) {
                        val result = await(results)
                        val tag = result.request.tag as? CaptureCallbackGate.Tag
                        if (!callbackGate.accepts(tag) || tag?.index == null) continue // Outstanding setup preview.
                        val sensor = requireNotNull(result.get(CaptureResult.SENSOR_TIMESTAMP))
                        if (sensor == timestamp) return result
                        check(pendingResults.size < 2 && !pendingResults.containsKey(sensor)) { "Unexpected RAW result backlog" }
                        pendingResults[sensor] = result
                    }
                }
                fun submit(index: Int, overlapping: Boolean): ConcurrentHashMap<String, Any> {
                    check()
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                    request.addTarget(reader.surface)
                    applyLocked(request, locked, characteristics, reportLensShading)
                    request.setTag(callbackGate.tag(index))
                    if (characteristics.availableCaptureRequestKeys?.contains(CaptureRequest.CONTROL_ENABLE_ZSL) == true)
                        request.set(CaptureRequest.CONTROL_ENABLE_ZSL, false)
                    val timing = ConcurrentHashMap<String, Any>()
                    timing["diagnosticsVersion"] = 2
                    timing["captureSetup"] = setupEvidence
                    timing["preparationStartedElapsedNs"] = preparationStarted
                    timing["sessionReadyElapsedNs"] = sessionReady
                    controlsLocked?.let { timing["controlsLockedElapsedNs"] = it }
                    timing["sessionReused"] = existingSession != null
                    timing["controlsReused"] = reusable != null
                    if (reportLensShading) timing["timingPolicy"] = RawTiming.POLICY
                    timing["requestIndex"] = index+1
                    timing["exposureOverlapPolicy"] = if (overlap) "two-frame-after-image-v1" else "sequential-v1"
                    timing["submittedWhilePreviousUnfinished"] = overlapping
                    timing["imageReaderMaxImages"] = reader.maxImages
                    timing["requestTemplate"] = "TEMPLATE_STILL_CAPTURE"
                    timing["repeatingPreviewDuringStill"] = keepPreviewRunning
                    timing["shortPreviewExposure"] = shortPreviewExposure
                    timing["requestedPreviewExposureNs"] = previewExposureNs
                    timing["previewResultsAtSubmission"] = previewResultsDuringStills.get()
                    timing["requestedExposureNs"] = locked.exposureNs
                    timing["requestedISO"] = locked.iso
                    timing["requestSubmittedElapsedNs"] = SystemClock.elapsedRealtimeNanos()
                    timings[index] = timing
                    checkpoint(index, "submitted", mapOf("exposureNs" to locked.exposureNs,
                        "iso" to locked.iso, "imageReaderMaxImages" to reader.maxImages,
                        "imageQueueDepth" to images.size, "resultQueueDepth" to results.size),
                        timing["requestSubmittedElapsedNs"] as Long)
                    val sequenceId = active.capture(request.build(), callback, handler)
                    timing["captureSequenceId"] = sequenceId
                    checkpoint(index, "sequenceAssigned", mapOf("sequenceId" to sequenceId))
                    submittedStills++
                    return timing
                }
                var prefetched: ConcurrentHashMap<String, Any>? = null
                for (index in 0 until maxAttempts) {
                    if (prefetched == null && needsMore != null && !needsMore()) break
                    val timing = prefetched ?: submit(index, false)
                    prefetched = null
                    previousCloseElapsed?.let { timing["previousImageClosedElapsedNs"] = it }
                    val arrival = await(images)
                    val image = arrival.image
                    val frame = try {
                        // The sensor has delivered this RAW. Start one subsequent exposure
                        // while retaining this Image for its own metadata and DNG write.
                        if (overlap && index+1 < maxAttempts &&
                            (needsMore == null || tryReserveNext!!.invoke())) {
                            prefetched = submit(index+1, true)
                        }
                        val result = matchedResult(image.timestamp)
                        check(callbackGate.accepts(result.request.tag) &&
                            (result.request.tag as? CaptureCallbackGate.Tag)?.index == index) { "RAW image/result identity mismatch" }
                        observed = result // Always a current result, never cached setup evidence.
                        checkpoint(index, "imageMatched", mapOf("imageTimestampNs" to image.timestamp), arrival.elapsedNs)
                        shading(result, "raw_${index+1}")
                        if (reportLensShading) {
                            val checks = controlChecks(result, locked, minFocus)
                            val mismatch = if (checks.any { it["status"] != "matched" }) "still_controls_not_honored"
                                else if (reference == null) "no_setup_readback" else reference.difference(stateReading(result), minFocus)
                            if (mismatch != null) {
                                diagnosticEvent?.invoke("manual_state_mismatch", mapOf("detail" to mismatch,
                                    "fatal" to (fast != null || mismatch == "still_controls_not_honored"),
                                    "requestIndex" to index + 1, "imageTimestampNs" to image.timestamp,
                                    "lockedControls" to lockedReading(locked), "checks" to checks,
                                    "submittedRequest" to requestReading(result.request),
                                    "actual" to setupReading(result),
                                    "reference" to (reference?.diagnosticFields() ?: "missing")))
                                if (fast != null || mismatch == "still_controls_not_honored") throw ManualStateMismatch(mismatch)
                                verificationUnavailable = mismatch
                            }
                            // Timing-invalid originals still follow the existing diagnostic /
                            // bounded replacement policy. They must not refresh cached evidence.
                            if (fast != null && (result.frameNumber <= fast.frameNumber || image.timestamp <= fast.sensorTimestampNs))
                                verificationUnavailable = "non_increasing_still_timing"
                            timing["manualStateVerification"] = mismatch ?: "matched"
                        }
                        timing["imageArrivedElapsedNs"] = arrival.elapsedNs
                        if (reportLensShading) timing["timingIntegrity"] = RawTiming.assess(
                            timing["requestSubmittedElapsedNs"] as Long, arrival.elapsedNs,
                            requireNotNull(result.get(CaptureResult.SENSOR_EXPOSURE_TIME)), image.timestamp,
                            characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ?: 0)
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
                        checkpoint(index, "writeStarted")
                        DngCreator(characteristics, result).use { creator ->
                            // Pixels retain native sensor coordinates. Preview orientation is independent.
                            creator.setOrientation(1)
                            pending.outputStream().use { creator.writeImage(it, image) }
                        }
                        timing["dngWriteEndedElapsedNs"] = SystemClock.elapsedRealtimeNanos()
                        checkpoint(index, "writeEnded")
                        val after = rawSample(image)
                        timing["rawAfterDng"] = after
                        check(pending.length() in 1..(128L * 1024 * 1024)) { "DNG exceeds file limit" }
                        check(pending.renameTo(file)) { "Cannot finalize DNG original" }
                        timing["dngBytes"] = file.length()
                        timing["dngReadbackStartedElapsedNs"] = SystemClock.elapsedRealtimeNanos()
                        checkpoint(index, "readbackStarted")
                        val saved = runCatching { DngSampleDiagnostics.sample(file, image.width, image.height) }
                            .getOrElse { mapOf("unavailable" to (it.message ?: it.javaClass.simpleName).take(240)) }
                        timing["dngReadbackEndedElapsedNs"] = SystemClock.elapsedRealtimeNanos()
                        checkpoint(index, "readbackEnded")
                        timing["rawSavedDng"] = saved
                        timing["sampleIntegrity"] = RawBufferDiagnostics.compare(arrival.samples, before, after, saved)
                        record("raw_frame_evidence", mapOf("index" to index+1,
                            "imageTimestampNs" to image.timestamp, "actual" to setupReading(result),
                            "samples" to arrival.samples, "savedSamples" to saved,
                            "sampleIntegrity" to timing["sampleIntegrity"]!!,
                            "timingIntegrity" to (timing["timingIntegrity"] ?: "unassessed"),
                            "dynamicBlackLevel" to (result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)?.toList() ?: "missing"),
                            "dynamicWhiteLevel" to (result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL) ?: "missing")))
                        if (reportLensShading) {
                            if ((timing["timingIntegrity"] as? Map<*, *>)?.get("status") != "passed")
                                verificationUnavailable = "timing_not_verified"
                            if ((timing["sampleIntegrity"] as? Map<*, *>)?.get("assessment") != "consistent_sampled_path")
                                verificationUnavailable = "samples_not_verified"
                            if (verificationUnavailable == null) verified = DngVerifiedState(locked, stateReading(result),
                                result.frameNumber, image.timestamp, SystemClock.elapsedRealtimeNanos())
                        }
                        DngFrame(file, result, image.width, image.height, image.timestamp)
                    } finally {
                        image.close()
                        previousCloseElapsed = SystemClock.elapsedRealtimeNanos()
                        timing["imageClosedElapsedNs"] = previousCloseElapsed!!
                        checkpoint(index, "closed")
                        timings.remove(index)
                    }
                    completedStills++
                    timing["previewResultsAtCompletion"] = previewResultsDuringStills.get()
                    latestPreviewDuringStills.get()?.let { timing["latestPreviewDuringStills"] = setupReading(it) }
                    val recorded = frame.copy(diagnostics = timing.toMap())
                    frames.add(recorded)
                    onFrame(recorded)
                }
            }
            check()
            val sequence = DngSequence(locked, requireNotNull(observed) { "No fresh capture result" }, frames).also {
                it.verified = verified.takeIf { verificationUnavailable == null }
                it.verificationUnavailable = verificationUnavailable ?: "no_verified_still"
            }
            succeeded = true
            return sequence
        } catch (error: Exception) {
            // Persist before cleanup, including failures that never produced a DNG sidecar.
            diagnosticEvent?.invoke("capture_setup_failure", mapOf("detail" to error.toString().take(512),
                "setup" to setup.toMap(), "submittedStills" to submittedStills,
                "completedStills" to completedStills))
            if (error is ManualStateMismatch)
                android.util.Log.w("OpenCaptureSetup", "fallback=${error.reason} nextJob=normal_setup")
            throw error
        } finally {
            ended.set(true)
            callbackGate.end()
            record("raw_timeline_summary", timeline.summary())
            diagnosticEvent?.invoke("raw_preview_summary", mapOf(
                "repeatingPreviewDuringStill" to keepPreviewRunning,
                "previewResults" to previewResultsDuringStills.get(),
                "submittedStills" to submittedStills, "completedStills" to completedStills))
            if (submittedStills > completedStills) runCatching { session?.abortCaptures() }
            if (existingSession == null || !succeeded) session?.close()
            sessions.poll()?.close()
            runCatching { reader.setOnImageAvailableListener(null, null) }
            // Includes any acquisition sample already executing on the handler.
            synchronized(images) { while (true) (images.poll() ?: break).image.close() }
        }
    }

    private fun stateReading(result: TotalCaptureResult): ManualStateReading = ManualStateReading(
        result.get(CaptureResult.SENSOR_EXPOSURE_TIME), result.get(CaptureResult.SENSOR_SENSITIVITY),
        result.get(CaptureResult.LENS_FOCUS_DISTANCE), result.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let {
            listOf(it.red, it.greenEven, it.greenOdd, it.blue) },
        result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let { matrix ->
            (0..8).map { matrix.getElement(it % 3, it / 3).toDouble() } },
        result.get(CaptureResult.SCALER_CROP_REGION)?.let { listOf(it.left, it.top, it.right, it.bottom) },
        listOf(result.get(CaptureResult.CONTROL_MODE), result.get(CaptureResult.CONTROL_AE_MODE),
            result.get(CaptureResult.CONTROL_AF_MODE), result.get(CaptureResult.CONTROL_AWB_MODE), result.get(CaptureResult.COLOR_CORRECTION_MODE)),
        result.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE), result.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE),
        result.get(CaptureResult.SHADING_MODE), result.get(CaptureResult.STATISTICS_LENS_SHADING_MAP_MODE),
        if (android.os.Build.VERSION.SDK_INT >= 29) result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID) else null,
        result.get(CaptureResult.LENS_STATE) == CaptureResult.LENS_STATE_MOVING)

    /** Small metadata-only snapshots; never substitute these maps for a still's actual map. */
    private fun setupReading(result: TotalCaptureResult): Map<String, Any> {
        val reading = linkedMapOf<String, Any>("frameNumber" to result.frameNumber)
        reading.putAll(stateReading(result).diagnosticFields())
        result.get(CaptureResult.SENSOR_TIMESTAMP)?.let { reading["sensorTimestampNs"] = it }
        result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { reading["exposureNs"] = it }
        result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { reading["iso"] = it }
        result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { reading["focusDiopters"] = it }
        result.get(CaptureResult.CONTROL_AE_MODE)?.let { reading["aeMode"] = it }
        result.get(CaptureResult.CONTROL_MODE)?.let { reading["controlMode"] = it }
        result.get(CaptureResult.CONTROL_AF_MODE)?.let { reading["afMode"] = it }
        result.get(CaptureResult.CONTROL_AE_STATE)?.let { reading["aeState"] = it }
        result.get(CaptureResult.CONTROL_AF_STATE)?.let { reading["afState"] = it }
        result.get(CaptureResult.SENSOR_FRAME_DURATION)?.let { reading["frameDurationNs"] = it }
        result.get(CaptureResult.CONTROL_AWB_MODE)?.let { reading["awbMode"] = it }
        result.get(CaptureResult.CONTROL_AWB_STATE)?.let { reading["awbState"] = it }
        result.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let {
            reading["whiteBalanceGains"] = listOf(it.red, it.greenEven, it.greenOdd, it.blue)
        }
        result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let { reading["colorTransform"] = it.toString() }
        result.get(CaptureResult.SHADING_MODE)?.let { reading["shadingMode"] = it }
        result.get(CaptureResult.STATISTICS_LENS_SHADING_MAP_MODE)?.let { reading["lensShadingMapMode"] = it }
        result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)?.let { map ->
            val gains = FloatArray(map.gainFactorCount).also { map.copyGainFactors(it, 0) }
            val bytes = ByteBuffer.allocate(gains.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            gains.forEach { bytes.putFloat(it) }
            reading["lensShadingRows"] = map.rowCount
            reading["lensShadingColumns"] = map.columnCount
            reading["lensShadingMapSha256"] = MessageDigest.getInstance("SHA-256").digest(bytes.array())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        return reading
    }

    private fun lockedReading(value: DngLockedSettings): Map<String, Any> = mapOf(
        "exposureNs" to value.exposureNs, "iso" to value.iso, "focusDiopters" to value.focusDiopters,
        "whiteBalanceGains" to listOf(value.gains.red, value.gains.greenEven, value.gains.greenOdd, value.gains.blue),
        "colorTransform" to (0..8).map { value.transform.getElement(it % 3, it / 3).toDouble() })

    private fun requestReading(request: CaptureRequest): Map<String, Any> {
        val reading = linkedMapOf<String, Any>()
        fun <T> field(name: String, key: CaptureRequest.Key<T>) { reading[name] = request.get(key) ?: "missing" }
        field("exposureNs", CaptureRequest.SENSOR_EXPOSURE_TIME)
        field("frameDurationNs", CaptureRequest.SENSOR_FRAME_DURATION)
        field("iso", CaptureRequest.SENSOR_SENSITIVITY)
        field("focusDiopters", CaptureRequest.LENS_FOCUS_DISTANCE)
        field("controlMode", CaptureRequest.CONTROL_MODE)
        field("aeMode", CaptureRequest.CONTROL_AE_MODE)
        field("afMode", CaptureRequest.CONTROL_AF_MODE)
        field("awbMode", CaptureRequest.CONTROL_AWB_MODE)
        field("colorCorrectionMode", CaptureRequest.COLOR_CORRECTION_MODE)
        field("shadingMode", CaptureRequest.SHADING_MODE)
        field("lensShadingMapMode", CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE)
        field("opticalStabilization", CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE)
        field("videoStabilization", CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE)
        request.get(CaptureRequest.COLOR_CORRECTION_GAINS)?.let {
            reading["whiteBalanceGains"] = listOf(it.red, it.greenEven, it.greenOdd, it.blue)
        }
        request.get(CaptureRequest.COLOR_CORRECTION_TRANSFORM)?.let { matrix ->
            reading["colorTransform"] = (0..8).map { matrix.getElement(it % 3, it / 3).toDouble() }
        }
        request.get(CaptureRequest.SCALER_CROP_REGION)?.let { reading["crop"] = listOf(it.left, it.top, it.right, it.bottom) }
        return reading
    }

    private fun controlChecks(result: TotalCaptureResult, locked: DngLockedSettings, maximumFocus: Float) =
        stateReading(result).controlChecks(locked.exposureNs, locked.iso, locked.focusDiopters,
            listOf(locked.gains.red, locked.gains.greenEven, locked.gains.greenOdd, locked.gains.blue), maximumFocus)

    companion object {
        private fun matches(settings: DngSettings, locked: DngLockedSettings, maximumFocus: Float): Boolean {
            return settings.exposureNs == locked.exposureNs && settings.iso == locked.iso &&
                matchesColor(settings, locked, maximumFocus)
        }

        private fun matchesColor(settings: DngSettings, locked: DngLockedSettings, maximumFocus: Float): Boolean {
            val wb = settings.whiteBalance ?: return false
            return settings.focusFraction != null && settings.focusFraction * maximumFocus == locked.focusDiopters &&
                wb.contentEquals(floatArrayOf(locked.gains.red, locked.gains.greenEven, locked.gains.blue)) &&
                locked.gains.greenEven == locked.gains.greenOdd
        }

        fun isBayer(c: CameraCharacteristics): Boolean = c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) in setOf(
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB,
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG,
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG,
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR)

        fun applyLocked(builder: CaptureRequest.Builder, value: DngLockedSettings, c: CameraCharacteristics,
                        reportLensShading: Boolean = false) {
            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            applyExposure(builder, value.exposureNs, c)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, value.iso)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, value.focusDiopters)
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, value.gains)
            builder.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, value.transform)
            applySupportedProcessing(builder, c, reportLensShading)
            value.crop?.let { builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(it)) }
            value.opticalStabilization?.let { builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, it) }
            value.videoStabilization?.let { builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, it) }
        }

        private fun applyExposure(builder: CaptureRequest.Builder, exposureNs: Long, c: CameraCharacteristics) {
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
            if (c.availableCaptureRequestKeys.contains(CaptureRequest.SENSOR_FRAME_DURATION)) {
                // Do not leave a short template frame duration competing with a long manual shutter.
                builder.set(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(exposureNs, 33_333_333L))
            }
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
