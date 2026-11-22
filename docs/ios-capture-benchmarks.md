# Opt-in iOS RAW capture experiments

Development support for four independent comparisons against the unchanged
standard capture path. Capability advertisement is not physical verification.
No experiment is selected by default or persisted as a phone preference.

## Contract

The selected iOS camera advertises `captureExperiments`. Supply one advertised
string in the optional request field `captureExperiment`:

| Value | Single experimental change |
| --- | --- |
| omitted | Standard RAW-only photo requests, live preview outputs attached, bounded two-photo overlap |
| `ios-raw-no-preview` | Remove the video-data output and disable the aiming preview-layer connection for the job; restore both afterward |
| `ios-raw-bracket-pair` | Native manual RAW brackets of up to two independently saved photos with identical exposure/ISO |
| `ios-raw-prepared` | Prepare RAW photo resources explicitly before submission using `setPreparedPhotoSettingsArray` |
| `ios-bayer-only` | Disable ProRAW support, restrict output maximum dimensions to the existing Bayer dimensions, and set output maximum priority to speed |

Every experiment requires Bayer RAW, speed priority, explicit exposure **and**
ISO, focus and WB, `includePreview:false`, and no phone averaging. Controls-only,
automatic/partial, JPEG/ProRAW and mixed Android scheduling requests are rejected.
An unsupported bracket camera omits that mode; maximum bracket count is checked
again at submission. Normal camera exposure/ISO range validation still applies.
No substitute format, reduced Bayer resolution, arbitrary sleeps or private API.

Configurations are applied before fresh control preparation and restored after
writers drain. Experiment entry/exit invalidates manual reuse; do not interpret
configuration/setup overhead as exposure cadence. Prepared resources are cleared
at exit, including failures. Stop/background never restarts the camera as part
of restoration. A failed restoration is recorded and requires reconnection.
Preview may freeze temporarily while a test is running.

`originals-v1` works with all modes. A bracket reserves each exposure separately
within the unchanged two-unjudged bound and 2*N attempt limit. One available
permit or an odd tail uses a one-photo bracket, explicitly recorded in evidence.
The next bracket waits for the current bracket's processing/writers; originals
are published in index order. Batch mode returns exactly the requested number
of attempts. Stream mode retains controller-owned usable-frame selection.

Each bracket result is associated using settings ID, one-based bracket sequence,
photo count, expected count and its actual photo timestamp. Duplicate, stale,
wrong-format and inconsistent timestamp results fail. A bracket does not provide
our ordinary per-photo exposure callbacks: their timing fields remain null.
Device readings for these frames are labeled `processing_callback`; actual EXIF
exposure/ISO and each DNG's own metadata remain independent evidence. No group
callback or old setup result is invented as a new exposure result. Original
bytes and existing native-buffer/file checks are preserved.

## Evidence

Per-frame sidecars contain `captureExperiment` with mode, actual configured
preview/ProRAW/output-dimension/priority state, configuration duration, optional
resource-preparation duration, and actual bracket size/tail reason. Timing uses
`native-raw-bracket-v1` for brackets, existing policy otherwise. The bounded
job journal retains `experiment_configured`, `experiment_resources_prepared`,
`bracket_submitted`, `experiment_restored` or explicit restoration failure.
Download final job diagnostics **after cameraReady**, before ACK/session end.

## Portable camera/download benchmark

Python 3 standard library only; `controller/server.py` supplies the shared
request validation. This script connects directly to the phone. Disconnect the
Windows controller first; it will not seize another controller's reservation.
The phone must remain foreground, unlocked, on a trusted LAN and on a fixed
tripod. Set a stable, nonclipped scene before starting. No pattern or brightness
commands are issued by this script.

Obtain `/oc/v1/info`, select the exact camera ID, and create `manual-request.json`
from **fresh verified controls on this installed build**, not an old edited
baseline identity. Required fields are protocolVersion, id, format, quality,
frameCount, settleMilliseconds, context, exposureSeconds, iso, focusPosition and
whiteBalance. Use `format:"bayerRaw"`, `quality:"speed"`; omit experiment,
averaging, streaming and captureScheduling. A saved manifest may be supplied;
the script takes its request, not an inferred exposure from its image.

```powershell
python scripts/benchmark_ios.py --phone <private-ip>:8767 --camera <exact-camera-id> --request manual-request.json --output <new-archive-directory> --frames 6 --repeats 3
```

Start with `--frames 2 --repeats 1` to screen modes. Use `--modes standard
ios-raw-no-preview` to narrow a comparison. A full default run is 90 photos,
rotates mode order between repetitions, stops at the first failure and cancels
only its owned job. It never overwrites an output directory. It saves exact
originals, sidecars, request/manifest, final job state/diagnostics and summary.
All file lengths/hashes are verified before ACK. Camera identity, count, actual
controls, native integrity and sensor intervals are checked; malformed metadata
stops the benchmark. No camera request is made by `--help` or software tests.

**This harness uses batch transfer**, so captures finish before download. It
reports submission-through-cameraReady, download, total, phone preparation /
capture / packaging and per-frame sensor/callback timing separately. It does
not measure Windows import/solver time, does not replace decoded-RAW quality
checks, and cannot be compared directly with historical streamed timings.
The metadata pass is not proof of usable optical signal or physical accuracy.

For an end-to-end streamed benchmark, the desktop agent must propagate the
advertised field through its request validators/adapter, use the existing
`originals-v1` importer and unchanged acceptance checks, and retain exact N
usable originals within 2*N attempts. Do not call every frame accepted just
to measure speed. Measure camera, transfer, decode/inspection and final usable
completion separately, retaining overlaps rather than adding them twice.

For every mode check fresh actual EXIF/device controls, decoded RAW signal,
clipping, native dimensions/crop/CFA/black and white levels, shading metadata,
duplicates/timestamps and first-frame anomalies against same-build standard.
Separate first-job configuration costs from later captures. Run standard again
after each new mode and after cancellation/background/error recovery. Stop a
mode at its first fault, retain diagnostics, reconnect, then establish standard
capture again. Never relabel unsupported/failed modes as slower successes.

## Build and limitations

Run controller tests, Swift debug/release tests, unsigned device build and
simulator UI checks. macOS/Xcode is required for AVFoundation compilation.
Install the explicitly identified test IPA via AltStore; no update feed change
is needed. Actual speed, control fidelity, restore behavior and image quality
must be established on the physical phone. These experiments do not fix the
separately documented automatic-exposure transition failure.
