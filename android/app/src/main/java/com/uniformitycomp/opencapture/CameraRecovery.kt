// SPDX-License-Identifier: GPL-3.0-only
package com.uniformitycomp.opencapture

/** Main-thread lifecycle tickets also fence callbacks queued by the camera thread. */
internal class CameraRecovery {
    var generation = 0L
        private set
    var opening = false
        private set
    var pending = false
        private set
    var attempts = 0
        private set

    fun invalidate(resetAttempts: Boolean = false) {
        ++generation
        opening = false
        pending = false
        if (resetAttempts) attempts = 0
    }

    fun current(ticket: Long) = ticket == generation

    fun beginOpen(): Long? {
        if (opening || pending) return null
        opening = true
        return ++generation
    }

    fun beginRecovery(ticket: Long): Long? {
        if (!current(ticket) || pending) return null
        invalidate()
        pending = attempts < 3
        return generation
    }

    fun takeRetry(ticket: Long): Boolean {
        if (!current(ticket) || !pending) return false
        pending = false
        ++attempts
        return true
    }

    fun abandonRetry(ticket: Long) {
        if (current(ticket)) pending = false
    }
}
