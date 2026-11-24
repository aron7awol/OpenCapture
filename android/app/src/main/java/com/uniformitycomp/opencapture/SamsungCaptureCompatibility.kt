// SPDX-License-Identifier: GPL-3.0-only
package com.uniformitycomp.opencapture

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import dalvik.system.PathClassLoader
import org.json.JSONObject

/** Experimental interoperability probe, not a guarantee of longer sensor integration. */
@android.annotation.TargetApi(29)
internal class SamsungCaptureCompatibility(private val events: CameraDiagnostics,
    characteristics: CameraCharacteristics, private val cachedFocus: Pair<Float, Int>? = null,
    private val probe: JSONObject? = null) {
    companion object {
        const val KEY = "samsung.android.control.shootingMode"
        const val FOCUS_KEY = "samsung.android.lens.focusLensPos"
        const val FOCUS_RANGE_KEY = "samsung.android.lens.info.focusLensInfo"
        internal const val FOCUS_POLICY = "samsung-vendor-only-feedback-v2"
        internal fun writeFocus(position: Int, standardFocus: String?, omitStandard: () -> Unit,
                                writePosition: (Int) -> Unit) {
            // Only the explicit diagnostic comparison may send competing commands.
            if (standardFocus != "retain") omitStandard()
            writePosition(position)
        }
        private fun focusRange(c: CameraCharacteristics): IntArray? = runCatching {
            c.get(CameraCharacteristics.Key(FOCUS_RANGE_KEY, IntArray::class.java))
        }.getOrNull()?.takeIf { SamsungFocusSearch.validRange(it) }
        private const val HELPER = "com.samsung.android.sdk.camera.impl.internal.CustomInterfaceHelper"
        private const val JAR = "/system/framework/scamera_sdk_util.jar"
        private fun method() = Class.forName(HELPER, true,
            PathClassLoader(JAR, CameraDevice::class.java.classLoader))
            .getMethod("setSamsungParameter", CameraDevice::class.java, String::class.java)

        internal fun eligible(api: Int, helperAvailable: Boolean): Boolean =
            api >= 29 && helperAvailable

        fun available(): Boolean =
            eligible(Build.VERSION.SDK_INT, true) &&
                runCatching { method() }.isSuccess

        fun diagnostics(c: CameraCharacteristics): JSONObject {
            val helper = runCatching { method() }
            return JSONObject().put("policy", "samsung-helper-probe-v3")
                .put("manufacturer", Build.MANUFACTURER).put("hardware", Build.HARDWARE)
                .put("frameworkHelperAvailable", helper.isSuccess)
                .put("frameworkError", helper.exceptionOrNull()?.javaClass?.simpleName ?: JSONObject.NULL)
                .put("shootingModeRequestAdvertised", c.availableCaptureRequestKeys?.any { it.name == KEY } == true)
                .put("shootingModeResultAdvertised", c.availableCaptureResultKeys?.any { it.name == KEY } == true)
                .put("focusPolicy", FOCUS_POLICY)
                .put("focusPositionRequestAdvertised", c.availableCaptureRequestKeys?.any { it.name == FOCUS_KEY } == true)
                .put("focusPositionResultAdvertised", c.availableCaptureResultKeys?.any { it.name == FOCUS_KEY } == true)
                .put("focusPositionRange", focusRange(c)?.let { org.json.JSONArray(it.toList()) } ?: JSONObject.NULL)
                .put("advertisedKeyRequired", false)
                .put("eligible", eligible(Build.VERSION.SDK_INT, helper.isSuccess))
                .put("physicallyVerified", false)
        }
    }

    init { require(Build.VERSION.SDK_INT >= 29) { "Samsung vendor probes require Android 10 or newer" } }
    private val setter = method()
    private val shootingMode = CaptureRequest.Key(KEY, Int::class.javaObjectType)
    private val focusKey = CaptureRequest.Key(FOCUS_KEY, Int::class.javaObjectType)
    private val focusSearch = focusRange(characteristics)?.let { SamsungFocusSearch(it[0], it[1]) }
    private val probePosition = probe?.let { p ->
        val range = requireNotNull(focusRange(characteristics)) { "Samsung focus range unavailable" }
        range[0] + kotlin.math.round((range[1]-range[0])*p.getDouble("position")).toInt()
    }
    private val focusTolerance = maxOf(.0001f,
        (characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f) * .02f)
    private var focusTarget: Float? = null
    val focusSeed: Pair<Float, Int>? get() = focusTarget?.let { target -> focusSearch?.position?.let { target to it } }

    private fun focusPosition(result: TotalCaptureResult): Int? = runCatching {
        result.get(CaptureResult.Key(FOCUS_KEY, Int::class.javaObjectType))
    }.getOrNull() ?: runCatching {
        result.get(CaptureResult.Key(FOCUS_KEY, IntArray::class.java))?.singleOrNull()
    }.getOrNull()

    fun prepareFocus(result: TotalCaptureResult?, target: Float) {
        focusTarget = target
        if (probePosition != null) {
            events.record("samsung_focus_diagnostic", JSONObject().put("position", probePosition)
                .put("standardFocus", probe!!.getString("standardFocus")).put("measurementEligible", false))
            return
        }
        focusSearch?.selectSeed(target, result?.get(CaptureResult.LENS_FOCUS_DISTANCE),
            result?.let { focusPosition(it) }, cachedFocus, focusTolerance)
        val search = requireNotNull(focusSearch) { "Samsung vendor focus range unavailable; choose a non-vendor capture mode" }
        if (search.position == null) search.seed(search.minimum)
        events.record("samsung_focus_selected", JSONObject().put("targetDiopters", target)
            .put("position", focusSearch?.position ?: JSONObject.NULL)
            .put("rangeAvailable", true).put("policy", FOCUS_POLICY))
    }

    fun applyFocus(builder: CaptureRequest.Builder, target: Float) {
        check(focusTarget == target) { "Samsung focus target changed during capture" }
        val position = requireNotNull(probePosition ?: focusSearch?.position) { "Samsung vendor focus position unavailable" }
        try {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            writeFocus(position, probe?.getString("standardFocus"),
                { builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, null) },
                { builder.set(focusKey, it) })
        } catch (error: Exception) {
            events.record("samsung_focus_request_failed", JSONObject().put("position", position)
                .put("errorChain", CameraDiagnostics.errorChain(error)), true)
            throw IllegalStateException("Samsung vendor focus request is unavailable", error)
        }
    }

    fun probeReady(result: TotalCaptureResult): Boolean = probePosition != null &&
        focusPosition(result) == probePosition &&
        result.get(CaptureResult.LENS_STATE) == CaptureResult.LENS_STATE_STATIONARY

    fun focusReady(result: TotalCaptureResult): Boolean = focusSearch?.position?.let { position ->
        runCatching { result.request.get(focusKey) }.getOrNull() == position &&
            focusPosition(result) == position &&
            result.get(CaptureResult.LENS_STATE) == CaptureResult.LENS_STATE_STATIONARY
    } == true

    /** Called only for a fresh stationary focus-only mismatch, never for exposure/WB failures. */
    fun retryFocus(result: TotalCaptureResult, target: Float): Boolean {
        val search = focusSearch ?: return false
        check(focusReady(result)) { "Samsung vendor focus position was not honored" }
        val actual = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: return false
        val position = search.next(actual, target)
            ?: throw IllegalStateException("Samsung vendor focus could not reach requested focus; share camera diagnostics")
        events.record("samsung_focus_probe", JSONObject().put("targetDiopters", target)
            .put("reportedDiopters", actual).put("nextPosition", position).put("probe", search.probes))
        return true
    }

    fun configure(camera: CameraDevice, enabled: Boolean) {
        val parameters = if (enabled) "first-entrance=false;samsungcamera=true"
            else "first-entrance=true;samsungcamera=false"
        events.record("samsung_vendor_parameters_requested", JSONObject().put("enabled", enabled)
            .put("parameters", parameters))
        try {
            setter.invoke(null, camera, parameters)
            events.record("samsung_vendor_parameters_returned", JSONObject().put("enabled", enabled)
                .put("driverAcceptanceVerified", false))
        } catch (error: Exception) {
            events.record("samsung_vendor_parameters_failed", JSONObject().put("enabled", enabled)
                .put("errorChain", CameraDiagnostics.errorChain(error)), true)
            throw IllegalStateException("Samsung vendor camera setup failed", error)
        }
    }

    fun request(builder: CaptureRequest.Builder) {
        try {
            builder.set(shootingMode, 5)
            events.record("samsung_vendor_request_set", JSONObject().put("key", KEY).put("value", 5)
                .put("driverAcceptanceVerified", false))
        } catch (error: Exception) {
            events.record("samsung_vendor_request_failed", JSONObject().put("key", KEY).put("value", 5)
                .put("errorChain", CameraDiagnostics.errorChain(error)), true)
            throw IllegalStateException("Samsung vendor shooting-mode request could not be set", error)
        }
    }

    fun readback(result: TotalCaptureResult): Map<String, Any> = mapOf(
        "focusPolicy" to FOCUS_POLICY,
        "requestedStandardFocusDiopters" to (result.request.get(CaptureRequest.LENS_FOCUS_DISTANCE) ?: "omitted"),
        "requestedShootingMode" to 5,
        "requestedFocusPosition" to (runCatching { result.request.get(focusKey) }.getOrNull() ?: "standard"),
        "reportedFocusPosition" to (focusPosition(result) ?: "unavailable"),
        "reportedFocusDiopters" to (result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: "unavailable"),
        "reportedShootingMode" to (runCatching {
            result.get(CaptureResult.Key(KEY, Int::class.javaObjectType))
        }.getOrNull() ?: "unavailable"))
}
