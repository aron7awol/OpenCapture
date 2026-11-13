# Optional On-Device Bayer Averaging

Version 0.4.1 adds optional averaging on both platforms: Android advertises
`bayer-mean-v1`; supported iOS Bayer cameras advertise `bayer-mean-v2`.
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
iOS compares native samples before/after encoding and encoded/saved file bytes;
the desktop additionally compares retained accepted DNGs' decoded pixels with
the native samples. Matching sparse hashes do NOT establish useful
exposure, radiometric accuracy, lack of motion, or a driver root cause.
Clipping and ordinary signal drift are not silently trimmed away. Controllers
must still validate actual controls, clipping, geometry and measurement quality.

## Processing

Both modes support RGB Bayer up to 13 MP. Android v1 supports only uncompressed
unsigned RAW16 DNGs.
The reader is bounded and uses the DNG active area, 1x1/2x2 black levels and
scalar white level. Unsupported linearization, spatial black levels, non-square
default scale, radiometric opcodes or incompatible crops fail explicitly. A
validated optional common-plane WarpRectilinear rendering opcode is permitted
but NOT applied. Lens shading already present in RAW remains present.

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
