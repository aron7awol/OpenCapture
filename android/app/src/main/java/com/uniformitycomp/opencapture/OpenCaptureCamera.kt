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
            .put("name", "${Build.MANUFACTURER} ${Build.MODEL} / camera ${camera.cameraID()}")
            .put("formats", JSONArray(if (usable && selected != null) listOf("bayerRaw") else emptyList<String>()))
            .put("minISO", iso?.lower).put("maxISO", iso?.upper)
            .put("minExposureSeconds", exposure?.lower?.div(1e9)).put("maxExposureSeconds", exposure?.upper?.div(1e9)?.coerceAtMost(30.0))
            .put("manualExposure", manualSensor).put("manualFocus", manualFocus)
            .put("manualWhiteBalance", usable).put("maxWhiteBalanceGain", 64)
            .put("maxPhotoWidth", selected?.width ?: 0).put("maxPhotoHeight", selected?.height ?: 0)
            .put("rawSizes", JSONArray(sizes.map { "${it.width}x${it.height}" }))
            .put("rawLensShadingApplied", c.get(CameraCharacteristics.SENSOR_INFO_LENS_SHADING_APPLIED))
            .put("osVersion", "Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
            .put("appVersion", BuildConfig.VERSION_NAME).put("appBuild", BuildConfig.VERSION_CODE.toString())
            .put("deviceID", deviceId).put("operations", JSONArray(if (usable) listOf("capture", "applyControls") else emptyList<String>()))
            .put("platform", "android").put("actualControlsSource", "camera2CaptureResult")
            .put("focusModel", "androidNormalizedDiopters")
            .put("maximumFocusDiopters", c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f)
            .put("whiteBalanceModel", "rggbGreenMean")
    }

    fun capture(request: JSONObject, cancel: AtomicBoolean, progress: (String) -> Unit): Pair<JSONObject, File?> {
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
        val directory = if (controlOnly) null else CaptureArchives(root).create(request.getString("id"))
        if (directory != null) {
            val needed = count * cap.getInt("maxPhotoWidth").toLong() * cap.getInt("maxPhotoHeight") * 2 + 64L * 1024 * 1024
            check(directory.usableSpace >= needed) { "Not enough free space for individual DNG originals" }
        }
        val start = Instant.now().toString()
        val records = JSONArray()
        try {
            val sequence = camera.captureDngOriginals(settings, count, directory, cancel) { frame ->
                val index = records.length() + 1
                val native = nativeMetadata(frame.result, c).put("imageTimestampNs", frame.imageTimestampNs)
                val sidecar = File(directory, "frame-%04d.json".format(java.util.Locale.ROOT, index))
                OpenCaptureProtocol.saveJson(sidecar, JSONObject().put("metadataVersion", 1)
                    .put("source", "camera2CaptureResult").put("cameraID", cap.getString("cameraID"))
                    .put("native", native).put("characteristics", staticMetadata(c)))
                records.put(reading(frame.result, c).put("index", index)
                    .put("actualExposureSeconds", native.getDouble("exposureTimeNs") / 1e9)
                    .put("actualISO", native.getInt("iso"))
                    .put("file", OpenCaptureProtocol.receipt(frame.file)).put("metadataFile", OpenCaptureProtocol.receipt(sidecar))
                    .put("width", frame.width).put("height", frame.height)
                    .put("timestampSeconds", native.getDouble("sensorTimestampNs") / 1e9).put("isBayerRAW", true))
                progress("Saved DNG $index / $count")
            }
            if (controlOnly) return reading(sequence.reading, c).put("protocolVersion", 1).put("request", request)
                .put("camera", cap).put("completedAt", Instant.now().toString()) to null
            val manifest = JSONObject().put("protocolVersion", 1).put("request", request).put("camera", cap)
                .put("frames", records).put("startedAt", start).put("completedAt", Instant.now().toString())
                .put("processing", "Camera2 RAW_SENSOR -> DngCreator; individual originals, no averaging or demosaicing; lens shading map requested off; vendor RAW shading declared in capabilities; orientation native")
            OpenCaptureProtocol.saveJson(File(directory, "manifest.json"), manifest)
            return manifest to directory
        } catch (error: Exception) {
            if (directory != null) OpenCaptureProtocol.saveJson(File(directory, "failure.json"), JSONObject()
                .put("status", if (cancel.get()) "cancelled" else "failed").put("message", error.message ?: "Capture failed"))
            throw error
        }
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
        if (Build.VERSION.SDK_INT >= 29) data.put("physicalCameraId", r.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID))
        return data
    }

    private fun staticMetadata(c: CameraCharacteristics): JSONObject {
        val black = c.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        return JSONObject().put("sensorOrientation", c.get(CameraCharacteristics.SENSOR_ORIENTATION))
            .put("lensShadingApplied", c.get(CameraCharacteristics.SENSOR_INFO_LENS_SHADING_APPLIED))
            .put("cfa", c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT))
            .put("whiteLevel", c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL))
            .put("blackLevelPattern", black?.let { JSONArray(listOf(it.getOffsetForIndex(0, 0), it.getOffsetForIndex(1, 0), it.getOffsetForIndex(0, 1), it.getOffsetForIndex(1, 1))) })
            .put("activeArray", c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.toShortString())
            .put("preCorrectionActiveArray", c.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)?.toShortString())
            .put("pixelArray", c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.toString())
    }
}
