// SPDX-License-Identifier: GPL-3.0-only
package com.uniformitycomp.opencapture

import kotlin.math.abs

/** Bounded position search using measured diopters, never a guessed unit conversion. */
internal class SamsungFocusSearch(val minimum: Int, val maximum: Int) {
    companion object {
        const val MAX_PROBES = 10
        fun validRange(values: IntArray?): Boolean = values != null && values.size == 2 &&
            values[0] >= 0 && values[1] > values[0] && values[1] <= 1_000_000
    }
    init { require(validRange(intArrayOf(minimum, maximum))) }
    var position: Int? = null
        private set
    var probes = 0
        private set
    private val readings = sortedMapOf<Int, Float>()

    fun seed(value: Int?) {
        if (value != null && value in minimum..maximum) position = value
    }

    fun next(actual: Float, target: Float): Int? {
        if (!actual.isFinite() || actual < 0 || !target.isFinite() || target < 0 || probes >= MAX_PROBES) return null
        position?.let { readings[it] = actual }
        val candidate = when {
            minimum !in readings -> minimum
            maximum !in readings -> maximum
            else -> readings.entries.zipWithNext().firstOrNull { (a, b) ->
                b.key - a.key > 1 && a.value != b.value &&
                    target >= minOf(a.value, b.value) && target <= maxOf(a.value, b.value)
            }?.let { (a, b) -> a.key + (b.key - a.key) / 2 }
        } ?: return null
        if (candidate == position || candidate in readings) return null
        position = candidate
        probes++
        return candidate
    }

    fun selectSeed(target: Float, actual: Float?, actualPosition: Int?, cached: Pair<Float, Int>?, tolerance: Float) {
        val remembered = cached?.takeIf { abs(it.first - target) < .00001f }?.second
        seed(remembered ?: actualPosition?.takeIf { actual != null && actual.isFinite() && abs(actual - target) <= tolerance })
    }
}
