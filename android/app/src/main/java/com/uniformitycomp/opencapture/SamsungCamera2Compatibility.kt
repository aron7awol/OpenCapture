// SPDX-License-Identifier: GPL-3.0-only
package com.uniformitycomp.opencapture

import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import org.json.JSONObject

/** Camera2-only experiment: no framework helper or vendor focus commands. */
internal class SamsungCamera2Compatibility(private val events: CameraDiagnostics,
    private val shootingMode5: Boolean) {
    companion object {
        fun available() = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
        private const val KEY = "samsung.android.control.shootingMode"
    }

    fun requestStill(builder: CaptureRequest.Builder) {
        if (!shootingMode5) return
        try {
            builder.set(CaptureRequest.Key(KEY, Int::class.javaObjectType), 5)
            events.record("samsung_camera2_shooting_mode", JSONObject().put("requested", 5)
                .put("driverAcceptanceVerified", false))
        } catch (error: Exception) {
            throw IllegalStateException("Samsung Camera2 shooting mode 5 unavailable; turn it off to compare", error)
        }
    }

    fun readback(result: TotalCaptureResult): Map<String, Any> = mapOf(
        "profile" to "samsung-camera2-v1", "frameworkHelperActivated" to false,
        "focusPolicy" to "standard-camera2",
        "requestedStandardFocusDiopters" to (result.request.get(CaptureRequest.LENS_FOCUS_DISTANCE) ?: "omitted"),
        "reportedFocusDiopters" to (result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: "unavailable"),
        "requestedShootingMode" to (runCatching {
            result.request.get(CaptureRequest.Key(KEY, Int::class.javaObjectType))
        }.getOrNull() ?: "driver-default"),
        "reportedShootingMode" to (runCatching {
            result.get(CaptureResult.Key(KEY, Int::class.javaObjectType))
        }.getOrNull() ?: "unavailable"))
}
