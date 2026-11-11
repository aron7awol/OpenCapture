package com.uniformitycomp.capture

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Geometry only: no camera pixels are cropped or changed. */
object PreviewGeometry {
    data class Size(val width: Int, val height: Int)

    fun choose(sizes: List<Size>, photoWidth: Int, photoHeight: Int): Size? {
        val bounded = sizes.filter { it.width <= 1920 && it.height <= 1080 }.ifEmpty { sizes }
        val ratio = photoWidth.toDouble() / photoHeight.coerceAtLeast(1)
        val nearest = bounded.minOfOrNull { abs(it.width.toDouble() / it.height - ratio) } ?: return null
        return bounded.filter { abs(it.width.toDouble() / it.height - ratio) <= nearest + 0.01 }
            .maxByOrNull { it.width.toLong() * it.height }
    }

    /** TextureView starts with its buffer stretched to view bounds; undo that before fitting. */
    fun fit(bufferWidth: Int, bufferHeight: Int, viewWidth: Int, viewHeight: Int, rotation: Int): FloatArray {
        require(minOf(bufferWidth, bufferHeight, viewWidth, viewHeight) > 0)
        require(rotation in listOf(0, 90, 180, 270))
        val angle = Math.toRadians(rotation.toDouble())
        val c = cos(angle); val s = sin(angle)
        val bw = bufferWidth.toDouble(); val bh = bufferHeight.toDouble()
        val vw = viewWidth.toDouble(); val vh = viewHeight.toDouble()
        val rw = if (rotation % 180 == 0) bw else bh
        val rh = if (rotation % 180 == 0) bh else bw
        val scale = minOf(vw / rw, vh / rh)
        return floatArrayOf(
            (c * scale * bw / vw).toFloat(), (-s * scale * bh / vh).toFloat(), (vw / 2 - c * scale * bw / 2 + s * scale * bh / 2).toFloat(),
            (s * scale * bw / vw).toFloat(), (c * scale * bh / vh).toFloat(), (vh / 2 - s * scale * bw / 2 - c * scale * bh / 2).toFloat(),
            0f, 0f, 1f)
    }
}
