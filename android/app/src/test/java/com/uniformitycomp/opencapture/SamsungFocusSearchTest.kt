// SPDX-License-Identifier: GPL-3.0-only
package com.uniformitycomp.opencapture

import org.junit.Assert.*
import org.junit.Test

class SamsungFocusSearchTest {
    @Test fun validatesRanges() {
        assertFalse(SamsungFocusSearch.validRange(null))
        assertFalse(SamsungFocusSearch.validRange(intArrayOf(0)))
        assertFalse(SamsungFocusSearch.validRange(intArrayOf(-1, 100)))
        assertFalse(SamsungFocusSearch.validRange(intArrayOf(100, 0)))
        assertFalse(SamsungFocusSearch.validRange(intArrayOf(0, 1_000_001)))
        assertTrue(SamsungFocusSearch.validRange(intArrayOf(10, 1000)))
    }
    @Test fun findsTargetInEitherDirectionWithoutAssumingPositionUnits() {
        for (reverse in listOf(false, true)) {
            val search = SamsungFocusSearch(0, 1000)
            var actual = 0f
            var matched = false
            repeat(10) {
                if (!matched) {
                    val position = requireNotNull(search.next(actual, .7f))
                    actual = (if (reverse) 1000 - position else position) / 100f
                    matched = kotlin.math.abs(actual - .7f) <= .05f
                }
            }
            assertTrue(matched)
            assertTrue(search.probes <= SamsungFocusSearch.MAX_PROBES)
        }
    }
    @Test fun ignoredOrUnreachableControlsStop() {
        val search = SamsungFocusSearch(0, 1000)
        assertEquals(0, search.next(0f, 1f))
        assertEquals(1000, search.next(0f, 1f))
        assertNull(search.next(0f, 1f))
        assertNull(SamsungFocusSearch(0, 100).next(Float.NaN, 1f))
    }
    @Test fun onlySeedsFromMatchingActualOrExactRememberedTarget() {
        val search = SamsungFocusSearch(0, 1000)
        search.selectSeed(1f, 0f, 400, null, .2f)
        assertNull(search.position)
        search.selectSeed(1f, 1f, 400, null, .2f)
        assertEquals(400, search.position)
        val remembered = SamsungFocusSearch(0, 1000)
        remembered.selectSeed(1f, 0f, 5, 1f to 350, .2f)
        assertEquals(350, remembered.position)
    }
    @Test fun boundedEvenForVeryLargeRanges() {
        val search = SamsungFocusSearch(0, 1_000_000)
        var actual = 0f
        repeat(SamsungFocusSearch.MAX_PROBES) {
            actual = requireNotNull(search.next(actual, .333333f)) / 1_000_000f
        }
        assertNull(search.next(actual, .333333f))
    }

    @Test fun vendorOnlyS20RangeConvergesAndCanReuseVerifiedPosition() {
        val search = SamsungFocusSearch(1, 1023)
        search.seed(search.minimum)
        val target = 1.4431313f
        fun reported(position: Int) = (position - 1) * 10f / 1022f
        while (kotlin.math.abs(reported(requireNotNull(search.position)) - target) > .2f) {
            assertNotNull(search.next(reported(requireNotNull(search.position)), target))
        }
        assertTrue(search.probes <= SamsungFocusSearch.MAX_PROBES)
        val next = SamsungFocusSearch(1, 1023)
        next.selectSeed(target, null, null, target to requireNotNull(search.position), .2f)
        assertEquals(search.position, next.position)
    }
}
