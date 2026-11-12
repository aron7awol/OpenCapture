package com.uniformitycomp.opencapture

import com.uniformitycomp.capture.PreviewGeometry
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class PreviewGeometryTest {
    @Test fun textureTransformAccountsForThePlatformsSensorRotationExactlyOnce() {
        for ((bw, bh) in listOf(1440 to 1080, 1920 to 1080, 1280 to 960))
            for ((vw, vh) in listOf(360 to 300, 760 to 180, 280 to 120, 320 to 320))
                for (sensor in listOf(0, 90, 180, 270))
                    for (display in listOf(0, 90, 180, 270)) {
                        val orientedWidth = if (sensor % 180 == 0) bw else bh
                        val orientedHeight = if (sensor % 180 == 0) bh else bw
                        val m = PreviewGeometry.textureFit(bw, bh, vw, vh, sensor, display)
                        val sensorAngle = Math.toRadians(sensor.toDouble())
                        val finalAngle = Math.toRadians((sensor - display).toDouble())
                        val swapped = (sensor - display) % 180 != 0
                        val scale = minOf(vw.toDouble() / (if (swapped) bh else bw),
                            vh.toDouble() / (if (swapped) bw else bh))
                        for ((x, y) in listOf(0 to 0, bw to 0, 0 to bh, bw to bh, bw / 3 to bh / 4)) {
                            val dx = x - bw / 2.0; val dy = y - bh / 2.0
                            // Camera2 rotates first, then TextureView stretches to its bounds.
                            val tx = (cos(sensorAngle) * dx - sin(sensorAngle) * dy + orientedWidth / 2.0) * vw / orientedWidth
                            val ty = (sin(sensorAngle) * dx + cos(sensorAngle) * dy + orientedHeight / 2.0) * vh / orientedHeight
                            val actualX = m[0] * tx + m[1] * ty + m[2]
                            val actualY = m[3] * tx + m[4] * ty + m[5]
                            val label = "sensor=$sensor display=$display viewport=${vw}x$vh point=$x,$y"
                            assertEquals(label, vw / 2.0 + scale * (cos(finalAngle) * dx - sin(finalAngle) * dy), actualX, .002)
                            assertEquals(label, vh / 2.0 + scale * (sin(finalAngle) * dx + cos(finalAngle) * dy), actualY, .002)
                            assertTrue(label, actualX in -.002..(vw + .002))
                            assertTrue(label, actualY in -.002..(vh + .002))
                        }
                    }
    }

    @Test fun textureTransformRejectsInvalidOrientationAndEmptyViewports() {
        assertThrows(IllegalArgumentException::class.java) { PreviewGeometry.textureFit(1440, 1080, 360, 300, 45, 90) }
        assertThrows(IllegalArgumentException::class.java) { PreviewGeometry.textureFit(1440, 1080, 360, 300, 90, -90) }
        assertThrows(IllegalArgumentException::class.java) { PreviewGeometry.textureFit(1440, 1080, 0, 300, 90, 0) }
    }

    @Test fun fullFrameFitsWithoutStretchingInEveryRotationAndViewport() {
        for ((bw, bh) in listOf(1440 to 1080, 1920 to 1080, 1280 to 960))
            for ((vw, vh) in listOf(360 to 520, 760 to 180, 280 to 120, 320 to 320))
                for (rotation in listOf(0, 90, 180, 270)) {
                    val m = PreviewGeometry.fit(bw, bh, vw, vh, rotation)
                    fun point(x: Double, y: Double): Pair<Double, Double> {
                        // TextureView's input coordinates have already been stretched to its bounds.
                        val vx = x * vw / bw; val vy = y * vh / bh
                        return m[0] * vx + m[1] * vy + m[2] to m[3] * vx + m[4] * vy + m[5]
                    }
                    val corners = listOf(point(0.0, 0.0), point(bw.toDouble(), 0.0), point(0.0, bh.toDouble()), point(bw.toDouble(), bh.toDouble()))
                    for ((x, y) in corners) { assertTrue(x in -0.001..(vw + 0.001)); assertTrue(y in -0.001..(vh + 0.001)) }
                    assertEquals(vw / 2.0, corners.map { it.first }.average(), .001)
                    assertEquals(vh / 2.0, corners.map { it.second }.average(), .001)
                    val origin = point(0.0, 0.0); val x = point(1.0, 0.0); val y = point(0.0, 1.0)
                    assertEquals(hypot(x.first-origin.first, x.second-origin.second), hypot(y.first-origin.first, y.second-origin.second), .00001)
                    val spanX = corners.maxOf { it.first } - corners.minOf { it.first }
                    val spanY = corners.maxOf { it.second } - corners.minOf { it.second }
                    assertTrue(kotlin.math.abs(spanX-vw) < .001 || kotlin.math.abs(spanY-vh) < .001)
                }
    }
    @Test fun previewSelectionPrefersPhotoAspectWithinStreamingBudget() {
        val options = listOf(PreviewGeometry.Size(1920,1080), PreviewGeometry.Size(1440,1080), PreviewGeometry.Size(4000,3000), PreviewGeometry.Size(640,480))
        assertEquals(PreviewGeometry.Size(1440,1080), PreviewGeometry.choose(options,4032,3024))
        assertEquals(PreviewGeometry.Size(1920,1080), PreviewGeometry.choose(options,3840,2160))
        assertNull(PreviewGeometry.choose(emptyList(),4032,3024))
        assertEquals(options[2], PreviewGeometry.choose(listOf(options[2]),4032,3024))
    }
}
