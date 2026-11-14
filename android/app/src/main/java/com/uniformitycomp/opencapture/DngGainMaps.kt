// SPDX-License-Identifier: GPL-3.0-only
package com.uniformitycomp.opencapture

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** DNG 1.3 opcode 9, bounded to DngCreator's four non-overlapping Bayer maps.
 * Coordinates are relative to ActiveArea (stage 2), regardless of TIFF margins.
 * This product includes DNG technology under license by Adobe.
 */
internal object DngGainMaps {
    data class Map(val flags: Int, val area: List<Int>, val points: List<Int>,
                   val spacing: List<Double>, val gains: List<Double>) {
        fun fields(): kotlin.collections.Map<String, Any> = mapOf("id" to 9, "version" to "1.3.0.0",
            "flags" to flags, "area" to area, "plane" to 0, "planes" to 1, "pitch" to listOf(2,2),
            "points" to points, "spacing" to spacing, "origin" to listOf(0.0,0.0), "mapPlanes" to 1, "gains" to gains)

        fun gain(x: Int, y: Int, width: Int, height: Int): Float {
            // DNG uses normalized pixel centers, distinct from Camera2's grid
            // endpoint convention. Match desktop double interpolation then f32.
            val gy = (((y+.5)/height)/spacing[0]).coerceIn(0.0,points[0]-1.0)
            val gx = (((x+.5)/width)/spacing[1]).coerceIn(0.0,points[1]-1.0)
            val iy = gy.toInt(); val ix = gx.toInt()
            val fy = gy-iy; val fx = gx-ix
            val ny = minOf(iy+1,points[0]-1); val nx = minOf(ix+1,points[1]-1)
            val upper = gains[iy*points[1]+ix]*(1-fx)+gains[iy*points[1]+nx]*fx
            val lower = gains[ny*points[1]+ix]*(1-fx)+gains[ny*points[1]+nx]*fx
            return (upper*(1-fy)+lower*fy).toFloat()
        }
    }

    fun parse(values: List<Number>?, width: Int, height: Int): List<Map> {
        if (values == null) return emptyList()
        require(values.size in 4..131072) { "Invalid DNG GainMap list size" }
        val b = ByteBuffer.wrap(ByteArray(values.size) { values[it].toByte() }).order(ByteOrder.BIG_ENDIAN)
        val count = b.int
        require(count == 0 || count == 4) { "Expected four Bayer GainMaps" }
        val result = List(count) {
            require(b.remaining() >= 16) { "Truncated DNG opcode header" }
            val id = b.int; val version = b.int; val flags = b.int; val size = b.int
            require(id == 9 && version == 0x01030000 && flags in 0..3 && size in 76..b.remaining()) {
                "Unsupported DNG opcode in GainMap list"
            }
            val area = List(4) { b.int }
            require(area[0] in 0..1 && area[1] in 0..1 && area[2] == height && area[3] == width)
            require(b.int == 0 && b.int == 1 && b.int == 2 && b.int == 2) { "Unsupported GainMap planes/pitch" }
            val points = List(2) { b.int }
            require(points.all { it in 1..64 } && size == 76+points[0]*points[1]*4) { "Invalid GainMap grid size" }
            val spacing = List(2) { b.double }
            require(spacing.indices.all { spacing[it].isFinite() &&
                kotlin.math.abs(spacing[it]-1.0/maxOf(1,points[it]-1)) <= 1e-12 })
            require(b.double == 0.0 && b.double == 0.0 && b.int == 1) { "Unsupported GainMap origin/planes" }
            val gains = List(points[0]*points[1]) { b.float.toDouble() }
            require(gains.all { it.isFinite() && it in 1.0..16.0 }) { "DNG gain outside [1,16]" }
            Map(flags,area,points,spacing,gains)
        }
        require(!b.hasRemaining()) { "Trailing DNG GainMap bytes" }
        require(result.isEmpty() || result.map { it.area.take(2) }.toSet() ==
            setOf(listOf(0,0),listOf(0,1),listOf(1,0),listOf(1,1))) { "Overlapping/missing Bayer GainMaps" }
        return result
    }

    /** Compare actual Camera2 gains with the embedded maps before averaging. */
    fun verify(maps: List<Map>, rows: Int, columns: Int, gains: List<Double>, cfa: List<Int>) {
        require(rows in 1..64 && columns in 1..64 && gains.size == rows*columns*4 &&
            gains.all { it.isFinite() && it in 1.0..16.0 }) { "Invalid actual Camera2 shading map" }
        require(maps.size == 4 && cfa.size == 4)
        for (map in maps) {
            val y = map.area[0]; val x = map.area[1]
            val channel = when(cfa[y*2+x]) { 0 -> 0; 2 -> 3; 1 -> if(y == 0) 1 else 2; else -> error("Invalid CFA") }
            require(map.points == listOf(rows,columns) && map.gains == List(rows*columns) { gains[it*4+channel] }) {
                "Camera2 lens shading map disagrees with original DNG"
            }
        }
    }
}
