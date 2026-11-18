package com.uniformitycomp.opencapture

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DiagnosticWriterTest {
    @Test fun slowStorageDoesNotBlockProducerAndPendingQueueIsBounded() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val saved = mutableListOf<JSONObject>()
        val writer = DiagnosticWriter { snapshot ->
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            saved.add(snapshot)
        }
        try {
            writer.offer(JSONObject().put("request", JSONObject().put("id", "first")))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            repeat(50) { index ->
                writer.offer(JSONObject().put("request", JSONObject().put("id", "$index")))
            }
            repeat(20) { index ->
                writer.offer(JSONObject().put("request", JSONObject().put("id", "last")).put("sequence", index))
            }
            assertFalse(writer.flush(10))
            release.countDown()
            assertTrue(writer.flush())
            assertTrue(saved.size <= PersistentDiagnostics.MAX_RECORDS + 1)
            assertEquals(19, saved.last().getInt("sequence"))
        } finally { release.countDown(); writer.close() }
    }
}
