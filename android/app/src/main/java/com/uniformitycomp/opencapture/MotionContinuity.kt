// SPDX-License-Identifier: GPL-3.0-only
package com.uniformitycomp.opencapture

import org.json.JSONObject
import java.util.UUID
import kotlin.math.sqrt

/** Foreground continuity, independent of camera and network sessions. SI units. */
class MotionContinuity {
    private val instance = UUID.randomUUID().toString()
    private var epoch = UUID.randomUUID().toString()
    private var count = 0L
    private var supported = false
    private var active = false
    private var started = 0.0
    private val last = doubleArrayOf(-1.0, -1.0)
    private val suspect = doubleArrayOf(-1.0, -1.0)
    private var baseline: DoubleArray? = null
    private var lastMovement = -10.0
    private var reason = "not_started"

    @Synchronized fun start(available: Boolean, now: Double) {
        if (active) return
        supported = available; active = available; started = now
        last.fill(-1.0); suspect.fill(-1.0); baseline = null
        reason = if (available) "starting" else "sensors_unavailable"
    }
    @Synchronized fun interrupt(why: String) {
        epoch = UUID.randomUUID().toString(); active = false
        last.fill(-1.0); suspect.fill(-1.0); baseline = null; reason = why
    }
    private fun stale(now: Double) = active && now - started > 2 && last.any { it < 0 || now - it > 2 }
    @Synchronized fun sample(sensor: Int, time: Double, x: Double, y: Double, z: Double) {
        if (!active || sensor !in 0..1) return
        if (!listOf(time, x, y, z).all { it.isFinite() }) { interrupt("sensor_error"); return }
        if (last[sensor] >= 0 && time <= last[sensor]) { interrupt("sensor_clock_changed"); return }
        if (stale(time)) {
            interrupt("sensor_gap"); start(supported, time)
        }
        last[sensor] = time
        val vector = doubleArrayOf(x, y, z)
        if (sensor == 0 && baseline == null) baseline = vector
        val magnitude = if (sensor == 0) sqrt(vector.indices.sumOf { i ->
            val d = vector[i] - baseline!![i]; d * d
        }) else sqrt(x*x + y*y + z*z)
        val moving = magnitude > if (sensor == 0) 0.25 else 0.03
        if (!moving) suspect[sensor] = -1.0
        else {
            if (suspect[sensor] < 0) suspect[sensor] = time
            if (time - suspect[sensor] >= 0.12 && time - lastMovement >= 1) {
                count++; lastMovement = time; reason = "movement_detected"
                if (sensor == 0) baseline = vector
            }
        }
    }
    @Synchronized fun snapshot(now: Double): JSONObject {
        if (stale(now)) { interrupt("sensor_gap"); start(supported, now) }
        val ready = active && last.all { it >= 0 && now - it <= 2 } && now - started >= 0.5
        return JSONObject().put("schemaVersion", 1).put("instanceID", instance)
            .put("continuityID", epoch).put("movementCount", count).put("supported", supported)
            .put("monitoring", ready).put("reason", reason)
    }
}
