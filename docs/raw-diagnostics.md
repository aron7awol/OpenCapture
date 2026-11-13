# RAW Capture Diagnostics

iOS and Android have different native capture APIs and therefore different
diagnostic scopes. Do not relabel one platform's checks as the other's.

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
