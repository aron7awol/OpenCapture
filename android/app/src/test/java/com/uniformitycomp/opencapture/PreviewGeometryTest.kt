package com.uniformitycomp.opencapture

import com.uniformitycomp.capture.PreviewGeometry
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.hypot

class PreviewGeometryTest {
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
