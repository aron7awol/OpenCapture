package com.uniformitycomp.opencapture

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import com.uniformitycomp.capture.DngFrame
import com.uniformitycomp.capture.DngCapture
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

class OpenCaptureCamera(private val camera: CameraController, private val deviceId: String, private val root: File) {
    fun capabilities(): JSONObject {
        val c = camera.selectedCharacteristics()
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val manualSensor = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
        val manualFocus = (c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f) > 0f
        val iso = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val exposure = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val usable = DngCapture.isBayer(c) && caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) && manualSensor &&
            caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING)
        val sizes = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(android.graphics.ImageFormat.RAW_SENSOR).orEmpty()
        val selected = sizes.filter { it.width.toLong() * it.height <= 13_000_000L }.maxByOrNull { it.width.toLong() * it.height }
            ?: sizes.filter { it.width.toLong() * it.height <= 60_000_000L }.minByOrNull { it.width.toLong() * it.height }
        return JSONObject().put("protocolVersion", 1).put("cameraID", camera.cameraID())
            .put("captureStreamingModes", JSONArray(if (usable && selected != null) listOf("originals-v1") else emptyList<String>()))
            .put("name", "${Build.MANUFACTURER} ${Build.MODEL} / camera ${camera.cameraID()}")
            .put("formats", JSONArray(if (usable && selected != null) listOf("bayerRaw") else emptyList<String>()))
            .put("frameAveragingModes", JSONArray(if (usable && selected != null && selected.width.toLong()*selected.height <= 13_000_000)
                listOf(BayerAverage.MODE) else emptyList<String>()))
            .put("minISO", iso?.lower).put("maxISO", iso?.upper)
            .put("minExposureSeconds", exposure?.lower?.div(1e9)).put("maxExposureSeconds", exposure?.upper?.div(1e9)?.coerceAtMost(30.0))
            .put("manualExposure", manualSensor).put("manualFocus", manualFocus)
            .put("manualWhiteBalance", usable).put("maxWhiteBalanceGain", 64)
            .put("maxPhotoWidth", selected?.width ?: 0).put("maxPhotoHeight", selected?.height ?: 0)
            .put("rawSizes", JSONArray(sizes.map { "${it.width}x${it.height}" }))
            .put("rawLensShadingApplied", c.get(CameraCharacteristics.SENSOR_INFO_LENS_SHADING_APPLIED) ?: JSONObject.NULL)
            .put("rawShadingPolicy", "camera2-dng-gain-map-v1")
            .put("lensShadingMapRequested", DngCapture.lensShadingMode(c) != null)
            .put("osVersion", "Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
            .put("appVersion", BuildConfig.VERSION_NAME).put("appBuild", BuildConfig.VERSION_CODE.toString())
            .put("deviceID", deviceId).put("operations", JSONArray(if (usable) listOf("capture", "applyControls") else emptyList<String>()))
            .put("platform", "android").put("actualControlsSource", "camera2CaptureResult")
            .put("focusModel", "androidNormalizedDiopters")
            .put("maximumFocusDiopters", c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f)
            .put("whiteBalanceModel", "rggbGreenMean")
    }

    fun capture(request: JSONObject, cancel: AtomicBoolean, progress: (String) -> Unit): Pair<JSONObject, File?> =
        captureImpl(request,cancel,progress,null)

    fun captureStreaming(request: JSONObject, cancel: AtomicBoolean, progress: (String) -> Unit, stream: OriginalStream): Pair<JSONObject, File?> =
        captureImpl(request,cancel,progress,stream)

    private fun captureImpl(request: JSONObject, cancel: AtomicBoolean, progress: (String) -> Unit, stream: OriginalStream?): Pair<JSONObject, File?> {
        val settings = OpenCaptureProtocol.request(request)
        val previewDeadline = System.nanoTime() + 10_000_000_000L
        while (!camera.previewReady) {
            check(!cancel.get() && System.nanoTime() < previewDeadline) { "Camera preview is not ready" }
            Thread.sleep(50)
        }
        val cap = capabilities()
        val c = camera.selectedCharacteristics()
        val isoRange = requireNotNull(c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE))
        val exposureRange = requireNotNull(c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE))
        require(settings.iso == null || isoRange.contains(settings.iso)) { "ISO is outside this camera's range" }
        require(settings.exposureNs == null || exposureRange.contains(settings.exposureNs)) { "Exposure is outside this camera's range" }
        val controlOnly = request.optString("operation") == "applyControls"
        val count = if (controlOnly) 0 else request.getInt("frameCount")
        val averaging = OpenCaptureProtocol.averaging(request)
        require(averaging == null || cap.getJSONArray("frameAveragingModes").length() > 0) { "This camera does not support on-device averaging" }
        val selection = averaging?.let { BayerAverage.Selection(count, it.policy) }
        fun checkCancelled() {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Inspection cancelled")
            check(!cancel.get()) { "Capture cancelled" }
        }
        val directory = if (controlOnly) null else CaptureArchives(root).create(request.getString("id"))
        if (directory != null) {
            val needed = (stream?.limit ?: selection?.maxAttempts ?: count) * cap.getInt("maxPhotoWidth").toLong() * cap.getInt("maxPhotoHeight") * 2 +
                (if (selection == null) 64L else 160L) * 1024 * 1024
            check(directory.usableSpace >= needed) { "Not enough free space for individual DNG originals" }
        }
        val start = Instant.now().toString()
        val captureStarted = System.nanoTime()
        stream?.begin(cap,start,"Camera2 RAW_SENSOR -> DngCreator; immutable original frames, controller selection; no averaging or demosaicing")
        val records = JSONArray()
        val processor = if (selection == null) null else FrameProcessor<Pair<com.uniformitycomp.capture.DngFrame,BayerAverage.Evidence>>()
        fun inspectFrame(frame: com.uniformitycomp.capture.DngFrame): BayerAverage.Evidence {
            val integrity = frame.diagnostics["sampleIntegrity"] as? Map<*, *>
            val evidence = BayerAverage.inspect(frame.file, frame.width, frame.height,
                integrity?.get("assessment") as? String ?: "incomplete_diagnostics", check = ::checkCancelled,
                timingInvalid = (frame.diagnostics["timingIntegrity"] as? Map<*, *>)?.get("status") == "invalid")
            val map = frame.result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)
            require(!cap.getBoolean("lensShadingMapRequested") || (map != null &&
                frame.result.get(CaptureResult.STATISTICS_LENS_SHADING_MAP_MODE) == 1 &&
                frame.result.get(CaptureResult.SHADING_MODE) in setOf(1,2))) { "Camera omitted requested lens shading metadata" }
            if (map != null) {
                val gains = FloatArray(map.gainFactorCount).also { map.copyGainFactors(it,0) }
                DngGainMaps.verify(evidence.layout.gainMaps,map.rowCount,map.columnCount,
                    gains.map { it.toDouble() },evidence.layout.cfa)
            } else require(evidence.layout.gainMaps.isEmpty()) { "DNG gains have no Camera2 provenance" }
            return evidence
        }
        fun recordFrame(frame: com.uniformitycomp.capture.DngFrame, evidence: BayerAverage.Evidence? = null) {
            if(stream?.isFrozen==true) { frame.file.delete(); return }
            val index = records.length() + 1
            val integrity = frame.diagnostics["sampleIntegrity"] as? Map<*, *>
            val native = nativeMetadata(frame.result, c).put("imageTimestampNs", frame.imageTimestampNs)
            val sidecar = File(directory, "frame-%04d.json".format(java.util.Locale.ROOT, index))
            OpenCaptureProtocol.saveJson(sidecar, JSONObject().put("metadataVersion", 1)
                .put("source", "camera2CaptureResult").put("cameraID", cap.getString("cameraID"))
                .put("native", native).put("characteristics", staticMetadata(c))
                .put("captureDiagnostics", JSONObject(frame.diagnostics)))
            if (evidence != null) selection!!.attempts.add(evidence)
            // Full evidence is in the hashed sidecar; keep logcat below its line limit.
            android.util.Log.i("OpenCaptureRAW", "frame=$index sensorNs=${frame.imageTimestampNs} " +
                "diagnostics=${frame.diagnostics["diagnosticsVersion"]} " +
                "assessment=${integrity?.get("assessment")} findings=${integrity?.get("findings")}")
            records.put(reading(frame.result, c).put("index", index)
                .put("actualExposureSeconds", native.getDouble("exposureTimeNs") / 1e9)
                .put("actualISO", native.getInt("iso"))
                .put("file", OpenCaptureProtocol.receipt(frame.file)).put("metadataFile", OpenCaptureProtocol.receipt(sidecar))
                .put("width", frame.width).put("height", frame.height)
                .put("timestampSeconds", native.getDouble("sensorTimestampNs") / 1e9).put("isBayerRAW", true))
            stream?.publish(records.getJSONObject(index-1))
            progress(if (selection == null) "Saved DNG $index / $count" else
                "Checked $index attempts; ${selection.accepted().size} / $count usable")
        }
        fun drainInspection() {
            processor?.take()?.let { (frame,evidence) -> recordFrame(frame,evidence) }
        }
        fun needsAverage(): Boolean {
            if (processor!!.isDone || (processor.hasPending && selection!!.accepted().size+1 >= count)) drainInspection()
            return selection!!.needsMore()
        }
        try {
            val sequence = camera.captureDngOriginals(settings, count, directory, cancel,
                stream?.limit ?: selection?.maxAttempts ?: count,
                stream?.let { { it.next() } } ?: selection?.let { { needsAverage() } },
                overlap = selection == null && (count > 1 || stream != null),
                tryReserveNext = stream?.let { { it.tryNext() } }) { frame ->
                if (processor == null) recordFrame(frame)
                else {
                    drainInspection()
                    if (!selection!!.needsMore()) frame.file.delete()
                    else processor.submit {
                        val started = android.os.SystemClock.elapsedRealtimeNanos()
                        val evidence = inspectFrame(frame)
                        frame.copy(diagnostics=frame.diagnostics+mapOf("inspectionStartedElapsedNs" to started,
                            "inspectionEndedElapsedNs" to android.os.SystemClock.elapsedRealtimeNanos())) to evidence
                    }
                }
            }
            drainInspection()
            if (controlOnly) return reading(sequence.reading, c).put("protocolVersion", 1).put("request", request)
                .put("camera", cap).put("completedAt", Instant.now().toString()) to null
            if(stream!=null) return stream.finish() to directory
            val aggregationStarted = System.nanoTime()
            val manifest = JSONObject().put("protocolVersion", 1).put("request", request).put("camera", cap)
                .put("frames", records).put("startedAt", start).put("completedAt", Instant.now().toString())
                .put("processing", "Camera2 RAW_SENSOR -> DngCreator; original samples; remaining lens shading map requested where supported, actual metadata in sidecars and DNG; no demosaicing; orientation native")
            if (selection != null) {
                val accepted = selection.accepted()
                val reasons = selection.reasons()
                val complete = accepted.size == count
                val reference = accepted.firstOrNull() ?: 0
                val retained = records.length().let { size -> (0 until size).filter {
                    averaging!!.keepOriginals || it == reference || reasons[it] != null
                } }
                for (i in 0 until records.length()) {
                    val sidecar = File(directory, "frame-%04d.json".format(java.util.Locale.ROOT, i+1))
                    val metadata = JSONObject(sidecar.readText(Charsets.UTF_8)).put("averagingSelection",
                        JSONObject(selection.attempts[i].fields()).put("policy", averaging!!.policy)
                            .put("accepted", reasons[i] == null).put("reason", reasons[i] ?: JSONObject.NULL))
                    OpenCaptureProtocol.saveJson(sidecar, metadata)
                    records.getJSONObject(i).put("metadataFile", OpenCaptureProtocol.receipt(sidecar))
                }
                val aggregation = JSONObject(selection.attempts[reference].layout.fields())
                    .put("version", 3).put("mode", BayerAverage.MODE).put("status", if (complete) "complete" else "insufficient")
                    .put("targetFrames", count).put("attemptedFrames", records.length())
                    .put("acceptedIndices", JSONArray(accepted.map { it+1 })).put("referenceIndex", reference+1)
                    .put("retainedOriginalIndices", JSONArray(retained.map { it+1 }))
                    .put("rejected", JSONArray(reasons.mapIndexedNotNull { i, reason -> reason?.let {
                        JSONObject().put("index", i+1).put("reason", it)
                    } }))
                if (complete) {
                    progress("Averaging $count usable RAW frames")
                    BayerAverage.write(accepted.map { selection.attempts[it] }, requireNotNull(directory), ::checkCancelled)
                    aggregation.put("files", JSONObject().put("moments", OpenCaptureProtocol.receipt(File(directory, "average.f32")))
                        .put("clipping", OpenCaptureProtocol.receipt(File(directory, "average-clips.u8"))))
                } else aggregation.put("files", JSONObject())
                manifest.put("aggregation", aggregation).put("processing", "Camera2 RAW_SENSOR -> DngCreator -> signed black/white normalization -> embedded DNG GainMaps -> fractional Bayer-site means and luma moments; native clipping before gains; no demosaic")
            }
            manifest.put("completedAt", Instant.now().toString()).put("performance", JSONObject()
                .put("captureAndInspectionSeconds",(aggregationStarted-captureStarted)/1e9)
                .put("aggregationAndPackagingSeconds",(System.nanoTime()-aggregationStarted)/1e9))
            OpenCaptureProtocol.saveJson(File(directory, "manifest.json"), manifest)
            return manifest to directory
        } catch (error: Exception) {
            if (directory != null) OpenCaptureProtocol.saveJson(File(directory, "failure.json"), JSONObject()
                .put("status", if (cancel.get()) "cancelled" else "failed").put("message", error.message ?: "Capture failed"))
            throw error
        } finally { processor?.close() }
    }

    fun currentReading(): JSONObject = reading(requireNotNull(camera.lastPreviewResult) { "Wait for the camera preview to settle" }, camera.selectedCharacteristics())

    private fun reading(result: TotalCaptureResult, c: CameraCharacteristics): JSONObject {
        val gains = requireNotNull(result.get(CaptureResult.COLOR_CORRECTION_GAINS)) { "Camera omitted actual white balance" }
        val focus = requireNotNull(result.get(CaptureResult.LENS_FOCUS_DISTANCE)) { "Camera omitted actual focus" }
        val maximum = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val normalized = if (maximum == 0f) { require(focus == 0f); 0f } else focus / maximum
        require(normalized.isFinite() && normalized in 0f..1f) { "Invalid actual focus" }
        val wb = JSONObject().put("red", gains.red).put("green", (gains.greenEven + gains.greenOdd) / 2f).put("blue", gains.blue)
        require(listOf(gains.red, gains.greenEven, gains.greenOdd, gains.blue).all { it.isFinite() && it in 1f..64f }) { "Unsupported actual white balance" }
        return JSONObject().put("deviceExposureSeconds", requireNotNull(result.get(CaptureResult.SENSOR_EXPOSURE_TIME)) / 1e9)
            .put("deviceISO", requireNotNull(result.get(CaptureResult.SENSOR_SENSITIVITY)))
            .put("lensPosition", normalized).put("whiteBalance", wb)
    }

    private fun nativeMetadata(r: TotalCaptureResult, c: CameraCharacteristics): JSONObject {
        val g = requireNotNull(r.get(CaptureResult.COLOR_CORRECTION_GAINS))
        val data = JSONObject().put("sensorTimestampNs", requireNotNull(r.get(CaptureResult.SENSOR_TIMESTAMP)))
            .put("frameNumber", r.frameNumber)
            .put("exposureTimeNs", requireNotNull(r.get(CaptureResult.SENSOR_EXPOSURE_TIME)))
            .put("iso", requireNotNull(r.get(CaptureResult.SENSOR_SENSITIVITY)))
            .put("focusDistanceDiopters", requireNotNull(r.get(CaptureResult.LENS_FOCUS_DISTANCE)))
            .put("whiteBalanceRggb", JSONArray(listOf(g.red, g.greenEven, g.greenOdd, g.blue)))
            .put("frameDurationNs", r.get(CaptureResult.SENSOR_FRAME_DURATION))
            .put("focalLength", r.get(CaptureResult.LENS_FOCAL_LENGTH)).put("aperture", r.get(CaptureResult.LENS_APERTURE))
            .put("aeMode", r.get(CaptureResult.CONTROL_AE_MODE)).put("awbMode", r.get(CaptureResult.CONTROL_AWB_MODE))
            .put("afMode", r.get(CaptureResult.CONTROL_AF_MODE)).put("controlMode", r.get(CaptureResult.CONTROL_MODE))
            .put("noiseReductionMode", r.get(CaptureResult.NOISE_REDUCTION_MODE)).put("hotPixelMode", r.get(CaptureResult.HOT_PIXEL_MODE))
            .put("shadingMode", r.get(CaptureResult.SHADING_MODE)).put("lensShadingMapMode", r.get(CaptureResult.STATISTICS_LENS_SHADING_MAP_MODE))
            .put("distortionCorrectionMode", r.get(CaptureResult.DISTORTION_CORRECTION_MODE))
            .put("opticalStabilizationMode", r.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE))
            .put("videoStabilizationMode", r.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE))
            .put("cropRegion", r.get(CaptureResult.SCALER_CROP_REGION)?.toShortString())
            .put("colorCorrectionTransform", r.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.toString())
            .put("maximumFocusDiopters", c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f)
            .put("sensorTimestampSource", c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ?: 0)
            .put("rollingShutterSkewNs", r.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW))
            .put("pipelineDepth", r.get(CaptureResult.REQUEST_PIPELINE_DEPTH))
            .put("aeState", r.get(CaptureResult.CONTROL_AE_STATE))
            .put("afState", r.get(CaptureResult.CONTROL_AF_STATE))
            .put("awbState", r.get(CaptureResult.CONTROL_AWB_STATE))
            .put("lensState", r.get(CaptureResult.LENS_STATE))
            .put("blackLevelLocked", r.get(CaptureResult.BLACK_LEVEL_LOCK))
            .put("dynamicWhiteLevel", r.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL))
            .put("dynamicBlackLevel", r.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)?.let { JSONArray(it.toList()) })
        val map = r.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)
        data.put("lensShadingMap", if (map == null) JSONObject.NULL else {
            require(map.rowCount in 1..64 && map.columnCount in 1..64) { "Oversized Camera2 lens shading map" }
            val gains = FloatArray(map.gainFactorCount).also { map.copyGainFactors(it,0) }
            JSONObject().put("rows",map.rowCount).put("columns",map.columnCount)
                .put("channelOrder",JSONArray(listOf("R","G_even","G_odd","B")))
                .put("gains",JSONArray(gains.map { it.toDouble() }))
        })
        // Often empty for logical-only outputs; never substitute logical readings.
        @Suppress("DEPRECATION")
        val physical = if (Build.VERSION.SDK_INT >= 31) r.physicalCameraTotalResults else r.physicalCameraResults
        val physicalResults = JSONObject()
        for ((id, result) in physical) physicalResults.put(id, JSONObject()
            .put("sensorTimestampNs", result.get(CaptureResult.SENSOR_TIMESTAMP))
            .put("exposureTimeNs", result.get(CaptureResult.SENSOR_EXPOSURE_TIME))
            .put("iso", result.get(CaptureResult.SENSOR_SENSITIVITY))
            .put("frameDurationNs", result.get(CaptureResult.SENSOR_FRAME_DURATION)))
        data.put("physicalCaptureResults", physicalResults)
        if (Build.VERSION.SDK_INT >= 29) data.put("physicalCameraId", r.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID))
        return data
    }

    private fun staticMetadata(c: CameraCharacteristics): JSONObject {
        val black = c.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        return JSONObject().put("sensorOrientation", c.get(CameraCharacteristics.SENSOR_ORIENTATION))
            .put("deviceBuild", JSONObject().put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
                .put("hardware", Build.HARDWARE).put("buildID", Build.ID).put("androidRelease", Build.VERSION.RELEASE)
                .put("securityPatch", Build.VERSION.SECURITY_PATCH).put("apiLevel", Build.VERSION.SDK_INT))
            .put("lensShadingApplied", c.get(CameraCharacteristics.SENSOR_INFO_LENS_SHADING_APPLIED) ?: JSONObject.NULL)
            .put("cfa", c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT))
            .put("whiteLevel", c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL))
            .put("blackLevelPattern", black?.let { JSONArray(listOf(it.getOffsetForIndex(0, 0), it.getOffsetForIndex(1, 0), it.getOffsetForIndex(0, 1), it.getOffsetForIndex(1, 1))) })
            .put("activeArray", c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.toShortString())
            .put("preCorrectionActiveArray", c.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)?.toShortString())
            .put("pixelArray", c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.toString())
    }
}
