// SPDX-License-Identifier: GPL-3.0-only
package com.uniformitycomp.capture

/** Budget for queued integrations, not a claim about actual sensor latency. */
internal object CaptureWaitPolicy {
    const val POLICY = "exposure-aware-deadlines-v1"
    const val MIN_SECONDS = 45L
    const val MAX_SECONDS = 600L
    fun pipelineDepth(reported: Int?): Int = reported?.takeIf { it in 1..8 } ?: 8
    fun seconds(exposureNs: Long, previewNs: Long = 0, pipelineDepth: Int? = null,
                pendingStills: Int = 1, settleMs: Long = 0): Long {
        val exposure = exposureNs.coerceIn(0, 120_000_000_000L) / 1e9
        val preview = previewNs.coerceIn(0, 120_000_000_000L) / 1e9
        return kotlin.math.ceil(15 + exposure * pendingStills.coerceIn(1, 4) +
            preview * pipelineDepth(pipelineDepth) + settleMs.coerceIn(0, 30_000) / 1000.0)
            .toLong().coerceIn(MIN_SECONDS, MAX_SECONDS)
    }
}

/** One monotonic deadline shared by all callbacks in a stage/request. */
internal class CaptureDeadline(val seconds: Long, private val startedNs: Long = System.nanoTime()) {
    init { require(seconds in 1..CaptureWaitPolicy.MAX_SECONDS) }
    fun elapsedNs(now: Long) = (now - startedNs).coerceAtLeast(0)
    fun remainingNs(now: Long) = (seconds * 1_000_000_000L - elapsedNs(now)).coerceAtLeast(0)
    fun <T> await(check: () -> Unit, poll: (Long) -> T?, now: () -> Long = System::nanoTime,
                  discard: (T) -> Unit = {}): T {
        while (true) {
            check()
            val remaining = remainingNs(now())
            if (remaining == 0L) throw java.util.concurrent.TimeoutException()
            val value = poll(minOf(remaining, 100_000_000L))
            try {
                check()
                if (remainingNs(now()) == 0L) throw java.util.concurrent.TimeoutException()
            } catch (error: Exception) {
                if (value != null) runCatching { discard(value) }
                throw error
            }
            if (value != null) return value
        }
    }
}

internal object ShortPreviewExposure {
    const val CAP_NS = 200_000_000L
    fun exposure(stillNs: Long, enabled: Boolean): Long = if (enabled) minOf(stillNs, CAP_NS) else stillNs
}

/** Stills submitted before earlier ones finish, so preview frames do not run between them. */
internal object QueuedStills {
    const val POLICY = "queued-ahead-v1"
    const val MAXIMUM = 4
    /** Submitted and not yet closed. Each may hold one RAW buffer of the reader. */
    fun window(enabled: Boolean, maxImages: Int): Int =
        if (enabled) maxImages.coerceIn(1, MAXIMUM) else 1
}

/** Immutable request identity. Omitted/automatic values never mean "keep the previous value". */
internal data class ManualRequestKey(
    val exposureNs: Long, val iso: Int, val focus: Float, val gains: List<Float>
) {
    /** Only exposure/ISO may change while retaining the established manual color state. */
    fun exposureOnlyChangeFrom(previous: ManualRequestKey): Boolean =
        focus == previous.focus && gains == previous.gains &&
            (exposureNs != previous.exposureNs || iso != previous.iso)

    companion object {
        fun from(exposure: Long?, iso: Int?, focus: Float?, gains: FloatArray?): ManualRequestKey? {
            if (exposure == null || exposure <= 0 || iso == null || iso <= 0 ||
                focus == null || !focus.isFinite() || focus !in 0f..1f ||
                gains == null || gains.size != 3 || gains.any { !it.isFinite() || it <= 0f }) return null
            return ManualRequestKey(exposure, iso, focus, gains.toList())
        }
    }
}

/** Object identity matters: a recreated session or reader cannot inherit verification. */
internal class ManualCaptureScope(val camera: Any, val session: Any, val reader: Any, val preview: Any) {
    fun difference(other: ManualCaptureScope): String? = when {
        camera !== other.camera -> "camera_changed"
        session !== other.session -> "session_changed"
        reader !== other.reader -> "reader_changed"
        preview !== other.preview -> "preview_changed"
        else -> null
    }
}

/** A capture owns a generation, not just a settings tuple. Late completion cannot resurrect a cache. */
internal class ManualCaptureCache<T> {
    class Ticket<T> internal constructor(val generation: Long, val key: ManualRequestKey?,
        val scope: ManualCaptureScope, val reused: T?, val reason: String,
        val exposureSource: T? = null)
    private data class Entry<T>(val key: ManualRequestKey, val scope: ManualCaptureScope, val value: T,
        val stillHealthy: () -> Boolean)
    private var generation = 0L
    private var entry: Entry<T>? = null
    private var reason = "no_verified_still"

    @Synchronized fun invalidate(why: String) { generation++; entry = null; reason = why }

    @Synchronized fun begin(key: ManualRequestKey?, scope: ManualCaptureScope, capture: Boolean): Ticket<T> {
        val old = entry
        val sourceHealthy = old?.stillHealthy() == true
        val scopeDifference = old?.scope?.difference(scope)
        val healthyScope = sourceHealthy && scopeDifference == null
        val fallback = when {
            !capture -> "fresh_controls_readback_required"
            key == null -> "automatic_or_partial_request"
            old == null -> reason
            !sourceHealthy -> "source_capture_cancelled"
            scopeDifference != null -> scopeDifference
            key != old.key -> "settings_changed"
            else -> "verified_identical_manual"
        }
        generation++
        entry = null // Failed work cannot leave the previous verification available.
        reason = "capture_not_verified"
        return Ticket(generation, key, scope,
            old?.value?.takeIf { fallback == "verified_identical_manual" }, fallback,
            old?.takeIf { healthyScope && key?.exposureOnlyChangeFrom(it.key) == true }?.value)
    }

    @Synchronized fun current(ticket: Ticket<T>): Boolean = ticket.generation == generation

    @Synchronized fun complete(ticket: Ticket<T>, value: T?, unavailable: String, stillHealthy: () -> Boolean = { true }): Boolean {
        if (!current(ticket)) return false
        entry = if (ticket.key != null && value != null && stillHealthy()) Entry(ticket.key, ticket.scope, value, stillHealthy) else null
        reason = unavailable
        return true
    }
}

/** Actual Camera2 values only; no images, results, shading maps or synthesized timestamps are cached. */
internal data class ManualStateReading(
    val exposureNs: Long?, val iso: Int?, val focus: Float?, val gains: List<Float>?,
    val transform: List<Double>?, val crop: List<Int>?, val modes: List<Int?>,
    val opticalStabilization: Int?, val videoStabilization: Int?,
    val shadingMode: Int?, val shadingMapMode: Int?, val physicalCamera: String?, val lensMoving: Boolean = false
) {
    /** Same acceptance bounds as manual locking, with every failed field retained. */
    fun controlChecks(expectedExposure: Long, expectedISO: Int, expectedFocus: Float,
                      expectedGains: List<Float>, maximumFocus: Float): List<Map<String, Any>> {
        val checks = mutableListOf<Map<String, Any>>()
        fun add(field: String, expected: Any, actual: Any?, tolerance: Number, matched: Boolean) {
            checks.add(mapOf("field" to field, "expected" to expected,
                "actual" to (actual ?: "missing"), "tolerance" to tolerance,
                "status" to if (actual == null) "missing" else if (matched) "matched" else "mismatch"))
        }
        val exposureTolerance = maxOf(10_000L, (expectedExposure * .03).toLong())
        val isoTolerance = maxOf(1.0, expectedISO * .05)
        val focusTolerance = maxOf(.0001f, maximumFocus * .02f)
        add("exposureNs", expectedExposure, exposureNs, exposureTolerance,
            exposureNs != null && kotlin.math.abs(exposureNs - expectedExposure) <= exposureTolerance)
        add("iso", expectedISO, iso, isoTolerance, iso != null && kotlin.math.abs(iso - expectedISO) <= isoTolerance)
        add("focusDiopters", expectedFocus, focus, focusTolerance,
            focus != null && kotlin.math.abs(focus - expectedFocus) <= focusTolerance)
        listOf("controlMode", "aeMode", "afMode", "awbMode").forEachIndexed { index, field ->
            val actual = modes.getOrNull(index)
            add(field, 0, actual, 0, actual == 0)
        }
        listOf("red", "greenEven", "greenOdd", "blue").forEachIndexed { index, channel ->
            val actual = gains?.getOrNull(index)
            val expected = expectedGains[index]
            add("whiteBalance.$channel", expected, actual, .1f,
                actual != null && kotlin.math.abs(actual - expected) <= .1f)
        }
        return checks
    }

    fun diagnosticFields(): Map<String, Any> = mapOf(
        "exposureNs" to (exposureNs ?: "missing"), "iso" to (iso ?: "missing"),
        "focusDiopters" to (focus ?: "missing"), "whiteBalanceGains" to (gains ?: "missing"),
        "colorTransformMatrix" to (transform ?: "missing"), "crop" to (crop ?: "missing"),
        "controlModes" to modes.map { it ?: "missing" },
        "opticalStabilization" to (opticalStabilization ?: "missing"),
        "videoStabilization" to (videoStabilization ?: "missing"),
        "shadingMode" to (shadingMode ?: "missing"), "lensShadingMapMode" to (shadingMapMode ?: "missing"),
        "physicalCamera" to (physicalCamera ?: "missing"), "lensMoving" to lensMoving)

    fun incomplete(): String? = when {
        exposureNs == null || exposureNs <= 0 -> "missing_exposure"
        iso == null || iso <= 0 -> "missing_iso"
        focus == null || !focus.isFinite() || focus < 0 -> "missing_focus"
        gains == null || gains.size != 4 || gains.any { !it.isFinite() || it <= 0 } -> "missing_wb"
        transform == null || transform.size != 9 || transform.any { !it.isFinite() } -> "missing_transform"
        crop == null || crop.size != 4 || crop[2] <= crop[0] || crop[3] <= crop[1] -> "missing_crop"
        modes.size != 5 || modes.any { it != 0 } -> "controls_not_manual"
        lensMoving -> "lens_moving"
        else -> null
    }

    /** Preview and still templates can report different matrices for the same explicit request. */
    fun differenceForStill(actual: ManualStateReading, maximumFocus: Float,
                           requestedTransform: List<Double>?, referenceIsPreview: Boolean): String? {
        val mismatch = difference(actual, maximumFocus)
        if (!referenceIsPreview || mismatch != "transform_changed" ||
            requestedTransform == null || requestedTransform.size != 9 ||
            requestedTransform.any { !it.isFinite() }) return mismatch
        // Recheck every field: accepting the requested matrix must not hide a crop/lens change.
        return copy(transform = requestedTransform).difference(actual, maximumFocus)
    }

    /** Keep existing exposure/ISO/focus/WB tolerances; geometry, processing modes and lens must agree. */
    fun difference(actual: ManualStateReading, maximumFocus: Float): String? {
        incomplete()?.let { return "reference_$it" }
        actual.incomplete()?.let { return it }
        return when {
            kotlin.math.abs(actual.exposureNs!! - exposureNs!!) > maxOf(10_000L, (exposureNs * .03).toLong()) -> "exposure_changed"
            kotlin.math.abs(actual.iso!! - iso!!) > maxOf(1.0, iso * .05) -> "iso_changed"
            kotlin.math.abs(actual.focus!! - focus!!) > maxOf(.0001f, maximumFocus * .02f) -> "focus_changed"
            gains!!.zip(actual.gains!!).any { (a,b) -> kotlin.math.abs(a-b) > .1f } -> "wb_changed"
            transform!!.zip(actual.transform!!).any { (a,b) -> kotlin.math.abs(a-b) > .000001 } -> "transform_changed"
            crop != actual.crop -> "crop_changed"
            opticalStabilization != actual.opticalStabilization -> "optical_stabilization_changed"
            videoStabilization != actual.videoStabilization -> "video_stabilization_changed"
            shadingMode != actual.shadingMode || shadingMapMode != actual.shadingMapMode -> "shading_mode_changed"
            physicalCamera != actual.physicalCamera -> "physical_camera_changed"
            else -> null
        }
    }
}

/** Request tags are unique to a run, including preview requests; old callbacks cannot alias index zero. */
internal class CaptureCallbackGate {
    class Tag internal constructor(internal val owner: CaptureCallbackGate, val index: Int?)
    @Volatile private var ended = false
    @Volatile private var stillsOnly = false
    fun tag(index: Int? = null) = Tag(this, index)
    fun accepts(tag: Any?): Boolean = !ended && tag is Tag && tag.owner === this
    fun matches(tag: Any?, expected: Tag): Boolean = accepts(tag) && tag === expected
    fun queuesResult(tag: Any?): Boolean = accepts(tag) && (!stillsOnly || (tag as Tag).index != null)
    fun beginStills() { stillsOnly = true }
    fun end() { ended = true }
}
