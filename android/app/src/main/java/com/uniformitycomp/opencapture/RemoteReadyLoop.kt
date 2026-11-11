package com.uniformitycomp.opencapture

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException

class ControllerHttpException(val status: Int) : IOException(
    if (status == 401 || status == 403) "Controller rejected the connection. Check the PC address and pairing token."
    else "Controller HTTP $status${if (status in 300..399) "; redirects are disabled" else ""}")

/** Reconnects the control channel only. It never replays an interrupted capture job. */
class RemoteReadyLoop(
    private val stopped: AtomicBoolean,
    private val enabled: () -> Boolean,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) }
) {
    fun run(waiting: (Int) -> Unit, attempt: (connected: () -> Unit) -> Unit) {
        var failures = 0
        while (!stopped.get()) {
            try {
                attempt { failures = 0 }
                return
            } catch (error: Exception) {
                if (stopped.get()) return
                if (!enabled() || !retryable(error)) throw error
                val delay = delaySeconds(failures++)
                waiting(delay)
                repeat(delay * 10) {
                    if (stopped.get() || !enabled()) return
                    sleep(100)
                }
            }
        }
    }

    companion object {
        fun delaySeconds(failure: Int): Int = listOf(1, 2, 4, 8, 15)[failure.coerceIn(0, 4)]
        fun retryable(error: Exception): Boolean = when (error) {
            is ControllerHttpException -> error.status in listOf(408, 425, 429) || error.status in 500..599
            is SSLException -> false
            is IOException -> true
            else -> false
        }
    }
}
