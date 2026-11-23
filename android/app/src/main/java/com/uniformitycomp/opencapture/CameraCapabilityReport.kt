// SPDX-License-Identifier: GPL-3.0-only
package com.uniformitycomp.opencapture

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.os.Build
import com.uniformitycomp.capture.DngCapture
import org.json.JSONArray
import org.json.JSONObject

internal object CameraCapabilityReport {
    fun missingForRaw(bayer: Boolean, raw: Boolean, sensor: Boolean, post: Boolean, size: Boolean) =
        listOf("bayerColorFilter" to bayer, "rawCapability" to raw, "manualSensor" to sensor,
            "manualPostProcessing" to post, "supportedRawSize" to size).filterNot { it.second }.map { it.first }

    fun describe(id: String, c: CameraCharacteristics): JSONObject {
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES).orEmptyInts()
        val request = c.availableCaptureRequestKeys
        val result = c.availableCaptureResultKeys
        val sizes = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.RAW_SENSOR).orEmpty()
        val bayer = DngCapture.isBayer(c)
        val raw = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW in caps
        val manual = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps
        val post = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING in caps
        val ev = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val step = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
        return JSONObject().put("cameraID", id)
            .put("samsungCompatibility", SamsungCaptureCompatibility.diagnostics(c))
            .put("hardwareLevel", c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ?: JSONObject.NULL)
            .put("colorFilterArrangement", c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: JSONObject.NULL)
            .put("bayer", bayer).put("raw", raw).put("manualSensor", manual).put("manualPostProcessing", post)
            .put("rawSizes", JSONArray(sizes.map { "${it.width}x${it.height}" }))
            .put("rawResolutionDiagnostics", rawResolutionDiagnostics(c))
            .put("missingForOpenCaptureRaw", JSONArray(missingForRaw(bayer, raw, manual, post,
                sizes.any { it.width.toLong() * it.height in 1..60_000_000L })))
            .put("aeModes", JSONArray(c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES).orEmptyInts().toList()))
            .put("aeLockAvailable", c.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) ?: JSONObject.NULL)
            .put("aeLockRequestKey", CaptureRequest.CONTROL_AE_LOCK in request)
            .put("aeLockResultKey", CaptureResult.CONTROL_AE_LOCK in result)
            .put("aeStateResultKey", CaptureResult.CONTROL_AE_STATE in result)
            .put("exposureTimeResultKey", CaptureResult.SENSOR_EXPOSURE_TIME in result)
            .put("isoResultKey", CaptureResult.SENSOR_SENSITIVITY in result)
            .put("aeCompensationRangeSteps", ev?.let { JSONArray(listOf(it.lower, it.upper)) } ?: JSONObject.NULL)
            .put("aeCompensationStepEv", step?.toDouble()?.takeIf { it.isFinite() } ?: JSONObject.NULL)
    }

    @SuppressLint("NewApi") // Each API 31 read is lazy and gated by putDiagnostic.
    private fun rawResolutionDiagnostics(c: CameraCharacteristics): JSONObject {
        val report = JSONObject().put("androidApiLevel", Build.VERSION.SDK_INT)
            .put("source", "camera2Characteristics").put("captureTested", false)
        val errors = JSONObject()
        fun field(name: String, minApi: Int = 28, read: () -> Any?) =
            putDiagnostic(report, errors, name, Build.VERSION.SDK_INT, minApi, read)
        fun sizes(values: Array<android.util.Size>?) = values?.let {
            JSONArray(it.map { size -> "${size.width}x${size.height}" })
        }
        fun size(value: android.util.Size?) = value?.let { JSONArray(listOf(it.width, it.height)) }
        fun rect(value: android.graphics.Rect?) = value?.let { JSONArray(listOf(it.left, it.top, it.right, it.bottom)) }
        field("streamConfigurationMapAvailable") { c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) != null }
        field("rawSizes") { sizes(c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.RAW_SENSOR)) }
        field("highResolutionRawSizes") {
            sizes(c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getHighResolutionOutputSizes(ImageFormat.RAW_SENSOR))
        }
        field("colorFilterArrangement") { c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) }
        field("pixelArraySize") { size(c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)) }
        field("activeArraySize") { rect(c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)) }
        field("preCorrectionActiveArraySize") { rect(c.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)) }
        field("ultraHighResolutionSensor", 31) {
            c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR)
        }
        field("remosaicReprocessing", 31) {
            c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_REMOSAIC_REPROCESSING)
        }
        field("sensorPixelModeRequestKey", 31) { CaptureRequest.SENSOR_PIXEL_MODE in c.availableCaptureRequestKeys }
        field("sensorPixelModeResultKey", 31) { CaptureResult.SENSOR_PIXEL_MODE in c.availableCaptureResultKeys }
        field("maximumResolutionStreamConfigurationMapAvailable", 31) {
            c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION) != null
        }
        field("maximumResolutionRawSizes", 31) {
            sizes(c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)?.getOutputSizes(ImageFormat.RAW_SENSOR))
        }
        field("maximumResolutionHighResolutionRawSizes", 31) {
            sizes(c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)?.getHighResolutionOutputSizes(ImageFormat.RAW_SENSOR))
        }
        field("maximumResolutionJpegSizes", 31) {
            sizes(c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)?.getOutputSizes(ImageFormat.JPEG))
        }
        field("maximumResolutionPixelArraySize", 31) { size(c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION)) }
        field("maximumResolutionActiveArraySize", 31) { rect(c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION)) }
        field("maximumResolutionPreCorrectionActiveArraySize", 31) { rect(c.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION)) }
        field("binningFactor", 31) { size(c.get(CameraCharacteristics.SENSOR_INFO_BINNING_FACTOR)) }
        return report.put("queryErrors", errors)
    }

    // A broken optional vendor characteristic must not hide the remaining camera report.
    internal fun putDiagnostic(report: JSONObject, errors: JSONObject, name: String,
                               apiLevel: Int, minApi: Int, read: () -> Any?) {
        if (apiLevel < minApi) {
            report.put(name, JSONObject.NULL)
            return
        }
        try {
            report.put(name, read() ?: JSONObject.NULL)
        } catch (error: Exception) {
            report.put(name, JSONObject.NULL)
            errors.put(name, error.javaClass.simpleName)
        }
    }

    private fun IntArray?.orEmptyInts() = this ?: intArrayOf()
}
