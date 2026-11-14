package com.uniformitycomp.opencapture

import com.uniformitycomp.capture.DngSampleDiagnostics
import com.uniformitycomp.capture.RawBufferDiagnostics
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class DngSampleDiagnosticsTest {
    internal data class Fixture(val bytes: ByteArray, val rawIfd: Int, val order: ByteOrder) {
        fun entry(tag: Int): Int {
            val data = ByteBuffer.wrap(bytes).order(order)
            val count = data.getShort(rawIfd).toInt() and 65535
            return (0 until count).map { rawIfd + 2 + it * 12 }
                .first { (data.getShort(it).toInt() and 65535) == tag }
        }
    }

    internal fun fixture(width: Int = 8, height: Int = 6, rows: Int = 2,
                        order: ByteOrder = ByteOrder.LITTLE_ENDIAN, subIfd: Boolean = false,
                        activeArea: List<Int> = listOf(0,0,height,width), opcode2: ByteArray? = null,
                        value: (Int, Int) -> Int = { x, y -> 256 + y * 8 + x }): Fixture {
        val data = ByteBuffer.allocate(16384).order(order)
        data.put(0, if (order == ByteOrder.LITTLE_ENDIAN) 73.toByte() else 77.toByte())
        data.put(1, data.get(0)); data.putShort(2, 42); data.putInt(4, 8)
        var extras = 512
        fun directory(offset: Int, fields: List<Triple<Int, Int, List<Int>>>) {
            data.putShort(offset, fields.size.toShort())
            fields.sortedBy { it.first }.forEachIndexed { index, (tag, type, values) ->
                val start = offset + 2 + index * 12
                val unit = when (type) { 1,7 -> 1; 3 -> 2; 5 -> 8; else -> 4 }
                val count = if (type == 5) values.size / 2 else values.size
                data.putShort(start, tag.toShort()); data.putShort(start + 2, type.toShort())
                data.putInt(start + 4, count)
                val destination = if (count * unit <= 4) start + 8 else extras.also {
                    data.putInt(start + 8, it); extras += count * unit
                }
                values.forEachIndexed { n, number -> when (unit) {
                    1 -> data.put(destination + n, number.toByte())
                    2 -> data.putShort(destination + n * 2, number.toShort())
                    else -> data.putInt(destination + n * 4, number)
                } }
            }
            data.putInt(offset + 2 + fields.size * 12, 0)
        }
        val strips = (height + rows - 1) / rows
        val offsets = (0 until strips).map { 2048 + it * (rows * width * 2 + 16) }
        val counts = (0 until strips).map { minOf(rows, height - it * rows) * width * 2 }
        val rawIfd = if (subIfd) 128 else 8
        if (subIfd) directory(8, listOf(Triple(50706, 1, listOf(1, 4, 0, 0)), Triple(330, 4, listOf(rawIfd))))
        directory(rawIfd, listOf(Triple(254, 4, listOf(0)), Triple(256, 4, listOf(width)),
            Triple(257, 4, listOf(height)), Triple(258, 3, listOf(16)), Triple(259, 3, listOf(1)),
            Triple(262, 3, listOf(32803)), Triple(273, 4, offsets), Triple(277, 3, listOf(1)),
            Triple(278, 4, listOf(rows)), Triple(279, 4, counts), Triple(284, 3, listOf(1)),
            Triple(339, 3, listOf(1)), Triple(50706, 1, listOf(1, 4, 0, 0)),
            Triple(33421, 3, listOf(2, 2)), Triple(33422, 1, listOf(0, 1, 1, 2)),
            Triple(50713, 3, listOf(2, 2)), Triple(50714, 5, listOf(512, 2, 513, 2, 514, 2, 515, 2)),
            Triple(50717, 4, listOf(4095)), Triple(50829, 4, activeArea)) +
            (opcode2?.let { listOf(Triple(51009,7,it.map { value -> value.toInt() and 255 })) } ?: emptyList()))
        for (y in 0 until height) for (x in 0 until width) {
            data.putShort(offsets[y / rows] + ((y % rows) * width + x) * 2, value(x, y).toShort())
        }
        return Fixture(data.array().copyOf(offsets.last() + counts.last()), rawIfd, order)
    }

    private fun sample(fixture: Fixture, width: Int = 8, height: Int = 6): Map<String, Any> {
        val file = File.createTempFile("raw-diagnostic-", ".dng")
        try {
            file.writeBytes(fixture.bytes)
            return DngSampleDiagnostics.sample(file, width, height)
        } finally { file.delete() }
    }

    @Test fun readsBothByteOrdersAndNoncontiguousStrips() {
        val buffer = ByteBuffer.allocate(6 * 24).order(ByteOrder.nativeOrder())
        for (y in 0 until 6) for (x in 0 until 8) buffer.putShort(y * 24 + x * 2, (256 + y * 8 + x).toShort())
        val expected = RawBufferDiagnostics.sample(buffer, 8, 6, 24, 2)
        for (order in listOf(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
            val actual = sample(fixture(order = order))
            for (key in listOf("sampleSha256", "mean", "spatialStd", "minimum", "maximum", "sites")) {
                assertEquals(key, expected[key], actual[key])
            }
            assertEquals(3, actual["stripCount"])
        }
    }

    @Test fun readsSubIfdUnsignedSamplesAndPartialLastStrip() {
        val actual = sample(fixture(rows = 4, subIfd = true) { x, _ -> if (x % 2 == 0) 65535 else 0 })
        assertEquals(65535, actual["maximum"])
        assertEquals(0, actual["minimum"])
        assertEquals(24, actual["zeroSampleCount"])
        assertEquals(32767.5, actual["mean"] as Double, 0.0)
    }

    @Test fun recordsDngBlackAndWhiteLevelsWithoutNormalizingSamples() {
        for (order in listOf(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
            val actual = sample(fixture(order = order) { _, _ -> 256 })
            val metadata = actual["dngMetadata"] as Map<*, *>
            assertEquals(listOf(256.0, 256.5, 257.0, 257.5), metadata["blackLevels"])
            assertEquals(listOf(4095L), metadata["whiteLevels"])
            assertEquals(listOf(0L, 0L, 6L, 8L), metadata["activeArea"])
            assertEquals(256.0, actual["mean"] as Double, 0.0)
        }
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsZeroBlackLevelDenominator() {
        val f = fixture()
        val data = ByteBuffer.wrap(f.bytes).order(f.order)
        data.putInt(data.getInt(f.entry(50714) + 8) + 4, 0)
        sample(f)
    }

    @Test fun oddDimensionsUseTheSameCompleteBayerBlocks() {
        val actual = sample(fixture(width = 7, height = 5), 7, 5)
        val expected = RawBufferDiagnostics.samples(7, 5) { x, y -> 256 + y * 8 + x }
        assertEquals(expected["sampleSha256"], actual["sampleSha256"])
        assertEquals(24, actual["sampleCount"])
    }

    @Test fun samplesLargeImagesAtTheSameNativeCoordinates() {
        val expected = RawBufferDiagnostics.samples(4032, 3024) { x, y -> (x * 13 + y * 7) % 65536 }
        assertEquals(96 * 64 * 4, expected["sampleCount"])
        assertEquals(expected, RawBufferDiagnostics.samples(4032, 3024) { x, y -> (x * 13 + y * 7) % 65536 })
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsTruncatedFile() {
        val f = fixture()
        sample(f.copy(bytes = f.bytes.copyOf(f.bytes.size - 1)))
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsWrongDimensions() { sample(fixture(), 6, 6) }

    @Test(expected = IllegalArgumentException::class) fun refusesCompressedDataInsteadOfInventingAHash() {
        val f = fixture()
        ByteBuffer.wrap(f.bytes).order(f.order).putShort(f.entry(259) + 8, 7)
        sample(f)
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsOutOfBoundsStrip() {
        val f = fixture(rows = 6)
        ByteBuffer.wrap(f.bytes).order(f.order).putInt(f.entry(273) + 8, -1)
        sample(f)
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsCyclicDirectories() {
        val f = fixture(subIfd = true)
        val data = ByteBuffer.wrap(f.bytes).order(f.order)
        val count = data.getShort(f.rawIfd).toInt() and 65535
        data.putInt(f.rawIfd + 2 + count * 12, 8)
        sample(f)
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsDuplicateFields() {
        val f = fixture()
        ByteBuffer.wrap(f.bytes).order(f.order).putShort(f.entry(258), 256)
        sample(f)
    }

    @Test fun boundsAggregateMetadataBeforeAllocatingManyFields() {
        val f = fixture().let { it.copy(bytes = it.bytes.copyOf(4096 + 40000 * 4)) }
        val data = ByteBuffer.wrap(f.bytes).order(f.order)
        for (tag in listOf(273, 279)) {
            data.putInt(f.entry(tag) + 4, 40000)
            data.putInt(f.entry(tag) + 8, 4096)
        }
        try {
            sample(f)
            fail("Expected metadata allocation limit")
        } catch (error: IllegalArgumentException) {
            assertEquals("Excessive total diagnostic metadata", error.message)
        }
    }

    @Test fun flatDarkDataIsAnObservationNotARejectedFrame() {
        val flat = RawBufferDiagnostics.samples(8, 6) { _, _ -> 256 }
        val saved = sample(fixture { _, _ -> 256 })
        val result = RawBufferDiagnostics.compare(flat, flat, flat, saved)
        assertEquals("consistent_sampled_path", result["assessment"])
        assertEquals(listOf("flat_samples_already_present_at_acquisition"), result["findings"])
    }

    @Test fun localizesChangesWithoutClaimingTheHardwareCause() {
        val a = RawBufferDiagnostics.samples(8, 6) { x, y -> 256 + x + y }
        val b = RawBufferDiagnostics.samples(8, 6) { _, _ -> 256 }
        val before = RawBufferDiagnostics.compare(a, b, b, b)
        assertEquals("sample_mismatch_observed", before["assessment"])
        assertEquals(listOf("sampled_buffer_changed_before_dng_write"), before["findings"])
        val after = RawBufferDiagnostics.compare(a, a, b, a)
        assertEquals(listOf("sampled_buffer_changed_during_dng_write"), after["findings"])
        val saved = RawBufferDiagnostics.compare(a, a, a, b)
        assertEquals(listOf("saved_dng_samples_differ_from_prewrite_buffer"), saved["findings"])
    }

    @Test fun missingAndIncompatibleSamplesAreNeverReportedAsMatching() {
        val a = RawBufferDiagnostics.samples(8, 6) { x, _ -> x }
        val absent = mapOf<String, Any>("unavailable" to "Unsupported layout")
        assertEquals("incomplete_diagnostics", RawBufferDiagnostics.compare(a, a, a, absent)["assessment"])
        val incompatible = a + mapOf("width" to 9)
        assertEquals("incomplete_diagnostics", RawBufferDiagnostics.compare(a, a, a, incompatible)["assessment"])
    }
}
