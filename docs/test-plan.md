# Verification and Device Test Plan

## Remote ready and deletion (0.3.0)

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

## Acceptance before calibration integration

Repeat fixed-geometry dark/baseline/synthetic-pattern runs at identical manual
settings. Quantify temporal noise, clipping, spatial repeatability, optical
resolution, and known perturbation recovery. Do not generate a correction map
from attractive-looking photos alone. Camera/lens artifacts remain distinct
from display errors. No phone camera mode removes that requirement.
