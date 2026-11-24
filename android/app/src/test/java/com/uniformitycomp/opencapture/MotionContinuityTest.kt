package com.uniformitycomp.opencapture

import org.junit.Assert.*
import org.junit.Test

class MotionContinuityTest {
    private fun feed(m: MotionContinuity, from: Int, to: Int, x: Double = 0.0, gyro: Double = 0.0) {
        for (i in from..to) {
            val t = i * 0.02
            m.sample(0, t, x, 0.0, 9.80665)
            m.sample(1, t, 0.0, 0.0, gyro)
        }
    }
    @Test fun stationaryAndSmallVibrationsKeepContinuity() {
        val m = MotionContinuity(); m.start(true, 0.0); feed(m, 0, 40)
        val a = m.snapshot(.8)
        assertTrue(a.getBoolean("monitoring"))
        feed(m, 41, 100, .05, .005)
        val b = m.snapshot(2.0)
        assertEquals(a.getString("continuityID"), b.getString("continuityID"))
        assertEquals(0L, b.getLong("movementCount"))
    }
    @Test fun sustainedAccelerationOrRotationIsLatchedButOneSpikeIsNot() {
        for (rotation in listOf(false, true)) {
            val m = MotionContinuity(); m.start(true, 0.0); feed(m, 0, 30)
            feed(m, 31, 31, 1.0, .2); feed(m, 32, 40)
            assertEquals(0L, m.snapshot(.8).getLong("movementCount"))
            feed(m, 41, 60, if (rotation) 0.0 else .5, if (rotation) .1 else 0.0)
            assertTrue(m.snapshot(1.2).getLong("movementCount") > 0)
            val count = m.snapshot(1.2).getLong("movementCount")
            feed(m, 61, 80, if (rotation) 0.0 else .5)
            assertEquals(count, m.snapshot(1.6).getLong("movementCount"))
        }
    }
    @Test fun backgroundAndEitherSensorGapChangeContinuity() {
        val m = MotionContinuity(); m.start(true, 0.0); feed(m, 0, 30)
        val a = m.snapshot(.6)
        m.interrupt("app_inactive"); m.start(true, 1.0); feed(m, 50, 80)
        val b = m.snapshot(1.6)
        assertNotEquals(a.getString("continuityID"), b.getString("continuityID"))
        for (i in 81..200) m.sample(0, i*.02, 0.0, 0.0, 9.80665)
        val c = m.snapshot(4.0)
        assertNotEquals(b.getString("continuityID"), c.getString("continuityID"))
        assertFalse(c.getBoolean("monitoring"))
    }
    @Test fun unavailableAndNewInstanceAreExplicit() {
        val a = MotionContinuity(); a.start(false, 0.0)
        assertFalse(a.snapshot(1.0).getBoolean("supported"))
        assertFalse(a.snapshot(1.0).getBoolean("monitoring"))
        assertNotEquals(a.snapshot(1.0).getString("instanceID"), MotionContinuity().snapshot(1.0).getString("instanceID"))
    }
}
