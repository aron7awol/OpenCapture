// SPDX-License-Identifier: GPL-3.0-only
package com.uniformitycomp.capture

/** Immutable request identity. Omitted/automatic values never mean "keep the previous value". */
internal data class ManualRequestKey(
    val exposureNs: Long, val iso: Int, val focus: Float, val gains: List<Float>
) {
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
        val scope: ManualCaptureScope, val reused: T?, val reason: String)
    private data class Entry<T>(val key: ManualRequestKey, val scope: ManualCaptureScope, val value: T,
        val stillHealthy: () -> Boolean)
    private var generation = 0L
    private var entry: Entry<T>? = null
    private var reason = "no_verified_still"

    @Synchronized fun invalidate(why: String) { generation++; entry = null; reason = why }

    @Synchronized fun begin(key: ManualRequestKey?, scope: ManualCaptureScope, capture: Boolean): Ticket<T> {
        val old = entry
        val fallback = when {
            !capture -> "fresh_controls_readback_required"
            key == null -> "automatic_or_partial_request"
            old == null -> reason
            !old.stillHealthy() -> "source_capture_cancelled"
            old.scope.difference(scope) != null -> old.scope.difference(scope)!!
            key != old.key -> "settings_changed"
            else -> "verified_identical_manual"
        }
        generation++
        entry = null // Failed work cannot leave the previous verification available.
        reason = "capture_not_verified"
        return Ticket(generation, key, scope,
            old?.value?.takeIf { fallback == "verified_identical_manual" }, fallback)
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
    fun tag(index: Int? = null) = Tag(this, index)
    fun accepts(tag: Any?): Boolean = !ended && tag is Tag && tag.owner === this
    fun end() { ended = true }
}
