package com.uniformitycomp.opencapture

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLHandshakeException

class RemoteReadyLoopTest {
    @Test fun reconnectsWithBoundedBackoffAndResetsAfterRegistration() {
        val stop = AtomicBoolean(false)
        val delays = mutableListOf<Int>()
        var calls = 0
        RemoteReadyLoop(stop, { true }, {}).run({ delays.add(it) }) { connected ->
            calls++
            if (calls == 7) connected()
            if (calls == 8) stop.set(true) else throw IOException("offline")
        }
        assertEquals(listOf(1, 2, 4, 8, 15, 15, 1), delays)
        assertEquals(8, calls)
    }
    @Test fun stoppingOrBackgroundingCancelsPendingReconnect() {
        val stop = AtomicBoolean(false)
        var calls = 0
        RemoteReadyLoop(stop, { true }, { stop.set(true) }).run({}) {
            calls++; throw IOException("offline")
        }
        assertEquals(1, calls)
        RemoteReadyLoop(stop, { true }, {}).run({ fail("No timer after stop") }) { fail("No connection after stop") }
    }
    @Test fun manualConnectionDoesNotRetryAndProtocolFailuresRequireAttention() {
        for (failure in listOf(ControllerHttpException(401), ControllerHttpException(403), ControllerHttpException(302),
                IllegalArgumentException("bad protocol"), SSLHandshakeException("untrusted certificate"))) {
            var calls = 0
            try {
                RemoteReadyLoop(AtomicBoolean(false), { true }, {}).run({ fail("Must not retry") }) { calls++; throw failure }
                fail("Expected rejection")
            } catch (actual: Exception) { assertSame(failure, actual) }
            assertEquals(1, calls)
        }
        try {
            RemoteReadyLoop(AtomicBoolean(false), { false }, {}).run({ fail("Manual mode must not retry") }) { throw IOException("offline") }
            fail("Expected connection failure")
        } catch (_: IOException) { }
        assertTrue(RemoteReadyLoop.retryable(ControllerHttpException(503)))
        assertTrue(RemoteReadyLoop.retryable(ControllerHttpException(429)))
    }
    @Test fun cancelledActiveAttemptNeverSchedulesAnotherConnection() {
        val stop = AtomicBoolean(false)
        RemoteReadyLoop(stop, { true }, {}).run({ fail("No retry on cancellation") }) {
            stop.set(true); throw IOException("socket closed by background lifecycle")
        }
    }
}
