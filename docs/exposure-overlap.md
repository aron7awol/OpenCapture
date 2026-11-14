# Bounded exposure overlap

Source after Android 0.4.3/build 10 and iOS 0.4.2/build 33 can overlap the next
original capture with completion of the previous photo. This is development
source; existing released apps do not contain it. Physical timing/quality
verification remains pending.

## Scope

Original-file bursts, including `originals-v1` streaming, use at most **two
unfinished photos**. Controls settle once before the burst. Original formats,
dimensions, quality priorities, DNG bytes, actual-control evidence and receipts
are unchanged. JPEG and ProRAW are not substituted for Bayer RAW. No zero-shutter
lag, deferred photo delivery or adaptive quality mode is enabled by this change.

On-device averaging retains sequential exposures because usable-frame selection
happens on the phone. The merged performance implementation overlaps inspection
of one saved frame with the next exposure, drains before a potentially final
frame, and preserves the exact usable count. The legacy Android measurement app
also retains its sequential default. An original-only burst captures exactly
the requested count; streaming can admit one speculative unjudged capture, as
specified by [original streaming](original-streaming.md).

## Android

`DngCapture` can submit the next still after acquiring/sampling the current RAW
image, before waiting for the current image's full metadata. It owns the current
Image until its timestamp-matched TotalCaptureResult is available and DNG
writing/checkpoints finish. A bounded result cache retains early metadata for
the next image. Sensor timestamp and request-index checks prevent cross-frame
metadata substitution; a mismatch fails rather than relabeling a file.

Only one request is prefetched. The next loop consumes it before admitting
another, so there are at most two unfinished originals. The main worker still
publishes completed files in request order. Acquisition, prewrite, postwrite
and saved-DNG sample comparisons remain enabled.

On failure/cancellation, outstanding camera work is aborted, acquired images
are closed, and cleanup synchronizes with any acquisition sampling callback.
OpenCapture closes the affected reader and creates a new one for the next job;
late buffers cannot enter that next job's reader. Healthy bursts reuse the
reader. The merged performance source also reuses compatible RAW+preview sessions
and verified unchanged manual controls; compare overlap with those settings
held constant.

Per-frame diagnostics add `exposureOverlapPolicy` (`two-frame-after-image-v1`
or `sequential-v1`) and `submittedWhilePreviousUnfinished`. Existing submission,
image-arrival, result-arrival and image-close times demonstrate actual overlap.
`previousImageClosedElapsedNs` may be later than the current submission; that
is valid overlap, not a negative-duration exposure. Partial-result and setup
evidence is documented in [RAW diagnostics](raw-diagnostics.md).

## iOS

AVFoundation distinguishes exposure completion from processing/file completion.
`ExposurePipeline` uses `didCapturePhotoFor` to permit one following request.
`CameraService` also waits for `captureReadiness == .ready`; the device can keep
the sequence effectively serial if it is not ready. It owns delegates by their
unique settings IDs and never allows more than two unfinished photos.

File consumers remain ordered even if a later callback finishes first. A
pre-exposure failure releases its waiter without enabling lookahead. Cancellation
and early stream completion drain every admitted task before staging cleanup.
Timeout/Stop completion is deferred while a delegate is actively writing;
future callbacks cannot restart writing after that delegate has completed.
This is cooperative cleanup, not a promise to cancel an individual AVFoundation
exposure instantaneously.

Each sidecar adds `captureTiming` with scheduling policy, settings ID and
monotonic submission, exposure-start/end, processing-callback, encoding and
original-write-completion timestamps. These times are separate from the photo's
sensor timestamp. The policy indicates allowed scheduling, not proof that a
particular pair overlapped; compare actual timestamps.

Apple documents [exposure and processing callbacks, including multiple captures](https://developer.apple.com/documentation/avfoundation/tracking-photo-capture-progress)
and [capture readiness](https://developer.apple.com/documentation/avfoundation/avcapturephotooutput/capturereadiness-swift.property).
The Pixel's observed metadata delay is not evidence of an equivalent iPhone
delay or a predicted iPhone speedup.

## Streaming admission and ownership

Both platforms now reserve an attempt **before** submitting it. Reserved count
minus verdict count must never exceed two, even when neither file is published.
Publication requires a previously reserved index. Publishing a file does not
release a slot; only an ordered controller verdict does. Reservations cannot
exceed 2*N attempts or continue after freeze.

The opportunistic reservation is nonblocking. Waiting for a verdict before
finishing/publishing the current file could deadlock the controller, which needs
that file to judge it. If no slot is available, finish the current file, publish
it, then wait for admission normally. The public HTTP protocol and frozen
manifest contract do not change.

Freeze still exposes the exact judged prefix immediately. Writer drain and
`cameraReady` remain separate; ACK/session cleanup cannot delete a file beneath
an active writer. New jobs and lens changes remain excluded until drain ends.

## Initial verification and mobile checks

At initial source preparation, all 36 Python controller tests passed, `git diff --check`
passed, and all 55 explicit iOS export paths exist, including the new pipeline,
tests and this document. The first HTTP test run was blocked by the local
sandbox's loopback socket restriction (WinError 10013); the rerun outside that
sandbox passed. These checks verify existing contracts and source packaging,
not the new Kotlin/Swift scheduling implementation.

Added Swift `ExposurePipelineTests` cover overlap before file completion, two
unfinished writers, ordered consumption after out-of-order completion,
nonblocking admission, pre-exposure failure, cancellation drain, late discarded
failure and sequential mode. Android/Swift streaming tests now reserve their
simulated exposures and cover unpublished reservations, unreserved publication,
exact counts, replacement exhaustion and immutable freeze. Session lifetime
fixtures retain a reserved extra exposure through ACK/cancellation.

Run Android build/lint/debug/release JVM tests and Swift debug/release tests,
then compile the iOS device target. Verify exported standalone sources include
`Core/ExposurePipeline.swift` and its tests; the explicit export allowlist has
been updated. The originating display PC had no Java/Android SDK or Swift/Xcode, so these
new mobile tests and compilation were **not run there**. Do not label fixture
results or device performance as passing before the equipped PCs run them.

Physical follow-up, separately on Android and iOS:

1. At fixed settings, capture original-only and streamed bursts at N=1/6/18.
   Verify exact selected counts, per-frame actual controls, hashes, map stability,
   unchanged native/file checkpoints and no stale/cross-frame metadata.
2. Compare overlap enabled/disabled with otherwise identical source/settings.
   Internal scheduling switches are `DngCapture.overlap` / the OpenCapture caller
   and `ExposurePipeline.run(overlap:)`; there is no new public request option.
   Keep manual-setup/session changes constant when measuring this effect.
3. Measure setup separately from steady request spacing, exposure, metadata,
   file writing, transfer, PC decoding, frozen-result time and writer drain.
4. Delay controller verdicts; confirm at most two reserved/unjudged attempts.
   Exercise rejection replacements, exhaustion, Stop/background/expiry and ACK
   while the final extra photo is unfinished. Resume a fresh job afterward and
   verify no old image or callback enters it.

No APK/IPA/feed version was changed or published with this source preparation.
Use the existing signing identity and assign a distinct development build before
installing. A successful physical test is required to claim time savings.
