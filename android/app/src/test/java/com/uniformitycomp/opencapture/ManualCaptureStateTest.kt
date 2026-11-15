// SPDX-License-Identifier: GPL-3.0-only
package com.uniformitycomp.opencapture

import com.uniformitycomp.capture.*
import org.junit.Assert.*
import org.junit.Test

class ManualCaptureStateTest {
    private fun key(exposure: Long? = 4_290_200_432, iso: Int? = 5331, focus: Float? = .2f,
                    gains: FloatArray? = floatArrayOf(2f, 1f, 1.5f)) = ManualRequestKey.from(exposure, iso, focus, gains)
    private val scope = ManualCaptureScope(Any(), Any(), Any(), Any())
    private val cache = ManualCaptureCache<String>()
    private fun seed() = cache.begin(key(), scope, true).also { assertNull(it.reused); cache.complete(it, "actual still #42", "none") }
    private fun actual() = ManualStateReading(4_290_200_432, 5331, 1f, listOf(2f,1f,1f,1.5f),
        listOf(1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0), listOf(0,0,4080,3072),
        listOf(0,0,0,0,0), 0,0,2,1,"wide")

    @Test fun firstUseNeedsVerificationButIdenticalManualBurstReusesIt() {
        assertEquals("no_verified_still", seed().reason)
        val next = cache.begin(key(), scope, true)
        assertEquals("actual still #42", next.reused)
        assertEquals("verified_identical_manual", next.reason)
    }
    @Test fun changingAnyExplicitControlRequiresSetup() {
        for (changed in listOf(key(exposure=4_290_200_433), key(iso=5332), key(focus=.21f),
            key(gains=floatArrayOf(2f,1f,1.6f)))) {
            seed()
            val next = cache.begin(changed, scope, true)
            assertNull(next.reused); assertEquals("settings_changed", next.reason)
        }
    }
    @Test fun exposureAndIsoChangesRetainColorSourceButNeverSkipReadback() {
        for (capture in listOf(true, false)) {
            for (changed in listOf(key(exposure=1_000_000_000), key(iso=100), key(exposure=1_000_000_000,iso=100))) {
                seed()
                val next = cache.begin(changed, scope, capture)
                assertNull(next.reused)
                assertEquals("actual still #42", next.exposureSource)
                assertEquals(if (capture) "settings_changed" else "fresh_controls_readback_required", next.reason)
            }
        }
    }
    @Test fun retainedColorNeverCrossesFocusWhiteBalanceAutomaticOrScopeChanges() {
        val changes = listOf(key(exposure=1,focus=.3f), key(exposure=1,gains=floatArrayOf(3f,1f,2f)),
            key(exposure=null), key(iso=null), key(focus=null), key(gains=null), key())
        for (changed in changes) {
            seed(); assertNull(cache.begin(changed,scope,true).exposureSource)
        }
        for (changedScope in listOf(
            ManualCaptureScope(Any(),scope.session,scope.reader,scope.preview),
            ManualCaptureScope(scope.camera,Any(),scope.reader,scope.preview),
            ManualCaptureScope(scope.camera,scope.session,Any(),scope.preview),
            ManualCaptureScope(scope.camera,scope.session,scope.reader,Any()))) {
            seed(); assertNull(cache.begin(key(iso=100),changedScope,true).exposureSource)
        }
    }
    @Test fun failedCancelledOrInvalidatedWorkCannotSupplyRetainedColor() {
        seed(); cache.invalidate("capture_error")
        assertNull(cache.begin(key(iso=100),scope,true).exposureSource)
        var cancelled = false
        val source = cache.begin(key(),scope,true)
        cache.complete(source,"still","none") { !cancelled }
        cancelled = true
        assertNull(cache.begin(key(iso=100),scope,true).exposureSource)
        seed(); val changed = cache.begin(key(iso=100),scope,true)
        assertNotNull(changed.exposureSource)
        cache.complete(changed,null,"readback_failed")
        assertNull(cache.begin(key(iso=200),scope,true).exposureSource)
    }
    @Test fun changedExposureReadbackStillChecksColorGeometryAndLens() {
        val reference = actual().copy(exposureNs=1_000_000_000,iso=100)
        assertNull(reference.difference(reference.copy(exposureNs=999_990_000,iso=99),10f))
        assertEquals("transform_changed",reference.difference(reference.copy(transform=List(9){1.0}),10f))
        assertEquals("physical_camera_changed",reference.difference(reference.copy(physicalCamera="other"),10f))
        assertEquals("wb_changed",reference.difference(reference.copy(gains=listOf(3f,1f,1f,2f)),10f))
        assertEquals("crop_changed",reference.difference(reference.copy(crop=listOf(0,0,4000,3000)),10f))
    }
    @Test fun automaticOrPartiallySpecifiedRequestsCannotReuseOrSeedManualState() {
        for (automatic in listOf(key(exposure=null),key(iso=null),key(focus=null),key(gains=null))) {
            seed(); val next = cache.begin(automatic,scope,true)
            assertEquals("automatic_or_partial_request",next.reason); assertNull(next.reused)
            cache.complete(next,"automatic reading","automatic_or_partial_request")
            assertNull(cache.begin(key(),scope,true).reused)
        }
    }
    @Test fun keyRejectsMalformedValuesAndDoesNotAliasMutableGains() {
        assertNull(key(gains=floatArrayOf(1f, Float.NaN,1f))); assertNull(key(focus=Float.NaN))
        assertNull(key(gains=floatArrayOf(1f))); assertNull(key(exposure=0)); assertNull(key(iso=0))
        val gains=floatArrayOf(2f,1f,1.5f); val frozen=key(gains=gains); gains[0]=4f
        assertEquals(key(),frozen)
    }
    @Test fun applyControlsAlwaysRequiresANewReadback() {
        seed(); val controls = cache.begin(key(),scope,false)
        assertNull(controls.reused); assertEquals("fresh_controls_readback_required",controls.reason)
    }
    @Test fun eachCameraSessionReaderAndPreviewIdentityInvalidates() {
        for ((changed,reason) in listOf(
            ManualCaptureScope(Any(),scope.session,scope.reader,scope.preview) to "camera_changed",
            ManualCaptureScope(scope.camera,Any(),scope.reader,scope.preview) to "session_changed",
            ManualCaptureScope(scope.camera,scope.session,Any(),scope.preview) to "reader_changed",
            ManualCaptureScope(scope.camera,scope.session,scope.reader,Any()) to "preview_changed")) {
            seed(); val next=cache.begin(key(),changed,true)
            assertNull(next.reused); assertEquals(reason,next.reason)
        }
    }
    @Test fun cancellationErrorsBackgroundAndLensChangesCannotBeUndoneByLateSuccess() {
        for(reason in listOf("capture_cancelled","capture_error","preview_error","camera_closed_or_backgrounded",
            "lens_changed","session_recreated","reader_changed")) {
            seed(); val inFlight=cache.begin(key(),scope,true)
            cache.invalidate(reason)
            assertFalse(cache.complete(inFlight,"late old still","none"))
            val retry=cache.begin(key(),scope,true)
            assertNull(retry.reused); assertEquals(reason,retry.reason)
            assertTrue(cache.complete(retry,"fresh still","none"))
            assertEquals("fresh still",cache.begin(key(),scope,true).reused)
        }
    }
    @Test fun newerCaptureCannotInheritAnUnfinishedOrFailedCapture() {
        seed(); val old=cache.begin(key(),scope,true); val newer=cache.begin(key(),scope,true)
        assertNull(newer.reused); assertFalse(cache.complete(old,"stale","none"))
        cache.complete(newer,null,"timing_not_verified")
        assertEquals("timing_not_verified",cache.begin(key(),scope,true).reason)
    }
    @Test fun cancellationAfterWriterCompletionAlsoInvalidatesTheSource() {
        var cancelled = false
        val ticket=cache.begin(key(),scope,true)
        cache.complete(ticket,"completed still","none") { !cancelled }
        cancelled = true // Session cancellation can happen after the camera writer has drained.
        val next=cache.begin(key(),scope,true)
        assertNull(next.reused); assertEquals("source_capture_cancelled",next.reason)
    }
    @Test fun actualMetadataChecksKeepExistingTolerances() {
        val value=actual()
        assertNull(value.difference(value.copy(iso=5333,exposureNs=4_290_200_000,focus=1.001f),10f))
        assertEquals("exposure_changed",value.difference(value.copy(exposureNs=1),10f))
        assertEquals("iso_changed",value.difference(value.copy(iso=100),10f))
        assertEquals("focus_changed",value.difference(value.copy(focus=2f),10f))
        assertEquals("wb_changed",value.difference(value.copy(gains=listOf(2f,1f,2f,1.5f)),10f))
    }
    @Test fun transformCropStabilizationProcessingAndPhysicalLensMustStayVerified() {
        val value=actual()
        val changes=listOf(value.copy(transform=List(9){1.0}) to "transform_changed",
            value.copy(crop=listOf(0,0,2040,1536)) to "crop_changed",
            value.copy(opticalStabilization=1) to "optical_stabilization_changed",
            value.copy(videoStabilization=1) to "video_stabilization_changed",
            value.copy(shadingMode=0) to "shading_mode_changed",
            value.copy(physicalCamera="tele") to "physical_camera_changed")
        for((changed,reason) in changes) assertEquals(reason,value.difference(changed,10f))
    }
    @Test fun missingOrNonfiniteMetadataDoesNotVerifyAndMovingLensDoesNotSeedReuse() {
        val value=actual()
        for(bad in listOf(value.copy(iso=null),value.copy(focus=Float.NaN),value.copy(gains=listOf(Float.NaN,1f,1f,1f)),
            value.copy(transform=null),value.copy(crop=null),value.copy(modes=listOf(0,1,0,0,0)),value.copy(lensMoving=true))) {
            assertNotNull(bad.incomplete()); assertNotNull(value.difference(bad,10f))
        }
    }
    @Test fun stalePreviewPartialFailureAndStillCallbacksCannotAliasCurrentRun() {
        val old=CaptureCallbackGate(); val current=CaptureCallbackGate()
        val oldStill=old.tag(0); val oldPreview=old.tag(); val still=current.tag(0); val preview=current.tag()
        assertFalse(current.accepts(oldStill)); assertFalse(current.accepts(oldPreview))
        assertFalse(current.accepts(0)); assertTrue(current.accepts(still)); assertTrue(current.accepts(preview))
        old.end(); assertFalse(old.accepts(oldStill))
        current.end(); assertFalse(current.accepts(still)); assertFalse(current.accepts(preview))
    }
}
