// SPDX-License-Identifier: GPL-3.0-only
package com.uniformitycomp.opencapture

import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** One CPU frame may overlap the next exposure; callers own ordered publication. */
internal class FrameProcessor<T> : Closeable {
    private val executor = Executors.newSingleThreadExecutor { work -> Thread(work,"OpenCaptureInspect") }
    private var pending: Future<T>? = null
    val hasPending get() = pending != null
    val isDone get() = pending?.isDone == true
    fun submit(work: () -> T) {
        check(pending == null) { "Inspection queue is full" }
        pending = executor.submit<T> { work() }
    }
    fun take(): T? {
        val task = pending ?: return null
        try { return task.get() }
        finally { pending = null }
    }
    override fun close() {
        pending?.cancel(true)
        executor.shutdownNow()
        var interrupted = Thread.interrupted()
        // Archive cleanup must not race a still-running file reader.
        while (!executor.isTerminated) {
            try { executor.awaitTermination(1,TimeUnit.SECONDS) }
            catch (_: InterruptedException) { interrupted = true }
        }
        pending = null
        if (interrupted) Thread.currentThread().interrupt()
    }
}
