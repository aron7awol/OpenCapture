// SPDX-License-Identifier: GPL-3.0-only
package com.uniformitycomp.capture

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.sqrt

/** Sparse native samples only. No demosaic, normalization, frame selection or buffer mutation. */
internal object RawBufferDiagnostics {
    const val POLICY = "raw16-sparse-bayer-blocks-v1"

    fun sample(buffer: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int): Map<String, Any> {
        require(width >= 2 && height >= 2 && pixelStride >= 2 && rowStride >= width.toLong() * pixelStride)
        val data = buffer.duplicate().order(ByteOrder.nativeOrder())
        require((height - 1L) * rowStride + (width - 1L) * pixelStride + 2 <= data.limit())
        return samples(width, height) { x, y ->
            data.getShort(y * rowStride + x * pixelStride).toInt() and 65535
        } + mapOf("rowStrideBytes" to rowStride, "pixelStrideBytes" to pixelStride,
            "bufferLimitBytes" to data.limit(), "byteOrder" to ByteOrder.nativeOrder().toString())
    }

    private class Moments {
        var count = 0
        var mean = 0.0
        var m2 = 0.0
        var minimum = 65535
        var maximum = 0
        var zeros = 0
        fun add(value: Int) {
            count++
            val delta = value - mean
            mean += delta / count
            m2 += delta * (value - mean)
            minimum = minOf(minimum, value)
            maximum = maxOf(maximum, value)
            if (value == 0) zeros++
        }
        fun fields(): Map<String, Any> = mapOf("sampleCount" to count, "minimum" to minimum,
            "maximum" to maximum, "mean" to mean, "spatialStd" to sqrt(m2 / count),
            "zeroSampleCount" to zeros)
    }

    fun samples(width: Int, height: Int, read: (Int, Int) -> Int): Map<String, Any> {
        require(width in 2..16384 && height in 2..16384 && width.toLong() * height <= 64_000_000)
        val nx = minOf(96, width / 2)
        val ny = minOf(64, height / 2)
        val digest = MessageDigest.getInstance("SHA-256")
        val all = Moments()
        val sites = Array(4) { Moments() }
        for (gy in 0 until ny) for (gx in 0 until nx) {
            val x = (gx.toLong() * (width / 2 - 1) / (nx - 1).coerceAtLeast(1)).toInt() * 2
            val y = (gy.toLong() * (height / 2 - 1) / (ny - 1).coerceAtLeast(1)).toInt() * 2
            for (dy in 0..1) for (dx in 0..1) {
                val value = read(x + dx, y + dy)
                require(value in 0..65535)
                all.add(value)
                sites[dy * 2 + dx].add(value)
                digest.update(value.toByte())
                digest.update((value ushr 8).toByte())
            }
        }
        return all.fields() + mapOf("policy" to POLICY, "gridColumns" to nx, "gridRows" to ny,
            "width" to width, "height" to height, "siteOrder" to "top-left,top-right,bottom-left,bottom-right",
            "sites" to sites.map { it.fields() }, "flatSamples" to (all.minimum == all.maximum),
            "sampleSha256" to digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
    }

    fun compare(acquired: Map<String, Any>, before: Map<String, Any>, after: Map<String, Any>,
                saved: Map<String, Any>): Map<String, Any> {
        fun same(a: Map<String, Any>, b: Map<String, Any>): String {
            val keys = listOf("policy", "width", "height", "sampleCount", "gridColumns", "gridRows")
            val hashA = a["sampleSha256"] as? String
            val hashB = b["sampleSha256"] as? String
            if (hashA == null || hashB == null || keys.any { a[it] == null || b[it] == null }) return "unavailable"
            if (keys.any { a[it] != b[it] }) return "incompatible"
            return if (hashA == hashB) "match" else "mismatch"
        }
        val checks = linkedMapOf("acquisitionToBeforeWrite" to same(acquired, before),
            "beforeToAfterWrite" to same(before, after), "beforeWriteToSavedDng" to same(before, saved))
        val findings = mutableListOf<String>()
        if (checks["acquisitionToBeforeWrite"] == "mismatch") findings.add("sampled_buffer_changed_before_dng_write")
        if (checks["beforeToAfterWrite"] == "mismatch") findings.add("sampled_buffer_changed_during_dng_write")
        if (checks["beforeWriteToSavedDng"] == "mismatch") findings.add("saved_dng_samples_differ_from_prewrite_buffer")
        if (acquired["flatSamples"] == true) findings.add("flat_samples_already_present_at_acquisition")
        return mapOf("policy" to "raw-capture-checkpoints-v2", "checks" to checks,
            "assessment" to when {
                checks.values.any { it == "mismatch" } -> "sample_mismatch_observed"
                checks.values.all { it == "match" } -> "consistent_sampled_path"
                else -> "incomplete_diagnostics"
            }, "findings" to findings, "sampledPixelsOnly" to true,
            "scope" to "Diagnostic observations, not a root-cause verdict or frame acceptance. Dark scenes can be valid.")
    }
}

/** Read back DngCreator's uncompressed RAW16 strips without decoding a preview or retaining an image. */
internal object DngSampleDiagnostics {
    private val tags = setOf(254, 256, 257, 258, 259, 262, 273, 274, 277, 278, 279, 284, 330, 339,
        33421, 33422, 50706, 50712, 50713, 50714, 50715, 50716, 50717, 50718, 50829,
        51008, 51009, 51022)
    private const val MAX_BYTES = 128L * 1024 * 1024

    fun interface PixelReader { fun get(x: Int, y: Int): Int }
    class Raster(val width: Int, val height: Int, val fields: Map<Int, List<Number>>,
                 val order: ByteOrder, val strips: Int, val rows: Long,
                 private val reader: PixelReader) {
        // Primitive arguments avoid allocating boxed integers for every full-frame sample.
        fun pixel(x: Int, y: Int): Int = reader.get(x, y)
    }

    fun <T> read(file: File, width: Int, height: Int, consume: (Raster) -> T): T = RandomAccessFile(file, "r").use { stream ->
        val size = stream.length()
        require(size in 8..MAX_BYTES) { "DNG length is outside diagnostic bounds" }
        fun read(offset: Long, count: Int): ByteArray {
            require(count >= 0 && offset >= 0 && offset <= size - count) { "DNG field outside file" }
            stream.seek(offset)
            return ByteArray(count).also { stream.readFully(it) }
        }
        val header = read(0, 8)
        val order = when {
            header[0] == 73.toByte() && header[1] == 73.toByte() -> ByteOrder.LITTLE_ENDIAN
            header[0] == 77.toByte() && header[1] == 77.toByte() -> ByteOrder.BIG_ENDIAN
            else -> error("Not TIFF/DNG")
        }
        fun view(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(order)
        fun uint(buffer: ByteBuffer, offset: Int) = buffer.getInt(offset).toLong() and 0xffffffffL
        require((view(header).getShort(2).toInt() and 65535) == 42) { "Only classic TIFF is sampled" }
        val pending = java.util.ArrayDeque<Long>()
        pending.add(uint(view(header), 4))
        val seen = mutableSetOf<Long>()
        val raw = mutableListOf<Map<Int, List<Number>>>()
        var dng = false
        var metadataItems = 0L
        var metadataBytes = 0L
        while (pending.isNotEmpty()) {
            val offset = pending.removeLast()
            if (offset == 0L) continue
            require(seen.size < 32 && seen.add(offset)) { "Cyclic/excessive DNG directories" }
            val count = view(read(offset, 2)).short.toInt() and 65535
            require(count <= 1024) { "Excessive DNG entries" }
            val entries = view(read(offset + 2, count * 12 + 4))
            val fields = mutableMapOf<Int, List<Number>>()
            repeat(count) { index ->
                val start = index * 12
                val tag = entries.getShort(start).toInt() and 65535
                if (tag in tags) {
                    require(tag !in fields) { "Duplicate DNG field" }
                    val type = entries.getShort(start + 2).toInt() and 65535
                    val length = uint(entries, start + 4)
                    val unit = when (type) { 1, 7 -> 1; 3 -> 2; 4, 13 -> 4; 5 -> 8; else -> error("Unsupported diagnostic TIFF field type") }
                    require(type != 5 || tag in setOf(50714, 50715, 50716, 50718)) { "Unexpected rational TIFF field" }
                    require(tag !in setOf(51008, 51009, 51022) || type == 7) { "Invalid opcode field type" }
                    require(length in 1..65536 && length * unit <= 262144) { "DNG field is too large" }
                    metadataItems += length
                    metadataBytes += length * unit
                    require(metadataItems <= 65536 && metadataBytes <= 524288) { "Excessive total diagnostic metadata" }
                    val bytes = (length * unit).toInt()
                    val data = if (bytes <= 4) {
                        view(ByteArray(bytes) { entries.get(start + 8 + it) })
                    } else view(read(uint(entries, start + 8), bytes))
                    fields[tag] = List(length.toInt()) { item -> when (unit) {
                        1 -> (data.get(item).toInt() and 255).toLong()
                        2 -> (data.getShort(item * 2).toInt() and 65535).toLong()
                        8 -> {
                            val denominator = uint(data, item * 8 + 4)
                            require(denominator != 0L) { "Invalid DNG black-level rational" }
                            uint(data, item * 8).toDouble() / denominator
                        }
                        else -> uint(data, item * 4)
                    } }
                }
            }
            fields[50706]?.let { require(it.size == 4 && it.first() == 1L) { "Unsupported DNG version" }; dng = true }
            if (fields[262] == listOf(32803L) && fields.getOrDefault(254, listOf(0L)) == listOf(0L)) raw.add(fields)
            fields[330]?.forEach { require(pending.size < 64) { "Excessive DNG SubIFDs" }; pending.add(it.toLong()) }
            pending.add(uint(entries, count * 12))
        }
        require(dng && raw.size == 1) { "Expected one RAW DNG directory" }
        val fields = raw.single()
        fun scalar(tag: Int, default: Long? = null): Long =
            ((fields[tag] ?: default?.let { listOf(it) })?.singleOrNull() as? Long)
                ?: error("Missing/invalid TIFF field $tag")
        require(scalar(256) == width.toLong() && scalar(257) == height.toLong()) { "RAW DNG dimensions differ from image buffer" }
        require(scalar(258) == 16L && scalar(259, 1) == 1L && scalar(277, 1) == 1L &&
            scalar(284, 1) == 1L && scalar(339, 1) == 1L) { "Only uncompressed unsigned RAW16 strips are sampled" }
        require(width in 2..16384 && height in 2..16384 && width.toLong() * height <= 64_000_000)
        val rows = scalar(278, height.toLong())
        require(rows in 1..0xffffffffL) { "Invalid rows per strip" }
        val offsets = fields[273]?.map { it.toLong() } ?: error("DNG strip offsets unavailable")
        val lengths = fields[279]?.map { it.toLong() } ?: error("DNG strip lengths unavailable")
        val strips = ((height + rows - 1) / rows).toInt()
        require(offsets.size == strips && lengths.size == strips) { "DNG strip count mismatch" }
        repeat(strips) { strip ->
            val needed = minOf(rows, height - strip * rows) * width * 2
            require(lengths[strip] >= needed && offsets[strip] >= 8 && offsets[strip] <= size - lengths[strip]) {
                "DNG strip outside file or truncated"
            }
        }
        // At most 128 sampled rows are read; cache only the current Bayer row pair.
        val cached = arrayOfNulls<ByteBuffer>(2)
        val cachedY = intArrayOf(-1, -1)
        consume(Raster(width, height, fields, order, strips, rows) { x, y ->
            require(x in 0 until width && y in 0 until height)
            val slot = y % 2
            if (cachedY[slot] != y) {
                val strip = (y / rows).toInt()
                cached[slot] = view(read(offsets[strip] + (y % rows) * width * 2, width * 2))
                cachedY[slot] = y
            }
            cached[slot]!!.getShort(x * 2).toInt() and 65535
        })
    }

    fun sample(file: File, width: Int, height: Int): Map<String, Any> = read(file, width, height) { raster ->
        val metadata = linkedMapOf<String, Any>()
        for ((tag, name) in mapOf(274 to "orientation", 33421 to "cfaRepeatDimensions", 33422 to "cfaPattern",
            50713 to "blackLevelRepeatDimensions", 50714 to "blackLevels", 50717 to "whiteLevels", 50829 to "activeArea")) {
            raster.fields[tag]?.let { metadata[name] = it }
        }
        RawBufferDiagnostics.samples(width, height, raster::pixel) + mapOf("storage" to "uncompressed-tiff-strips", "fileByteOrder" to raster.order.toString(),
            "stripCount" to raster.strips, "rowsPerStrip" to raster.rows, "dngMetadata" to metadata)
    }
}
