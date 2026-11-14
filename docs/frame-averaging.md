# Optional On-Device Bayer Averaging

Published 0.4.1/0.4.2 Android apps advertise `bayer-mean-v1`; supported iOS
Bayer cameras advertise `bayer-mean-v2`. Android 0.4.3/build 10
advertises `bayer-mean-v3` with embedded DNG lens shading gains.
Source may precede a published release. The phone UI remains a foreground
camera server; the controller chooses averaging, not a phone-side menu.

## Request

Use only a mode the selected camera advertises in `frameAveragingModes`.
Omit the field for the unchanged
original-file path. It is invalid on `applyControls`, JPEG or ProRAW requests.

```json
"averaging": {
  "mode": "bayer-mean-v1",
  "emptyFramePolicy": "illuminated",
  "keepOriginals": false
}
```

`frameCount` (1..32) means **usable contributing frames**, not attempts.
The phone meters/locks once, captures one DNG at a time, closes its image, and
checks the saved samples. It takes replacements in that same locked session.
Android 0.4.4 and iOS 0.4.3 allow one saved-frame inspection to run
while the next photo is exposed. Publication and selection remain ordered,
with at most one inspection worker and one photo in flight. Before a possible
final contributing frame, the worker is drained to avoid an unnecessary tail
exposure. Cancellation joins the worker before staging cleanup.
Maximum total attempts are `2 * frameCount` (at most 64), including failed
attempts. For example, 4 requested means at most 8 attempts; 10 means 20.
Camera disconnects, unsupported formats, cancellation and I/O failures are
terminal errors, not permission for unlimited retries.

Policies:

- `illuminated`: reject a full-field, normalized 2x2-Bayer luma RMS below 0.001
  AND spatial standard deviation below 0.00025 AND zero native clipped samples.
  This is an explicit expected-signal assumption. Very weak legitimate images
  can fail this gate; choose appropriate exposure, not a looser hidden threshold.
- `dark`: never reject solely for low signal. Use for dark references.
- `relative`: reject that same absolute empty signature only when both RMS and
  spatial spread fall below one tenth of a credible brighter-frame reference
  within the burst. Initial low attempts can be reclassified when that reference
  arrives. An all-dark burst has no reference and is not certified nonempty.

All policies exclude attempts without the platform's matching integrity
checkpoints. Android compares acquisition/prewrite/postwrite/saved-DNG samples.
New Android captures also exclude `capture_timing_invalid`, independently of
signal level; see [timing integrity](raw-diagnostics.md#timing-integrity-and-stage-timings).
iOS compares native samples before/after encoding and encoded/saved file bytes;
the desktop additionally compares retained accepted DNGs' decoded pixels with
the native samples. Matching sparse hashes do NOT establish useful
exposure, radiometric accuracy, lack of motion, or a driver root cause.
Clipping and ordinary signal drift are not silently trimmed away. Controllers
must still validate actual controls, clipping, geometry and measurement quality.

## Processing

All modes support RGB Bayer up to 13 MP. Android v1/v3 support only uncompressed
unsigned RAW16 DNGs.
The reader is bounded and uses the DNG active area, 1x1/2x2 black levels and
scalar white level. Unsupported linearization, spatial black levels, non-square
default scale or incompatible crops fail explicitly. Android v1 rejects
radiometric opcodes; v3 supports the bounded DngCreator GainMaps described below. A
validated optional common-plane WarpRectilinear rendering opcode is permitted
but NOT applied. Lens shading already present in RAW remains present.

Android v3 applies four embedded DNG OpcodeList2 `GainMap` operations at native
Bayer sites after signed black normalization and before binning. The actual
Camera2 map must match the embedded map; its values describe the remaining
correction, including when hardware already shades RAW. Native clipping is
measured before gains. All contributing DNG gain maps/normalization must agree
within the burst. See [lens shading](lens-shading.md) for coordinates, bounds,
actual metadata and physical verification requirements.

iOS v2 reads the native `AVCapturePhoto.pixelBuffer` (supported 14-bit Bayer
formats in unsigned little-endian 16-bit storage). It preserves original
`fileDataRepresentation()` DNG bytes, including lossless-JPEG compressed DNGs.
Only bounded TIFF metadata is parsed on the phone; compressed pixels are NOT
decoded there. A tightly packed active-area raster is staged privately for
strip processing and is never a transferable file. Native-buffer dimensions
can be either raw TIFF storage dimensions or active-area dimensions. The
explicit `bufferOrigin` identifies the mapping to the stored mosaic.

iOS supports the embedded DNG `FixVignetteRadial` model: evaluate a common
scalar gain at each native site, multiply the signed black-normalized Float32
value, then bin. The descriptor records every radial opcode's ID/version/flags,
five coefficients, and center. Unknown radiometric processing fails closed;
an optional, sole common-plane geometric warp may be recorded but is not
applied. This is camera-authored metadata, NOT a measured flat-field calibration
or a claim that all lens artifacts are removed. Native clipping is assessed
before radial gain. A gain-corrected value above 1 is not itself saturation.

Each native site is normalized as `(sample - black) / (white - black)`, preserving
negative values. There is no demosaic, tone curve, sharpening or integer
rounding. Four parity means remain available separately. Luma is the arithmetic
mean of a 2x2 block, not a calibrated photometric luminance measurement.
No registration or motion compensation is performed between frames; keep the
camera and scene fixed. Per-frame motion is not an automatic rejection reason.

After selection, staged DNGs (Android) or native rasters (iOS) are read in strips. Float64 Welford
accumulation preserves fractional means and sample variance of per-frame luma,
including the covariance between Bayer sites. Working image memory is bounded
independently of frame count. Temporary disk usage still scales with attempts.
The optimized implementation opens each contributing raster once (at most 32),
reuses its validated layout and computes a gain strip once for all contributors.
It retains the same Float64 interpolation, Float32 gain/normalization rounding,
Welford order, signed noise and native clipping rules. It never retains a full
burst of decoded image arrays in memory.
This mode primarily reduces transfer volume for larger bursts; short bursts
can transfer as much or more than originals due to diagnostic/reference overhead.

## Result And Transfer

The v1 manifest gains an `aggregation` object. `frames` contains **all attempts**,
sequentially numbered, with original receipts and full actual-control records.
Every frame's hashed JSON sidecar includes `averagingSelection`: policy,
accepted/reason, full-field RMS/mean/spatialStd/clippedFraction, integrity
assessment and normalization descriptor. The final selection is written and
hashed before the manifest is published.

`aggregation` contains:

- `version:1`, `mode:"bayer-mean-v1"`, `status:"complete"|"insufficient"`;
- `targetFrames`, `attemptedFrames`, sorted `acceptedIndices`;
- sorted `rejected:[{index,reason}]`, reasons `empty_signal` or
  `capture_integrity_not_verified`;
- `referenceIndex`: first accepted attempt, or 1 if none;
- sorted `retainedOriginalIndices`: reference plus every rejected DNG, or all
  attempts when `keepOriginals:true`;
- `domain:"black_subtracted_bayer_site_relative_signal"`, native `rawWidth`,
  `rawHeight`, binned `width`, `height`, `activeArea:[top,left,bottom,right]`,
  parity-ordered `blackLevels`, scalar `whiteLevel`, active-origin `cfa` RGB
  indices (0=R, 1=G, 2=B);
- `files` with the receipts below on success, otherwise an empty object.

For iOS replace the version/mode with `2`/`bayer-mean-v2`, use domain
`black_subtracted_dng_corrected_bayer_site_relative_signal`, and add
`radialGain:[{id:3,version:"1.3.0.0",flags:0,coefficients:[...5],center:[...2]}]`
(an empty list means no embedded radial gain). The binary layout is identical.
The descriptor/sidecar normalization must agree, including the gain model.
Phone-native source values are bounded to [-1,2], radial gains to [0.125,16].
v2 payload bounds are consequently [-16,32] for means and [0,1152] for variance;
consumers may impose stricter downstream measurement bounds. Protocol version
remains 1. A v1-only consumer must refuse v2 rather than silently ignore gain.

For Android v3 use `version:3`, `mode:bayer-mean-v3`, the v2 corrected domain and
binary payload/bounds, and replace `radialGain` with `gainMaps`. Each entry has
exactly `id:9`, `version:"1.3.0.0"`, integer `flags` 0..3,
`area:[top,left,bottom,right]`, `plane:0`, `planes:1`, `pitch:[2,2]`,
`points:[rows,columns]`, `spacing:[vertical,horizontal]`, `origin:[0,0]`,
`mapPlanes:1`, and row-major `gains`. There are zero or four entries; the
[bounded layout](lens-shading.md#bounded-processing) is mandatory. v3 uses the
same Android checkpoint diagnostics as v1, not iOS native-buffer diagnostics.
v3 does not change protocolVersion or diagnostic version. Older controllers
must refuse an unrecognized mode rather than interpret it as v1.

The accepted/rejected lists must partition all attempts. A complete result has
exactly the requested accepted count. An insufficient result has exhausted the
attempt budget and **has no averaged measurement payload**. It still completes
the transport so the controller can save the evidence before acknowledging;
transport completion is not measurement success.

Payloads, both `application/octet-stream`, with SHA-256/byte-length receipts:

| File | Layout |
| --- | --- |
| `average.f32` | row-major, six little-endian float32 values per binned pixel: top-left, top-right, bottom-left, bottom-right site means, luma mean, luma sample variance |
| `average-clips.u8` | row-major uint8 count of contributing frames with any site >=0.995 at that block |

Variance uses denominator N-1 (zero for N=1); clipping retains native saturation
even if the averaged pixel is not saturated. These are derived data, not DNGs.
The luma plane is stored explicitly to preserve the original per-frame float32
2x2 sum and its temporal moments. Site and luma means agree to float roundoff.

Download every sidecar, the declared retained DNGs, optional previews, and the
two payloads. An unretained original receipt is provenance only: the desktop
has NOT independently checked those original bytes. The reference DNG allows
independent crop/CFA/black/white/opcode interpretation checks. It does not prove
the average equals all unavailable originals. For parity/reprocessing tests,
use `keepOriginals:true` or original-file mode.

For iOS, the desktop independently checks the retained accepted DNG's raw
dimensions, crop, CFA, black/white levels, gain model, and sampled native units.
Absent originals cannot receive that independent pixel check. Device buffer
packing or metadata differences therefore fail closed instead of silently
producing a differently normalized measurement. Test a short keep-originals
burst on each physical camera before depending on compact transfer.

Hash/length verification, unchanged-byte retries and result-hash ACK semantics
are unchanged. Staging for a completed result is removed after ACK, cancellation
or session end. Terminal capture/format/I/O exceptions instead fail the job and
clean its staging, unlike a transport-complete `insufficient` result;
there is no permanent phone gallery. See [RAW diagnostics](raw-diagnostics.md)
for checkpoint interpretation and [camera server](camera-server.md) for LAN
restrictions and session ownership.
