# Optional On-Device Bayer Averaging

Android source 0.4.1/build 7 adds opt-in `bayer-mean-v1`. Source may precede a
published release. iOS does not advertise this mode and must continue sending
individual originals. The phone UI remains a foreground camera server.

## Request

Use only when the selected camera advertises
`frameAveragingModes: ["bayer-mean-v1"]`. Omit the field for the unchanged
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

All policies exclude attempts without matching acquisition/prewrite/postwrite/
saved-DNG sample checkpoints. Matching sparse hashes do NOT establish useful
exposure, radiometric accuracy, lack of motion, or a driver root cause.
Clipping and ordinary signal drift are not silently trimmed away. Controllers
must still validate actual controls, clipping, geometry and measurement quality.

## Processing

Only uncompressed unsigned RAW16 RGB Bayer DNGs up to 13 MP are supported.
The reader is bounded and uses the DNG active area, 1x1/2x2 black levels and
scalar white level. Unsupported linearization, spatial black levels, non-square
default scale, radiometric opcodes or incompatible crops fail explicitly. A
validated optional common-plane WarpRectilinear rendering opcode is permitted
but NOT applied. Lens shading already present in RAW remains present.

Each native site is normalized as `(sample - black) / (white - black)`, preserving
negative values. There is no demosaic, tone curve, sharpening or integer
rounding. Four parity means remain available separately. Luma is the arithmetic
mean of a 2x2 block, not a calibrated photometric luminance measurement.
No registration or motion compensation is performed between frames; keep the
camera and scene fixed. Per-frame motion is not an automatic rejection reason.

After selection, originals are read from disk in strips. Float64 Welford
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

Hash/length verification, unchanged-byte retries and result-hash ACK semantics
are unchanged. Staging is removed only after ACK or session cancellation/end;
there is no permanent phone gallery. See [RAW diagnostics](raw-diagnostics.md)
for checkpoint interpretation and [camera server](camera-server.md) for LAN
restrictions and session ownership.
