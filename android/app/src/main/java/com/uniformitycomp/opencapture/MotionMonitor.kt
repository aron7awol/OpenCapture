// SPDX-License-Identifier: GPL-3.0-only
package com.uniformitycomp.opencapture

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock

class MotionMonitor(context: Context) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accel = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyro = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val continuity = MotionContinuity()
    private var running = false
    private fun now() = SystemClock.elapsedRealtimeNanos() / 1e9
    fun start() {
        if (running) return
        val available = accel != null && gyro != null
        continuity.start(available, now())
        if (!available) return
        running = true
        val a = manager.registerListener(this, accel, 20_000)
        val g = manager.registerListener(this, gyro, 20_000)
        if (!a || !g) stop("sensor_registration_failed")
    }
    fun stop(reason: String = "app_inactive") {
        continuity.interrupt(reason)
        manager.unregisterListener(this); running = false
    }
    fun snapshot() = continuity.snapshot(now())
    override fun onSensorChanged(event: SensorEvent) {
        if (!running) return
        val sensor = if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) 0 else 1
        continuity.sample(sensor, event.timestamp / 1e9,
            event.values[0].toDouble(), event.values[1].toDouble(), event.values[2].toDouble())
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
