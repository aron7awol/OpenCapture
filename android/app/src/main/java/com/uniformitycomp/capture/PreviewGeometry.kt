package com.uniformitycomp.capture

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Geometry only: no camera pixels are cropped or changed. */
object PreviewGeometry {
    data class Size(val width: Int, val height: Int)

    fun thumbnailSize(width: Int, height: Int, maxEdge: Int = 640): Size {
        require(minOf(width, height, maxEdge) > 0)
        val scale = minOf(1.0, maxEdge.toDouble() / maxOf(width, height))
        return Size(kotlin.math.round(width * scale).toInt().coerceAtLeast(1),
            kotlin.math.round(height * scale).toInt().coerceAtLeast(1))
    }

    /** Rebase the view's transform for a surface copy stretched to thumbnail bounds. */
    fun thumbnailTransform(transform: FloatArray, viewWidth: Int, viewHeight: Int,
                           bitmapWidth: Int, bitmapHeight: Int): FloatArray {
        require(transform.size == 9 && transform.all { it.isFinite() })
        require(minOf(viewWidth, viewHeight, bitmapWidth, bitmapHeight) > 0)
        val sx = bitmapWidth.toFloat() / viewWidth
        val sy = bitmapHeight.toFloat() / viewHeight
        return floatArrayOf(
            transform[0], transform[1] * sx / sy, transform[2] * sx,
            transform[3] * sy / sx, transform[4], transform[5] * sy,
            transform[6] / sx, transform[7] / sy, transform[8])
    }

    fun choose(sizes: List<Size>, photoWidth: Int, photoHeight: Int): Size? {
        val bounded = sizes.filter { it.width <= 1920 && it.height <= 1080 }.ifEmpty { sizes }
        val ratio = photoWidth.toDouble() / photoHeight.coerceAtLeast(1)
        val nearest = bounded.minOfOrNull { abs(it.width.toDouble() / it.height - ratio) } ?: return null
        return bounded.filter { abs(it.width.toDouble() / it.height - ratio) <= nearest + 0.01 }
            .maxByOrNull { it.width.toLong() * it.height }
    }

    /** Camera2 already applies sensor orientation inside TextureView; only compensate display rotation. */
    fun textureFit(bufferWidth: Int, bufferHeight: Int, viewWidth: Int, viewHeight: Int,
                   sensorOrientation: Int, displayRotation: Int): FloatArray {
        require(sensorOrientation in listOf(0, 90, 180, 270))
        require(displayRotation in listOf(0, 90, 180, 270))
        val swapped = sensorOrientation % 180 != 0
        return fit(if (swapped) bufferHeight else bufferWidth, if (swapped) bufferWidth else bufferHeight,
            viewWidth, viewHeight, (360 - displayRotation) % 360)
    }

    /** Fit an already-oriented buffer stretched to view bounds, without cropping or changing its aspect. */
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
