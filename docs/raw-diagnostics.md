# RAW Capture Diagnostics

iOS and Android have different native capture APIs and therefore different
diagnostic scopes. Do not relabel one platform's checks as the other's.

## Failure Timelines And Retained Evidence (November 19 Development)

Android now preserves metadata checkpoints before a frame can fail:

- `raw_request_checkpoint`: submission attempt, assigned sequence, capture
  start, first partial result, full result, image matching, DNG write/readback
  and buffer close. Request indices are one-based, sensor timestamps stay
  separate from elapsed-realtime timestamps. `raw_image_arrived` records native
  sparse sample statistics/hash and the preceding image timestamp even before
  an image/result pair is available. Buffer-loss, sequence-completion and wait
  timeout events distinguish missing callbacks from missing images.
- `raw_timeline_summary`: counters, overlapping interval totals and the last
  eight request timelines; older omitted request count is explicit. A submit
  checkpoint precedes the API call; an assigned sequence proves it returned.
  Missing milestones do not prove the HAL never performed the underlying work.
- Lifecycle/cancellation events record the initiating reason, job where known,
  camera/session/preview generations and aiming-preview result age. Aiming
  preview age does not describe the operation-owned repeating preview during
  RAW. Setup time, phone operation wall time and streaming verdict-wait time
  supplement existing successful-frame sidecar timings. Nested exception causes
  and bounded stack frames retain underlying application errors.
- `frame_selection_summary`: streaming requested/reserved/published/accepted/
  rejected/pending counts and all verdicts, or on-device averaging decisions.
  Averaging retains up to 12 rejected/neighbor compact evidence rows, prioritizing
  rejected frames, with an explicit omitted count. Decisions cover all attempts.
  Sparse stats/hashes, actual exposure/ISO/timestamps, black/white normalization
  and integrity distinguish empty acquisition from later file changes. They do
  not establish the cause of an empty frame. No new rejection policy is added.
- `lens_shading_evidence`: first and latest observed maps, change count and
  per-channel full-map extrema. Up to 17 columns by 13 rows of RGGB gains are
  retained per representative snapshot. Larger maps use labeled endpoint
  samples with original indices and `gridComplete:false`, not interpolation.
  Full native maps still live in successful sidecars. Selected settled setup
  and matched RAW results are observed, not every preview. Each snapshot includes
  source/result identity and shading mode/applied flag; never substitute a preview
  map into RAW processing. Missing/invalid observations are explicit events.

These larger summaries live under `cameraDiagnostics.evidence` and survive
event-ring eviction until the next operation begins. Previously persisted jobs
retain their own snapshot. The event ring remains bounded; diagnostics add no
exposures and do not retain full images in RAM. Abrupt process death can recover
only checkpoints actually persisted before the death. No internal vendor logs
are claimed. This diagnostic extension and short-preview experiment are Android
only; desktop metadata summaries also accept existing iOS sidecars.

## Android Control-Failure Evidence (November 19 Development)

`manual-control-comparison-v1` adds metadata-only evidence to the existing
persistent camera event log. No request settings, acceptance tolerances, or
capture scheduling have changed. In particular, an automatic ISO above the
advertised manual range is recorded, not silently clamped or declared causal.

- `auto_settled_result` records the actual reading selected to seed locking,
  distinct from the first automatic result.
- `manual_lock_selected` records expected locked exposure, ISO, focus and RGGB
  gains/color matrix, alongside advertised exposure and ISO ranges.
- `capture_setup_failure` preserves setup policy, requested/automatic controls,
  built auto/manual requests, first/settled/latest readings, latest manual
  comparisons, request-tag match and retained-reference mismatch. It is emitted
  before cleanup, including missing-result timeouts and failures before any RAW.
- `manual_state_mismatch` records the timestamp-matched RAW's actual controls,
  built request, locked expectations, setup reference, request index and image
  timestamp before rejecting the frame. `fatal` distinguishes rejection from a
  normal-path mismatch that only prevents verified-state reuse.
- Each `checks` entry includes `field`, `expected`, `actual`, `tolerance`, and
  `status` (`matched`, `mismatch`, `missing`). All exposure/ISO/focus/control-mode/
  AE/AF/AWB/RGGB failures are retained, not just the first. Missing readback is
  explicitly `"missing"`, never filled with the request. Existing comparison
  bounds are shared with the decision itself.
- Readings now include CONTROL_MODE, AF_MODE, AE/AF states, frame duration,
  crop/stabilization/physical-camera fields, and numeric `colorTransformMatrix`.
  Existing `colorTransform` string remains compatible. Shading maps remain
  dimensions/hashes in these events, not pixel or gain-array downloads.

Fatal comparison/setup events preserve the existing first fault. History is
trimmed by event count and a 64 KiB snapshot budget (retaining first fault) to
leave room under the 96 KiB persistent-record limit. Bounded snapshots are logged
at stage transitions/failure, not every preview result. The latest setup readback
is retained in memory until failure; a process kill before that event can only
recover events already written to disk.

Windows retrieves these event payloads without dropping unknown fields, including
recovered diagnostics on reconnect. The failed-run support ZIP includes them
even without a manifest or original photo. This is Camera2 application evidence,
not internal Samsung driver logs. Physical verification remains pending.

## Timing Integrity And Stage Timings

Android 0.4.4 adds `timingPolicy:"fresh-camera2-raw-v1"` and
`timingIntegrity` to generic manual RAW diagnostics. Original DNGs remain
unchanged. Optional phone averaging and the desktop original selector exclude
`capture_timing_invalid` attempts, including dark references, within the same
N usable / 2*N attempts limit. Batch original capture still preserves every
attempt; the measurement consumer decides whether to reject or replace it.

The policy checks two independent bounds using actual result exposure:

- Image arrival minus request submission must cover the exposure, allowing
  `max(5 ms, min(50 ms, exposure / 50))` tolerance. These two timestamps use
  `SystemClock.elapsedRealtimeNanos()` and do not require a sensor-clock offset.
- Sensor exposure start must not precede submission beyond that tolerance,
  but only when `sensorTimestampSource` is Camera2 REALTIME (1). UNKNOWN (0)
  does not permit comparing sensor time to the app clock.

An early `onCaptureStarted` callback alone is never grounds for rejection.
The [sensor timestamp](https://developer.android.com/reference/android/hardware/camera2/CaptureResult#SENSOR_TIMESTAMP)
is the start of first-row exposure, and only
[REALTIME](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics#SENSOR_INFO_TIMESTAMP_SOURCE)
guarantees the comparable timebase. The app requests ZSL off where supported;
this policy is for its fresh manual RAW requests, not buffered or fused photos.
The tolerance is an application integrity bound, not an Android accuracy
guarantee or a diagnosis of the Pixel driver. Physical verification is pending.

Consumers recompute the assessment from hashed sidecar values. A declared
policy with malformed evidence fails validation. Older sidecars without this
policy are unassessed; do not retroactively label them passing. iOS
0.4.3 records submission/processing-arrival times but does not declare this
Camera2 policy or apply its rejection rule to Apple RAW/ProRAW.

Android also records preparation start, session ready, controls locked when that phase ran,
`sessionReused`/`controlsReused`, and optional averaging inspection start/end.
iOS records preparation start/lock/reuse and averaging inspection start/end,
and retains its existing encoding
and saved-file readback timings. Capture manifests may include `performance`
durations in seconds for preparation, capture/inspection and aggregation/
packaging; names depend on the platform. Streaming results keep their frozen
completion semantics. These are overlapping stage measurements, not sums that
establish end-to-end latency or Wi-Fi throughput.

## iOS 0.4.1

Bayer captures automatically add `captureDiagnostics.diagnosticsVersion:3`
and `platform:"ios"` to each original's JSON sidecar, with or without averaging:

- `rawBeforeDng` / `rawAfterDng`: native pixel-buffer samples around
  `fileDataRepresentation()`, before the photo callback returns. Width, height,
  row stride, pixel format, CFA, coordinates/values, and sample SHA-256 are saved.
- Sampling uses 16x12 complete 2x2 blocks (768 sites), ordered by block row,
  column, then TL/TR/BL/BR. Hash bytes are unsigned 16-bit little-endian.
- `rawSavedDng`: SHA-256 of the actual saved file compared with the encoded Data.
  `encoded_bytes_verified` means file bytes only, NOT decoded DNG pixels.
- `normalization` / `bufferOrigin`: DNG active area, stored dimensions,
  black/white, CFA and supported radial model; native origin in DNG coordinates.
- Monotonic pre-encoding/post-encoding/readback times, photo timestamp,
  resolved-settings ID, and repeated sample-hash indication within a burst.
  EXIF exposure/ISO and shutter-time device readings remain separately labeled.

`consistent_native_buffer_and_file` means the native checkpoints and file-byte
check matched. `sample_mismatch_observed` or `incomplete_diagnostics` are not
driver diagnoses. There is no phone-side decoder for compressed DNG pixels;
the desktop averaging importer independently compares retained accepted DNGs
against their native sample coordinates. Unsupported native layouts can still
produce valid originals with explicit incomplete diagnostics, but cannot enter
the on-device average. Apple does not expose Android's Camera2 state/result
pipeline, and those fields must not be fabricated for iOS.

Buffer samples, file bytes and controls can all be consistent while a frame is
genuinely dark, stale, or otherwise unusable. Preserve originals for investigation.
New iOS native-buffer behavior requires physical-device verification; macOS core
tests and simulator navigation cannot establish sensor behavior.

## Verified Setup Reuse With Continuous Preview (November 19 Development)

The identical full-manual cache is now also eligible in `sequential-preview`.
Repeating preview stays active, with a new operation-owned callback; stills
remain sequential. It does not repeat an already verified manual-preview wait.
A successful controls-only request can seed the following identical capture
with `reusedVerification.source:"previous_manual_preview"`; later verified
stills use `previous_completed_still`. Every controls-only request itself still
requires fresh readback. Missing metadata, changed controls/session, cancellation
or camera errors prevent reuse. Per-still actual controls, timestamps, integrity
checks, scene waits and verdict handling remain required. No shorter-preview
exposure policy has been added. Physical speed and compatibility are unverified.

## Android 0.4.5 Verified Manual Fast Path (Historical)

Build 13 extends the earlier manual-control reuse: a matching fully specified
request skips both auto setup and the tagged manual-preview result wait when
the same camera/session/reader/preview configuration has a verified completed
still. The cache compares the exact requested exposure, ISO, focus and copied
WB tuple, independently of hardware rounding in the actual results. It keeps
the locked color transform, crop and stabilization settings. Missing essential
readback, a moving lens, timing-invalid or sample-unverified frames prevent a
burst from seeding reuse. No shading map or `TotalCaptureResult` is cached.

`captureSetup` adds:

- `policy:"verified-manual-still-reuse-v1"`, `fastPathUsed:true` and
  `manualPreviewSkipped:true` only when eligible.
- `reason:"verified_identical_manual"`, or a normal-setup reason such as
  `settings_changed`, `automatic_or_partial_request`, `session_recreated`,
  `source_capture_cancelled`, `fresh_controls_readback_required`, or missing
  verification evidence. Normal policy names below remain valid.
- `reusedVerification`: `source:"previous_completed_still"`, original
  `frameNumber`, `sensorTimestampNs` and monotonic `verifiedElapsedNs`. These
  identify old evidence; they never stand in for a new frame's result.
- `setupDurationNs` on both paths; `sceneWaitStartedElapsedNs` and
  `sceneWaitEndedElapsedNs` on the fast path. The scene wait remains
  `max(250, settleMilliseconds)` ms. It does not inspect preview brightness.

On the fast path, **omit** `manualRequestSubmittedElapsedNs`, manual first/
settled results/times, `manualResultsSeen` and top-level `controlsLockedElapsedNs`.
No new control-settled event occurred. `sessionReusedElapsedNs` records reuse,
not configuration or settling. In-flight preview work may still finish after
`stopRepeating`; a smaller setup duration is not by itself an end-to-end speedup.

Every new still retains fresh submission/arrival/result timing and unique
run-owned request tags. It must match its own image sensor timestamp and request
index. `manualStateVerification` records the metadata comparison. A changed
state on the fast path fails the job before that frame is published; a compact
`OpenCaptureSetup` log gives the mismatch and `nextJob=normal_setup`. Pending
captures are aborted/drained and the reader replaced. No silent recapture,
underfilled average or stale-result substitution is permitted. Terminal-failure
staging follows the existing cleanup contract; retain logs and any already
received PC evidence. First-use devices with incomplete extended metadata can
continue through normal setup but cannot enable this optimization.

The exposure/ISO/focus/WB tolerances are unchanged (3%/5%/2% of maximum focus/
0.1 gain, with existing absolute floors). Matrix comparison allows only 1e-6
per coefficient between actual readbacks; crop, stabilization, shading request
modes and physical-camera ID must agree. Lens-shading **gains** are neither
compared for reuse nor substituted: preserve each actual map independently.
The source still must have valid fresh timing and consistent sample checkpoints.

`applyControls` always returns a new preview reading, even for identical manual
values. Automatic/partly specified requests, changed settings, camera/lens or
session changes, reader/surface replacement, cancellation, backgrounding and
errors take the normal path. Generation and callback ownership checks reject
late success/failure/partial callbacks from an older run.

Software fixtures and builds cannot validate a Pixel HAL or prove savings.
See [the test plan](test-plan.md#android-045-manual-reuse-development) for the
held-build benchmark procedure.

## Manual Color Continuity Across Exposure Changes (Source Only)

Source after Android 0.4.5/build 13 adds
`captureSetup.policy:"manual-exposure-change-locked-color-v1"`. A fully specified
manual exposure/ISO change may retain the previous verified still's color
transform and WB when requested focus/WB and all camera/session/reader/preview
identities are unchanged. A source capture that failed, was cancelled, or lacks
verification is ineligible. Automatic/partial controls never mean reuse.

This path sets `automaticColorSetupSkipped:true` and `retainedColorState` with
the same source-frame provenance fields as `reusedVerification`. It still sets
`fastPathUsed:false` and `manualPreviewSkipped:false`, and records fresh tagged
manual-preview readings/times. New exposure/ISO must match readback; retained
actual WB, matrix, focus, crop, stabilization, processing modes and physical
camera must agree. No auto-preview timestamps/results are fabricated. The
verified identical-request fast path above is unchanged.

The purpose is to avoid an unnecessary automatic-WB transition when only
exposure changes. Per-frame shading maps remain fresh and unmodified; neither
map stability nor correct optical shading follows from retaining color state.
No public request/schema change or legacy-client opt-in is required. Build,
JVM verification and real-device effectiveness are pending on an Android
development machine.

## Android Manual Setup Follow-Up (Source Only)

The section below describes the earlier path retained for normal setup in
0.4.5. Its manual-preview wait is skipped only by the verified path above.

The source after 0.4.3/build 10 adds optional `captureSetup` evidence inside
Android diagnostics v2. It is now merged with the performance implementation;
physical verification remains pending and it does not identify a published APK.

OpenCapture can reuse a previously established color transform and locked
controls when the next job explicitly repeats identical exposure, ISO, focus
and WB on the same open camera. The first preview request is then manual,
without an intervening automatic-WB phase. Initial/changed/partly automatic
requests retain auto-then-lock setup. Camera close, lens change, camera error,
disconnect and capture failure clear cached state. Omitted controls never mean
"reuse the last manual value." The legacy capture caller does not opt in.

Each original's `captureSetup` contains:

- `policy`: `auto-then-lock-v1` or `manual-reuse-identical-controls-v1`.
- `sessionRecreated` and `previousLockedAvailable`. The merged performance
  implementation reuses the compatible RAW+preview session between healthy jobs.
- `advertisedPartialResultCount` and `advertisedPipelineMaxDepth`, when reported.
- Monotonic `setupStartedElapsedNs`, `sessionRequestedElapsedNs`,
  `sessionConfiguredElapsedNs`, `manualRequestSubmittedElapsedNs`,
  `manualFirstResultElapsedNs`, `manualSettledElapsedNs` and `manualResultsSeen`.
  Session request/configuration times appear only for a new session; reused
  sessions instead record `sessionReusedElapsedNs`.
- For the automatic path only: `autoRequestSubmittedElapsedNs`,
  `autoFirstResultElapsedNs`, `autoSettledElapsedNs`, `autoResultsSeen`.
- `autoFirstResult`, `autoSettledResult`, `manualFirstResult` and
  `manualSettledResult` when that phase exists. These small actual-result
  snapshots include controls, frame/sensor time, color transform, shading modes
  and map dimensions/SHA-256 where reported. Map hash bytes are Camera2 gains in
  row-major R/G_even/G_odd/B order, IEEE float32 little-endian.

Manual-phase selection requires the current request tag and actual exposure,
ISO, focus and WB to match the locked controls within the existing tolerances.
Each snapshot separately records actual values returned by the camera. Missing result
keys remain absent, not invented. Existing per-still native metadata and the
DNG map remain authoritative. Setup maps are never substituted for a still's
own map or used to force apparent map stability.

Compare repeated identical-manual bursts with the seed/automatic path at the
same settings. Check map hashes within and between bursts, actual controls and
setup durations. Exercise cancellation, close/reopen, changed/omitted controls
and lens changes before accepting the fast path. Do not subtract sensor clocks
from monotonic clocks, or call all setup duration removable overhead. Keep
session reuse constant while comparing exposure overlap; neither change proves
that per-frame lens-shading maps will be stable.

The per-frame diagnostics additionally record `partialResultCount`, first/last
partial-result monotonic times, `firstPartialResultKeys`, and the first
`partialShadingMapElapsedNs` if a partial result contains a map. Absent partial
callbacks leave these fields absent. These are timing observations only;
original writing and sidecars still use the timestamp-matched full result.
The subsequent [exposure-overlap change](exposure-overlap.md) uses these timings
to evaluate the source-only bounded capture pipeline. It also records Android
overlap policy/submission state and iOS `captureTiming` alongside the existing
per-frame diagnostic checkpoints.

## Android 0.4.1

Android source 0.4.1/build 7 adds automatic, phone-side diagnostics for frames
whose RAW data appears empty despite plausible capture-result settings.
The diagnostics alone do not enable averaging, reject originals, change exposure, or attempt a
camera-driver workaround. A local build is not a published update.

## Evidence In Each Sidecar

`frame-NNNN.json` contains `captureDiagnostics.diagnosticsVersion: 2`:

- `rawAtAcquisition`: sample the acquired RAW image before waiting for its
  timestamp-matched capture result.
- `rawBeforeDng` and `rawAfterDng`: sample the same still-owned image around
  `DngCreator.writeImage`.
- `rawSavedDng`: read those native coordinates back from the saved DNG's RAW
  strips, not its preview or thumbnail. Record its stored black/white levels,
  Bayer layout and active area when present, without applying them.
- `sampleIntegrity`: pairwise comparisons, findings and a bounded assessment.
- `sameSamplesAsPreviousFrame`: exact sampled-pixel equality with the preceding
  acquired frame in this burst, when both samples are available.
- Request, capture-start, image-arrival, result-arrival/match, writer, DNG
  readback and image-close timestamps; reader capacity and queue depth.

The native Camera2 metadata retains actual controls, sensor timestamps, frame
duration, pipeline depth, rolling-shutter skew, dynamic black/white levels and
physical-camera results. It now also records AE/AF/AWB/lens states and black-level
lock. Characteristics include non-unique device/OS build information, not serial
numbers, account identifiers, controller addresses or credentials.

Sampling uses at most 96x64 evenly distributed complete 2x2 Bayer blocks
(24,576 native unsigned 16-bit values). Hash order is block row, block column,
then top-left/top-right/bottom-left/bottom-right, with each sample hashed low
byte first. This is the same coordinate/hash policy as diagnostics v1.
Per-position statistics keep the four Bayer sites separate, without assuming
which is red, green or blue. All buffers are duplicated for reads; their
contents, positions and limits are not modified. No full-resolution extra
image or whole burst is retained in RAM.

The on-phone file reader is deliberately limited to bounded classic-TIFF,
unsigned, uncompressed RAW16 strips. Both TIFF byte orders, raw SubIFDs,
multiple strips and partial final strips are supported. An unsupported or
malformed layout records `unavailable`; it never invents a matching hash or
changes the original. Total decoded metadata is bounded to 65,536 values and
512 KiB across directories. The existing desktop decoder remains authoritative.

## Interpreting Results

| Observation | What it can establish |
| --- | --- |
| Acquisition differs from pre-write | Sampled pixels changed while the app held the image, before DNG writing. Investigate ownership, synchronization and the camera stack. |
| Pre-write differs from post-write | Sampled buffer changed during writing. This does not by itself identify which component changed it. |
| Pre-write differs from DNG readback | Saved RAW samples differ from the pre-write sample. Inspect the writer, concurrent changes and the diagnostic reader. |
| All samples match, but the frame is nearly empty | Empty-looking samples were already present when the app acquired the image. This argues against later DNG serialization as the source at those sites, not proof of a sensor/HAL defect. |
| Samples match but imported signal disagrees | Inspect stored versus actual black/white metadata, crop/Bayer interpretation and desktop normalization. |
| Adjacent frames have the same sample hash | Possible repeated/stale data, or a genuinely constant sampled scene. Compare timestamps, noise and neighboring frames before concluding. |

`consistent_sampled_path` means only that the sampled sites agree at these
checkpoints. It does not certify the whole image, exposure accuracy, optical
signal, or an absence of changes between checkpoints. `sample_mismatch_observed`
is an observation, not a root-cause label. Missing/incompatible evidence produces
`incomplete_diagnostics`.

A legitimate dark scene may have flat/low samples. **Do not discard a frame just
because these diagnostics call it flat.** Empty-buffer selection and bounded
replacement remain the controller's responsibility. All requested originals
and their sidecars are transferred, including suspicious frames. No changes to
original counts, hashes, request semantics, capture format or quality gates.

`*ElapsedNs` is monotonic elapsed realtime; `*SensorTimestampNs` and the native
image timestamp use the sensor's declared time domain. Do not subtract the two
without evidence that their clocks correspond. Diagnostic sampling and readback
cost time; the new timestamps let the consumer measure that overhead. A compact
`OpenCaptureRAW` logcat line points to the assessment, while the full evidence
is in the hashed sidecar so it is not truncated by logcat's line limit.

## Reproduce Without Losing Evidence

Use a fixed scene and the same requested/actual manual settings. Compare one
frame with a longer sequential burst. Keep neighboring normal originals,
suspect originals, sidecars and the manifest together on the desktop. Match
observations by job ID and frame index, not by unrelated clock differences.
Do not add on-device averaging or silent retries to this first comparison.

No phone taps or special diagnostic screen are required. The existing desktop
capture action requests the burst and receives these sidecars automatically.
Phone files retain the normal active-session lifetime and ACK cleanup. The
desktop retains evidence after phone cleanup. True capture exceptions remain
failed jobs; this document does not introduce an archive or failure-download API.

The normal path owns each `Image` until writing and diagnostic readback finish,
then closes it before the completed-frame callback. Late image callbacks and
shutdown draining share a queue lock so an acquired image cannot be stranded
after cleanup. This hardening is not a demonstrated explanation for the reported
successful-but-empty originals.

## Verification

JVM fixtures cover native row padding, read-only/nonzero-position buffers,
unsigned samples, both TIFF byte orders, strips/SubIFDs, fractional black-level
metadata, truncation, invalid offsets, duplicate/cyclic metadata and checkpoint
classification. A real Android emulator capture can test the platform writer and
transport; it cannot reproduce or validate a physical phone's sensor/HAL behavior.

The optional [averaging mode](frame-averaging.md) adds per-frame validity
checks before accumulation, exact usable-frame counts with bounded replacement,
retained rejection reasons and actual controls, fractional precision, clipping
evidence and temporal moments. Dark-reference captures must not inherit an
illuminated-scene empty-frame assumption. Preserve an originals-only diagnostic
mode so aggregation cannot hide a recurrence. For root-cause comparison, leave
averaging off or enable `keepOriginals`; use the original-file baseline before
interpreting a timing-dependent change in failure rate.
