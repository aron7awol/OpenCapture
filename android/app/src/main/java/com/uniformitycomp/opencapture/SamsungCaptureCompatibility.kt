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
internal class SamsungCaptureCompatibility(private val events: CameraDiagnostics) {
    companion object {
        const val KEY = "samsung.android.control.shootingMode"
        private const val HELPER = "com.samsung.android.sdk.camera.impl.internal.CustomInterfaceHelper"
        private const val JAR = "/system/framework/scamera_sdk_util.jar"
        private fun method() = Class.forName(HELPER, true,
            PathClassLoader(JAR, CameraDevice::class.java.classLoader))
            .getMethod("setSamsungParameter", CameraDevice::class.java, String::class.java)

        fun available(c: CameraCharacteristics): Boolean =
            Build.VERSION.SDK_INT >= 29 && Build.MANUFACTURER.equals("samsung", ignoreCase = true) &&
                (Build.HARDWARE.contains("exynos", ignoreCase = true) || Build.HARDWARE.startsWith("s5e")) &&
                c.availableCaptureRequestKeys?.any { it.name == KEY } == true &&
                runCatching { method() }.isSuccess

        fun diagnostics(c: CameraCharacteristics): JSONObject {
            val samsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
            val helper = if (samsung) runCatching { method() } else null
            return JSONObject().put("policy", "samsung-exynos-probe-v1")
                .put("manufacturer", Build.MANUFACTURER).put("hardware", Build.HARDWARE)
                .put("frameworkHelperAvailable", helper?.isSuccess == true)
                .put("frameworkError", helper?.exceptionOrNull()?.javaClass?.simpleName ?: JSONObject.NULL)
                .put("shootingModeRequestAdvertised", c.availableCaptureRequestKeys?.any { it.name == KEY } == true)
                .put("shootingModeResultAdvertised", c.availableCaptureResultKeys?.any { it.name == KEY } == true)
                .put("eligible", available(c)).put("physicallyVerified", false)
        }
    }

    init { require(Build.VERSION.SDK_INT >= 29) { "Samsung vendor probes require Android 10 or newer" } }
    private val setter = method()
    private val shootingMode = CaptureRequest.Key(KEY, Int::class.javaObjectType)

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
        builder.set(shootingMode, 5)
    }

    fun readback(result: TotalCaptureResult): Map<String, Any> = mapOf(
        "requestedShootingMode" to 5,
        "reportedShootingMode" to (runCatching {
            result.get(CaptureResult.Key(KEY, Int::class.javaObjectType))
        }.getOrNull() ?: "unavailable"))
}
