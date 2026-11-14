# Verification and Device Test Plan

## Exposure Overlap (Android 0.4.4 / iOS 0.4.3, 2026-11-14)

Read [the bounded overlap design and verification checklist](exposure-overlap.md).
Android original bursts can submit the next exposure after RAW image arrival;
iOS uses exposure completion plus output readiness. Initial source preparation
on the display PC lacked mobile toolchains. After merging the performance
changes on the build PC, Android compilation, lint, 83 debug and 83 release
tests passed, together with 37 controller tests. On combined revision `f5b9922`,
[iOS CI](https://github.com/aron7awol/OpenCapture/actions/runs/43597418131) passed
43 debug and 44 release Swift tests, unsigned device compilation and one
simulator navigation test. Release candidates also run CI at their exact source
revision. Software tests do not establish physical overlap or image quality.

Run the new Swift pipeline tests, updated Android/Swift stream reservation and
session-lifetime fixtures, normal platform builds/lint and exported-source
builds on the equipped PCs. Physical N=1/6/18 original/streamed tests must verify
actual overlap, identity, counts, reader/buffer integrity and post-cancellation
recovery. Averaging and legacy Android keep sequential exposures; averaging
may overlap inspection of one saved frame with the following exposure.

## Capture Performance Follow-Up

Use identical actual controls and scene for six- and eighteen-frame original
and optional-average comparisons. Record app/build, thermal state, per-frame
timings, stage durations and payload bytes. Software parity and simulator
navigation do not establish physical speed, fresh sensor timing or calibration.

- Verify first preparation, identical fully manual reuse, small intentional
  control changes, auto controls, lens changes, failure and resume. Retain scene
  settling and confirm actual readings for every accepted frame.
- On Android, check persistent preview/RAW sessions, REALTIME and UNKNOWN
  timestamp handling, long and short actual exposures, and dark/illuminated
  timing-invalid replacement. An early start callback with a valid image passes.
- For optional averaging, compare the same accepted originals against desktop
  results; verify N/2*N, ordered publication and cancellation during inspection
  and accumulation. Cleanup must wait for all readers to stop.
- Verify optional iOS thumbnails, unchanged aiming preview/original receipts,
  independent saved-DNG hash verification and ProRAW behavior separately.
- Measure inspection, aggregation and transfer independently. Neither a frozen
  streaming result nor a completed photo proves the camera writer has drained.

## Original Streaming And Remote Preview (0.4.2, 2026-11-13)

Read [original streaming](original-streaming.md). This opt-in path is separate
from on-device averaging and retains original files plus controller verdicts.

- Core tests cover exact N=1/6/18 targets, all-rejected 2*N exhaustion,
  immutable selection, byte-budget checks before publication, bad/replayed
  verdicts and the two-attempt lookahead bound. Session tests exercise
  freeze/ACK before the discarded exposure returns, next-job/lens exclusion,
  cancellation/session-end cleanup, and failures of the late extra exposure.
- iOS device compilation, 32 Swift debug / 33 Swift release tests (including
  one release-only 12 MP processing case), and simulator portrait/both-landscapes/
  About navigation passed in final CI run `43536706712` at `54c9f30`.
  Version 0.4.2/build 33 was published as an unsigned device IPA for AltStore.
  The simulator has no camera; synthetic session tests
  use a fake asynchronous capture callback, not AVFoundation hardware.
- Android parent build/lint and 74 tests per debug/release variant passed.
  API 33 with the emulated RAW camera transferred batch and streamed Bayer
  originals at N=1/6/18, replacements and insufficient results. Hashes, exact
  final prefixes, idempotent retries and freeze-before-writer-drain were checked.
  This is transport verification, not frame-quality or performance acceptance.
- Android served JPEGs were compared with phone screenshots in portrait,
  both landscape sides and reverse portrait, including direct 180-degree
  turns. The surface bitmap did not include the view's fit/rotation transform;
  simply copying at native view size still stretched the export. The current
  path explicitly rebases that transform into thumbnail coordinates and draws
  it once. Both temporary bitmaps have a maximum edge of 640. UI guide lines
  remain absent from the JPEG. The emulator's stationary synthetic camera
  cannot establish physical orientation; repeat against labeled real targets.

Hardware follow-up: test physical Pixel and iPhone with identical locked controls
in batch and streaming modes. Inspect actual exposure/ISO/focus/WB and capture
timestamps, not requested settings alone. Demonstrate exposure/transfer overlap
and measure time through final PC commit separately from discarded-writer drain.
Exercise Stop/background/expiry during capture and after freeze, and confirm
files remain until the writer exits. On Pixel, compare served JPEG geometry
with the phone view under capture load. Android 0.4.2/build 8 and iOS 0.4.2/build
33 are published; these software checks do not establish physical accuracy or
speed. Older dated sections below are historical.

## Optional Bayer Averaging (0.4.1)

Read [frame averaging](frame-averaging.md). Test original-file mode and averaged
mode separately; neither replaces physical-device verification.

- Verify capability gating and capture-only request validation. Send v1 only
  to advertising Android cameras and v2 only to advertising iOS cameras.
  The app's minimal foreground UI stays unchanged.
- Check fractional parity means, luma sample variance (including inter-site
  covariance), native clipping counts, strip boundaries and both TIFF byte
  orders. With all originals retained, compare against independent desktop
  decoding of those exact contributing frames, not a second capture.
- Inject known empty/mismatched attempts in fixtures. Require the usable target,
  unchanged locked controls and no more than exactly 2*N attempts. Confirm initial
  low attempts can be reclassified, and dark references are not brightness-rejected.
- Exhaust the budget: transfer every sidecar/rejected original but no average;
  desktop must save diagnostics and fail without publishing a measurement.
- Verify receipt/hash/length bounds, missing originals/sidecars, payload NaNs,
  invalid variance/clipping counts, changed normalization and unsupported DNG
  opcodes. Repeated downloads and ACK must not capture additional frames.
- Cancel during capture/aggregation and capture again in the same foreground
  session. Confirm no lingering file exposure after ACK/session cleanup.
- Measure overhead and compare Pixel failure rate with averaging disabled.
  Sparse matching checkpoints do not prove root cause or radiometric accuracy.
- iOS: test native-buffer vs DNG dimensions/crop, CFA and sample-unit mapping,
  including active-area width versus padded TIFF storage width. Compare retained
  accepted DNGs' independently decoded native sites with sidecar coordinates.
  Incorrect bit alignment, different units, and changed metadata must fail.
- iOS: test radial gain before 2x2 binning, signed sub-black noise, clipping
  before gain, unsupported/malformed opcodes and fractional moments. The Swift
  tests contain a Windows-generated Float32 payload/hash golden. Run both
  `swift test` and `swift test -c release`; the optimized suite also exercises
  two-frame 12 MP strip processing and reports elapsed time. The full device
  build and simulator UI tests are separate from physical camera verification.
- On a real iPhone, first request 2..4 frames with `keepOriginals:true`, and
  compare the exact contributing frames through the desktop original importer.
  Then test compact transfer, dark frames and cancellation. Mac fixtures cannot
  prove that an untested camera/lens returns a supported native buffer.

## Foreground Server And Minimal UI (0.4.0)

The following replaces the old tab/Remote ready workflow. Older sections are
historical regression coverage for the retained outbound transport only.

1. Open the app and grant permissions. Check portrait, both landscape sides,
   and direct 180-degree rotation: no scrolling/cropping/stretch, toolbar below
   in portrait and on the right in wide landscape. Border and bisectors stay
   one physical pixel and follow the actual fitted image, not letterboxing.
2. Open About: current version/build, licenses and update action are visible.
   Dismiss it and confirm the camera remains live and the app stays awake.
3. Discover `_oc-camera._tcp` from the desktop, connect without pairing, and
   verify fresh capabilities/current lens. Select another available physical
   lens; check new ranges/modes and cleared previous readback. Unknown lens IDs
   must fail. There must be no lens switch during capture/pending transfer.
4. Capture one frame, then four, and verify exact result/file lengths/hashes.
   Retry a truncated download and repeat a lost ACK: same bytes, no new photo.
   Invalid receipts never authorize deletion or mark a Windows job complete.
5. Confirm originals disappear after matching ACK. Stop/background/disconnect/
   idle expiry during a burst must cancel the writer before deleting staging.
   Foreground restart cannot replay the old ID. Preserve pre-update archives.
6. Exercise single-PC ownership, wrong Host/Origin, oversize requests, private
   interface binding, and failed network downloads. No browser cross-origin
   capture, arbitrary file access or public listening interface.
7. On physical Pixel/iPhone, compare preview against a labeled scene and check
   originals/preview JPEG have no overlay pixels. An emulator rotating its UI
   does not physically rotate its synthetic camera and cannot prove orientation.

Software evidence on 2026-11-12:

- Android debug/legacy APK builds and 31 tests in each OpenCapture debug/release
  variant passed. Standalone GitHub run `43263465037` also passed lint. Local lint
  is blocked by the installed SDK `37.0` parser; CI uses an isolated supported SDK.
- API 33 phone emulator screenshots cover portrait/landscape/About. A Camera2
  emulator capture transferred one DNG plus sidecar with exact hashes, repeated
  ACK and empty staging afterward. The final APK repeated this after background/
  resume. Repeated lifecycle cleanup after shutdown has a regression test.
- Final iOS run `43262707339` passed unsigned device compilation, 16 Swift core
  tests and portrait/both-landscapes/About/Stop navigation. Full-screen screenshots
  were inspected in both orientations, respecting their EXIF rotation. The
  simulator has no physical camera. Earlier runs `43258525053` and `43261828840`
  also passed, but their element-only landscape attachments were cropped.
- The merged generic Python controller suite passed 28 tests locally, including
  the Windows atomic-write sharing retry regression.

These are software checks, not physical Pixel/iPhone orientation, image quality,
network permissions or LAN discovery evidence. No release/feed update is implied
by committing source.

## Historical Android Preview Regression (2026-11-12)

The Pixel 10 Pro user reported a quarter-turn and off-screen preview in
landscape. The Android view now fits Camera2's already sensor-oriented texture,
compensates display rotation only, and observes display changes including
180-degree turns. The preview no longer belongs to the controls' ScrollView;
portrait uses stacked panes and wide landscape uses side-by-side panes.
Neither rotation nor layout changes original DNG bytes/coordinates.

The geometry tests model the platform sensor transform before applying our
matrix, checking labeled corners/interior points, all four sensor/display
angles, multiple stream sizes and short/wide viewports. Bounds-only tests of
an unrotated synthetic buffer did not cover the previous double rotation.

Local Android debug APK and legacy-app compilation passed; 27 tests passed in
each of the debug/release unit-test variants. Local lint is blocked by the
installed SDK's `37.0` version parsing error. The existing API 33 emulator
configuration lacks its system image, so no emulator screenshot pass is claimed.
The source has not been published or installed on the Pixel in this change.

On hardware, aim at upright text with all four frame edges visible. Verify
portrait, both landscapes, and a direct landscape-to-opposite-landscape turn.
Scroll the settings without moving the preview, show/hide the keyboard, resize
the app, switch tabs and enter/exit Remote ready. Confirm the complete aiming
image stays upright and visible; compare the PC's aiming JPEG. During a remote
job, rotation/tab changes must not recreate the camera, cancel a lease or
change original DNG orientation. Record real-device results separately from
the mathematical transform tests.

## Historical Remote Ready And Deletion (0.3.0)

Core/Android tests cover transient reconnect/backoff, stopping pending retries,
authentication/certificate rejection, archive path bounds, used-ID protection
after deletion, and keeping unconfirmed/opted-out originals. Confirmed cleanup
removes images and Android cached export ZIPs. iOS simulator navigation checks
the default retention setting and entering/showing/exiting the dim screen.

On each real phone, enable Remote ready with the selected PC, mount it and
operate existing Windows capture controls. Test a PC restart/Wi-Fi interruption,
End Remote ready during backoff, Home/lock during capture, foreground return,
and remembered mode after restarting the app. Confirm that no interrupted job
replays and no capture continues while backgrounded. Compare brightness before,
during and after the mode, including a Control Center/notification interruption.
Check complete uploads remove only the phone copy by default; local captures,
failed uploads and lost acknowledgements stay. Disable auto-delete and verify
retention, then test individual/bulk deletion and cancellation of confirmation.
Existing archives must remain after updating the app. These behaviors still
require physical iPhone/Pixel testing.

## Automated

- Android diagnostics v2: compare acquisition/pre-write/post-write/saved-DNG
  samples, retain native per-position statistics and fractional DNG black-level
  metadata, reject malformed diagnostic TIFF layouts, and never treat a flat
  dark scene as an automatic frame failure. Check actual platform-writer output
  in the emulator, then separately on physical Pixel hardware. See
  [RAW diagnostics](raw-diagnostics.md) for what matching samples do not prove.

- Android RAW diagnostics: padded row strides, unsigned RAW16 samples,
  read-only/nonzero-position buffers, sparse hashes, flat buffers and truncated
  input. Diagnostics must not mutate bytes, buffer state or capture decisions.
  On hardware, compare pre/post-DngCreator sample hashes and original DNG
  samples, plus request/start/result/image timing for normal and empty frames.
  Preserve sidecars even when a downstream consumer excludes an original.
  Logging is observability, not a claimed fix for a sensor/HAL failure.

- Python tests: validation, malformed/non-finite/duplicate JSON, authentication,
  cross-origin rejection, leased jobs, path traversal, bounds, hash mismatch,
  cancellation, expiry, complete manifests, duplicate/conflicting uploads,
  failure persistence, and IPA/feed consistency.
- Swift core tests: request roundtrip, malformed settings, unknown fields,
  URL restrictions, bare-IP/default-port normalization, optional local-network
  tokens, required public-host tokens, numeric version comparison, and optional
  apply-controls jobs.
- Remote controls: capability gating for old apps, settled-readback validation,
  cancellation, no photo uploads, and idempotent/immutable completion.
- ProRAW: separate quality-priority request contract; original DNG upload,
  manifest identity rejection, backward-compatible Bayer/JPEG completion.
- macOS build: Xcode device compile and unsigned IPA packaging. The packager
  refuses simulator products. Published IPA hash and permissions come from the
  actual build, not handwritten release metadata.
- Simulator checks: select Fast JPEG, rotate the camera screen into both
  landscape directions, and verify the full preview row and capture button
  fit inside the visible window. The preview scrolls with the camera form.
  Open the empty gallery and connection controls, rotate to landscape, and
  verify the controller field remains hittable and the optional token field is
  collapsed initially. Full-screen screenshots are retained
  for manual review. The script prefers iPhone 16 Pro on iOS 18.5, matching
  the current Xcode 16.4 runner's SDK, with another available iPhone as fallback.

These tests do not assert real camera quality, preview pixel orientation, or
exposure behavior. The simulator has no physical camera feed.

## Device observations

2026-11-11 source adds explicit Apple ProRAW. Python protocol/import fixtures
are software evidence only; Swift/Xcode compilation and real ProRAW delivery
remain pending. On the first device test, check advertised support, the actual
RAW pixel format, returned dimensions, per-frame declarations, native EXIF,
file size and sequential-burst stability. Compare identical manual controls
against Bayer and verify that neither mode silently falls back. Inspect both
the native originals and thumbnails separately. Test an unsupported lens and
confirm a clear error. High resolution does not establish measurement quality.

- 2026-11-07: the user installed the published build through AltStore and
  reached the live camera preview. They reported an upside-down live preview
  in landscape, not a saved-photo problem. The interface-orientation mapping
  had its landscape angles reversed; the fix uses 180 degrees for interface
  landscape-left and 0 degrees for interface landscape-right. Portrait angles
  and all photo/video output connections are unchanged. Confirm both
  landscape directions on the phone after updating; layout tests alone cannot
  validate the camera image.

## First phone test

For IP-only pairing, allow Local Network access, select an advertised controller,
and confirm capabilities/preview arrive with no token entered. Also test a bare
IP, an explicit alternate port, denied discovery permission, and a controller
that still requires a token. Validate discovery on the actual Wi-Fi network;
simulator layout and URL-policy tests do not prove multicast reachability.

1. Install with AltStore Classic. Confirm app launches, permissions are clear,
   and denied camera permission can be recovered through iOS Settings.
2. In local Camera mode, select the main physical camera. Confirm Bayer RAW is
   advertised. Capture one DNG and inspect original dimensions/EXIF/sidecar.
3. Capture fast JPEG and quality JPEG at matched manual controls. Inspect
   reported exposure for overrides. Compare native noise and spatial detail,
   not the processed preview or embedded RAW thumbnail.
   Also select **Apple ProRAW / highest resolution** on a supported camera.
   Check the advertised dimensions, then actual DNG dimensions and ProRAW
   identity flags. Test one frame, then four, and compare with individual Bayer
   originals. Verify that switching back still produces Bayer mosaic files.
   Test an unsupported lens/device: it must fail without changing formats.
   ProRAW fusion, high-resolution delivery and real storage/transfer costs
   require physical-phone evidence; neither simulator nor protocol tests prove them.
4. On a tripod, capture 4, 16, and 32 sequential frames. Confirm originals,
   metadata, disk use, memory stability, and no control changes within a burst.
5. Apply controls and copy current readings into manual fields; confirm focus
   and white balance remain fixed during matched capture sets.
6. Cancel mid-burst, deny Local Network permission, disconnect Wi-Fi during
   upload, and background the app. Verify errors are visible, partial files
   survive, and no overlapping captures occur on reconnect.
7. Connect to the reference controller on port 8766. Confirm preview, capture,
   cancellation, manifest, hashes, and desktop originals. Test a stale lease.
   Apply controls remotely, confirm the preview changes without a new photo,
   and compare the reported device readings with a subsequent photo's EXIF.
8. Rotate phone upright/upside-down/landscape. Record native file orientation
   and EXIF; verify the preview framing separately. Calibration must align the
   saved files, not assume preview orientation matches sensor coordinates.
   Use a subject with an obvious top (for example, printed text) and check both
   landscape directions, including rotating directly from one to the other.
9. Add the AltStore source, publish a higher build, check for updates, and
   install. Verify captures/settings survive. Export first as a precaution.

## Android Lens Shading (0.4.3, Build 10)

Local standalone APK compilation, lint, 80 debug and 80 release JVM tests passed
for this implementation. The generic controller has 36 passing tests. The
Android/Python shared fixtures produce identical moments and clipping; actual
Pixel map delivery, stability and optical effect still require a device test.
Release builds preserve the established Android signing key; CI keys are test-only.

Use the short physical test in [lens shading](lens-shading.md#physical-verification)
before relying on 0.4.3's corrected originals or `bayer-mean-v3` averages.
Synthetic Kotlin/Python golden moments and a real LibRaw decode of synthetic
DNG bytes cover arithmetic and interpretation, not the phone's factory map
accuracy. Keep the original DNGs for the initial parity test.

## Acceptance before calibration integration

Repeat fixed-geometry dark/baseline/synthetic-pattern runs at identical manual
settings. Quantify temporal noise, clipping, spatial repeatability, optical
resolution, and known perturbation recovery. Do not generate a correction map
from attractive-looking photos alone. Camera/lens artifacts remain distinct
from display errors. No phone camera mode removes that requirement.
