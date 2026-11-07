# Verification and Device Test Plan

## Automated

- Python tests: validation, malformed/non-finite/duplicate JSON, authentication,
  cross-origin rejection, leased jobs, path traversal, bounds, hash mismatch,
  cancellation, expiry, complete manifests, duplicate/conflicting uploads,
  failure persistence, and IPA/feed consistency.
- Swift core tests: request roundtrip, malformed settings, unknown fields,
  URL restrictions, numeric version comparison.
- macOS build: Xcode device compile and unsigned IPA packaging. The packager
  refuses simulator products. Published IPA hash and permissions come from the
  actual build, not handwritten release metadata.

These tests do not assert real camera quality or exposure behavior.

## First phone test

1. Install with AltStore Classic. Confirm app launches, permissions are clear,
   and denied camera permission can be recovered through iOS Settings.
2. In local Camera mode, select the main physical camera. Confirm Bayer RAW is
   advertised. Capture one DNG and inspect original dimensions/EXIF/sidecar.
3. Capture fast JPEG and quality JPEG at matched manual controls. Inspect
   reported exposure for overrides. Compare native noise and spatial detail,
   not the processed preview or embedded RAW thumbnail.
4. On a tripod, capture 4, 16, and 32 sequential frames. Confirm originals,
   metadata, disk use, memory stability, and no control changes within a burst.
5. Apply controls and copy current readings into manual fields; confirm focus
   and white balance remain fixed during matched capture sets.
6. Cancel mid-burst, deny Local Network permission, disconnect Wi-Fi during
   upload, and background the app. Verify errors are visible, partial files
   survive, and no overlapping captures occur on reconnect.
7. Connect to the reference controller on port 8766. Confirm preview, capture,
   cancellation, manifest, hashes, and desktop originals. Test a stale lease.
8. Rotate phone upright/upside-down/landscape. Record native file orientation
   and EXIF; verify the preview framing separately. Calibration must align the
   saved files, not assume preview orientation matches sensor coordinates.
9. Add the AltStore source, publish a higher build, check for updates, and
   install. Verify captures/settings survive. Export first as a precaution.

## Acceptance before calibration integration

Repeat fixed-geometry dark/baseline/synthetic-pattern runs at identical manual
settings. Quantify temporal noise, clipping, spatial repeatability, optical
resolution, and known perturbation recovery. Do not generate a correction map
from attractive-looking photos alone. Camera/lens artifacts remain distinct
from display errors. No phone camera mode removes that requirement.
