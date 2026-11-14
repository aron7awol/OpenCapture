// SPDX-License-Identifier: GPL-3.0-only
package com.uniformitycomp.opencapture

import com.uniformitycomp.capture.DngSampleDiagnostics
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** No demosaic or integer rounding. All coordinates are native, before geometric rendering. */
internal object BayerAverage {
    const val MODE = "bayer-mean-v3"
    const val DOMAIN = "black_subtracted_dng_corrected_bayer_site_relative_signal"

    data class Layout(val rawWidth: Int, val rawHeight: Int, val area: List<Int>,
                      val black: List<Double>, val white: Double, val cfa: List<Int>,
                      val gainMaps: List<DngGainMaps.Map> = emptyList()) {
        val width get() = (area[3] - area[1]) / 2
        val height get() = (area[2] - area[0]) / 2
        fun fields(): Map<String, Any> = mapOf("rawWidth" to rawWidth, "rawHeight" to rawHeight,
            "width" to width, "height" to height, "activeArea" to area, "blackLevels" to black,
            "whiteLevel" to white, "cfa" to cfa, "domain" to DOMAIN, "gainMaps" to gainMaps.map { it.fields() })
        private val siteMaps = List(4) { site -> gainMaps.firstOrNull { it.area.take(2) == listOf(site/2,site%2) } }
        fun gain(site: Int, x: Int, y: Int) = siteMaps[site]?.gain(x,y,width*2,height*2) ?: 1f
    }

    data class Evidence(val file: File, val layout: Layout, val rms: Double, val std: Double,
                        val mean: Double, val clipped: Double, val integrity: String) {
        fun fields(): Map<String, Any> = mapOf("rms" to rms, "spatialStd" to std, "mean" to mean,
            "clippedFraction" to clipped, "integrity" to integrity, "normalization" to layout.fields())
    }

    class Selection(val target: Int, val policy: String) {
        init { require(target in 1..32 && policy in setOf("relative", "illuminated", "dark")) }
        val maxAttempts = target * 2
        val attempts = mutableListOf<Evidence>()
        fun reasons(): List<String?> {
            val credible = attempts.filter { it.integrity == "consistent_sampled_path" && it.rms >= .001 }
            fun median(values: List<Double>): Double {
                val sorted = values.sorted()
                return if (sorted.isEmpty()) 0.0 else (sorted[(sorted.size-1)/2] + sorted[sorted.size/2]) / 2
            }
            val rms = median(credible.map { it.rms })
            val std = median(credible.map { it.std })
            return attempts.map { item -> when {
                item.integrity != "consistent_sampled_path" -> "capture_integrity_not_verified"
                policy == "dark" -> null
                item.clipped == 0.0 && item.rms < .001 && item.std < .00025 &&
                    (policy == "illuminated" || (rms >= .001 && item.rms < .1*rms && item.std < .1*std)) -> "empty_signal"
                else -> null
            } }
        }
        fun accepted(): List<Int> = reasons().mapIndexedNotNull { index, reason -> if (reason == null) index else null }
        fun needsMore() = accepted().size < target && attempts.size < maxAttempts
    }

    private fun layout(r: DngSampleDiagnostics.Raster): Layout {
        val f = r.fields
        require(r.width.toLong()*r.height <= 13_000_000) { "On-device averaging supports RAW up to 13 MP" }
        require(listOf(50712, 50715, 50716).none { it in f }) { "DNG linearization/spatial black levels need desktop originals" }
        require(f[50718]?.map { it.toDouble() } in listOf(null, listOf(1.0, 1.0))) { "Unsupported DNG default scale" }
        for (tag in listOf(51008, 51022)) f[tag]?.let { values ->
            val bytes = ByteArray(values.size) { values[it].toByte() }
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            require(bytes.size >= 4) { "Truncated DNG opcode list" }
            if (b.int == 0) require(bytes.size == 4)
            else {
                require(tag == 51022 && bytes.size == 88 && b.getInt(0) == 1 && b.getInt(4) == 1 &&
                    b.getInt(8) == 0x01030000 && b.getInt(12) in setOf(1, 3) && b.getInt(16) == 68 &&
                    b.getInt(20) == 1) { "Radiometric/unsupported DNG opcodes require desktop originals" }
                val p = List(8) { b.getDouble(24 + it*8) }
                require(p.all { it.isFinite() } && p[0] > 0 && p[0] <= 16 &&
                    p.subList(1, 6).all { kotlin.math.abs(it) <= 1000 } && p.takeLast(2).all { it in 0.0..1.0 })
            }
        }
        val area = f[50829]?.map { it.toInt() } ?: listOf(0, 0, r.height, r.width)
        require(area.size == 4 && area[0] >= 0 && area[1] >= 0 && area[2] <= r.height && area[3] <= r.width &&
            area[2] > area[0] && area[3] > area[1] && (area[2]-area[0]) % 2 == 0 && (area[3]-area[1]) % 2 == 0)
        require(f[33421]?.map { it.toInt() } == listOf(2, 2)) { "Expected 2x2 Bayer CFA" }
        val nativeCfa = requireNotNull(f[33422]).map { it.toInt() }
        require(nativeCfa.size == 4 && nativeCfa.sorted() == listOf(0, 1, 1, 2))
        val cfa = List(4) { nativeCfa[((it/2+area[0])%2)*2+(it%2+area[1])%2] }
        val repeat = f[50713]?.map { it.toInt() } ?: listOf(1, 1)
        val levels = f[50714]?.map { it.toDouble() } ?: listOf(0.0)
        require((repeat == listOf(1, 1) && levels.size == 1) || (repeat == listOf(2, 2) && levels.size == 4))
        val black = List(4) { levels[if (levels.size == 1) 0 else it] }
        val white = requireNotNull(f[50717]?.singleOrNull()).toDouble()
        require(white in 1.0..65535.0 && black.all { it.isFinite() && it >= 0 && it < white })
        val maps = DngGainMaps.parse(f[51009],area[3]-area[1],area[2]-area[0])
        return Layout(r.width, r.height, area, black, white, cfa, maps)
    }

    private fun block(r: DngSampleDiagnostics.Raster, l: Layout, x: Int, y: Int, out: FloatArray): Boolean {
        var clip = false
        var luma = 0f
        for (site in 0..3) {
            val native = r.pixel(l.area[1]+2*x+site%2, l.area[0]+2*y+site/2)
            val value = ((native.toFloat()-l.black[site].toFloat())/(l.white.toFloat()-l.black[site].toFloat()))
            require(value.isFinite() && value in -1f..2f) { "Normalized RAW samples outside supported range" }
            out[site] = value * l.gain(site,2*x+site%2,2*y+site/2)
            luma += out[site] * .25f
            if (value >= .995f) clip = true
        }
        out[4] = luma
        return clip
    }

    fun inspect(file: File, width: Int, height: Int, integrity: String, check: () -> Unit): Evidence =
        DngSampleDiagnostics.read(file, width, height) { r ->
            val l = layout(r)
            var n = 0L
            var mean = 0.0
            var m2 = 0.0
            var clips = 0L
            val values = FloatArray(5)
            for (y in 0 until l.height) {
                check()
                for (x in 0 until l.width) {
                    if (block(r, l, x, y, values)) clips++
                    val v = values[4].toDouble()
                    n++
                    val delta = v-mean
                    mean += delta/n
                    m2 += delta*(v-mean)
                }
            }
            Evidence(file, l, sqrt(m2/n+mean*mean), sqrt(m2/n), mean, clips.toDouble()/n, integrity)
        }

    /** Strip-wise Welford moments, bounded independently of burst length. */
    fun write(frames: List<Evidence>, directory: File, check: () -> Unit) {
        require(frames.size in 1..32)
        val l = frames.first().layout
        require(frames.all { it.layout == l }) { "DNG normalization changed during burst" }
        val moments = File(directory, "average.f32")
        val clipping = File(directory, "average-clips.u8")
        moments.outputStream().buffered().use { output -> clipping.outputStream().buffered().use { clipped ->
            for (start in 0 until l.height step 32) {
                check()
                val rows = minOf(32, l.height-start)
                val pixels = rows*l.width
                val means = DoubleArray(pixels*5)
                val m2 = DoubleArray(pixels)
                val clips = ByteArray(pixels)
                frames.forEachIndexed { frameIndex, frame ->
                    DngSampleDiagnostics.read(frame.file, l.rawWidth, l.rawHeight) { r ->
                        require(layout(r) == l)
                        val values = FloatArray(5)
                        for (y in 0 until rows) {
                            check()
                            for (x in 0 until l.width) {
                                val i = y*l.width+x
                                if (block(r, l, x, start+y, values)) clips[i] = (clips[i]+1).toByte()
                                for (site in 0..4) {
                                    val delta = values[site]-means[i*5+site]
                                    means[i*5+site] += delta/(frameIndex+1)
                                    if (site == 4) m2[i] += delta*(values[site]-means[i*5+site])
                                }
                            }
                        }
                    }
                }
                val bytes = ByteBuffer.allocate(pixels*24).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until pixels) {
                    for (site in 0..4) bytes.putFloat(means[i*5+site].toFloat())
                    bytes.putFloat((m2[i]/maxOf(1, frames.size-1)).toFloat())
                }
                output.write(bytes.array())
                clipped.write(clips)
            }
        } }
    }
}
