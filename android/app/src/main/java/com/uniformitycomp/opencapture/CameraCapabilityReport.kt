// SPDX-License-Identifier: GPL-3.0-only
package com.uniformitycomp.opencapture

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
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
            .put("hardwareLevel", c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ?: JSONObject.NULL)
            .put("colorFilterArrangement", c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: JSONObject.NULL)
            .put("bayer", bayer).put("raw", raw).put("manualSensor", manual).put("manualPostProcessing", post)
            .put("rawSizes", JSONArray(sizes.map { "${it.width}x${it.height}" }))
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

    private fun IntArray?.orEmptyInts() = this ?: intArrayOf()
}
