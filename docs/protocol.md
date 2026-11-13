# OpenCapture Protocol 1

OpenCapture 0.4.0 uses the [foreground camera-server transport](camera-server.md).
Capture requests, results and original-file receipt schemas below are reused
unchanged. The outbound connection/endpoints in this document are the legacy
0.3.x transport, retained for controller compatibility and regression tests.
Do not confuse `_oc-camera._tcp` (phone server) with `_opencapture._tcp`
(legacy desktop controller).

Optional `streaming:{"mode":"originals-v1"}` is defined by
[the foreground original-streaming extension](original-streaming.md). It is
capture-only, capability-gated and mutually exclusive with phone averaging.
The legacy outbound transport does not perform streaming verdicts.

This contract is independent of any display calibration protocol. The phone
is the client; a user-selected controller leases capture jobs and receives
original files. All paths below are relative to that configured origin.
Controllers may offer IP-only pairing on a trusted private LAN. For these,
the client omits `Authorization`; job-scoped lease headers remain required.
Other controllers, including the supplied reference server, require
`Authorization: Bearer <pairing-token>` on all calls except GET `/`.
Tokens are never URL parameters. Public controller hosts require HTTPS and a
token. Versioned JSON has `protocolVersion: 1`.

## Connection and discovery

Remote ready changes client lifecycle only: the phone still connects outward
to its explicitly selected controller. Temporary network/408/425/429/5xx
failures retry at 1, 2, 4, 8 then 15 seconds; successful registration resets
the delay. Authentication, redirects, certificate and malformed-protocol
errors stop automatic retry. Backgrounding cancels active work and closes
requests; foreground return may re-register but never replay an old job.

A bare private IPv4 address or `.local` name is normalized to HTTP on port
8765. Explicit ports are preserved; an HTTPS URL without a port uses HTTPS's
default. Credentials, paths other than `/`, query strings, and fragments are
not accepted in controller addresses.

Controllers can advertise `_opencapture._tcp.local.` using Bonjour/DNS-SD.
The SRV port is the public controller port. TXT `protocol=1` identifies this
contract; TXT `auth=none` enables one-tap IP-only pairing. Any other or absent
`auth` value prompts for a token. No token or lease secret is broadcast.
The client resolves private IPv4 addresses and lists controllers for explicit
selection; discovering a service never initiates a capture. Local Network
permission is required. Manual IP entry remains the fallback.

## Endpoints

| Method and path | Purpose |
| --- | --- |
| POST `/oc/v1/capabilities` | Actual selected camera capabilities |
| GET `/oc/v1/lease` | Idle or one leased request |
| POST `/oc/v1/preview` | Aiming JPEG, at most 2 MiB |
| GET `/oc/v1/preview.jpg` | Most recent aiming JPEG |
| POST `/oc/v1/jobs/<id>/heartbeat` | Renew lease; returns `cancelled` |
| PUT `/oc/v1/jobs/<id>/files/<name>` | Stream one content-hashed original/sidecar |
| POST `/oc/v1/jobs/<id>/complete` | Verify all references, commit manifest |
| POST `/oc/v1/jobs/<id>/controls-complete` | Commit device readback for apply-controls jobs |
| POST `/oc/v1/jobs/<id>/failure` | Terminal `failed` or `cancelled` |
| POST `/oc/v1/jobs` | Operator queues a request |
| POST `/oc/v1/jobs/<id>/cancel` | Operator requests cancellation |
| GET `/oc/v1/state` | Operator camera/job status |

Job-scoped phone routes also require `X-OpenCapture-Lease`. IDs are lowercase
UUIDs, filenames are `frame-NNNN.dng`, `.jpg`, `.json`, or `.preview.jpg`.
No path, destination URL, shell command, or display instruction is supplied by
a capture request. Cross-origin browser requests are rejected. GET `/` serves
the reference server's token-free UI; its browser requests carry the entered
token in a header. A controller offering IP-only pairing must restrict that
mode to its intended local interface and reject foreign browser origins.

## Request

See `protocol/request.schema.json` and `protocol/request.example.json`.
`exposureSeconds` and `iso` are either both omitted/null (auto then lock) or
both provided. Focus position is normalized 0...1. For existing iOS clients it
is AVFoundation lens position, **not meters or diopters**. A client declaring
`focusModel: androidNormalizedDiopters` uses actual focus diopters divided by
its advertised `maximumFocusDiopters`: 0 is infinity and 1 is nearest focus
(fixed-focus cameras report 0). WB values are device gains, not Kelvin.
Omitted focus/WB means auto then lock where supported. Explicit unsupported
manual controls fail. `context` is opaque string-to-string data; its values
must not influence paths, commands, or camera settings.

Formats: `bayerRaw`, `appleProRaw` or `jpeg`. Only request formats in the
camera's capabilities. Quality: `speed` or `quality`; Bayer RAW requires
speed, Apple ProRAW requires quality. 1...32 frames, 0...10000 ms additional settling. Schema and runtime
validation bound numbers; the camera additionally enforces hardware limits.
Context limits are measured in UTF-8 bytes by the runtime (JSON Schema string
length alone is not sufficient for multibyte content).

Optional `operation` is `capture` (also the meaning of absent/null) or
`applyControls`. The latter requires `frameCount: 1` but takes no photo. It
uses the same settle/manual-control rules and returns a controls result, not
a capture manifest. Only submit it when camera capabilities advertise
`operations: ["capture", "applyControls"]`. Old capture requests are unchanged.
Capabilities also optionally include `appBuild` and an app-install-scoped
`deviceID` UUID; the latter is distinct from the physical `cameraID` and may
change after reinstalling the app.

Supported iOS cameras advertise `appleProRaw` plus optional
`proRAWMaxPhotoWidth`/`proRAWMaxPhotoHeight`. ProRAW requests those maximum
dimensions; these are a limit, not proof of the dimensions delivered.
`maxPhotoWidth`/`maxPhotoHeight` retain the standard Bayer/JPEG configuration.
No resolution is hard-coded as 48 MP. Older controllers that do not recognize
`appleProRaw` must be updated; Bayer/JPEG requests remain compatible.

Android clients additionally declare `platform: android`,
`actualControlsSource: camera2CaptureResult`, the focus fields above and
`whiteBalanceModel: rggbGreenMean`. The generic three-channel WB reading uses
the mean of the two native greens; both greens remain in the sidecar.
`rawSizes` optionally lists advertised normal RAW dimensions as `WxH` strings.
`rawLensShadingApplied` optionally records the camera's declaration that some
lens shading correction is already applied to RAW pixels by the device.
None of these additions change the numeric request schema or iOS behavior.

The controls-complete body contains exactly `protocolVersion`, `request`,
`camera`, `deviceExposureSeconds`, `deviceISO`, `lensPosition`, `whiteBalance`,
and `completedAt`. These are settled device readings, not photo EXIF. The
request must match the leased request. Cancellation/expiry and immutable
idempotent completion work as for captures. Control jobs cannot upload files.
`GET /oc/v1/state` includes the latest successful result as `controls`.

Idle: `{"protocolVersion":1}`. Active lease:
`{"protocolVersion":1,"request":{...},"leaseToken":"..."}`.
Heartbeats every 10 seconds extend the server deadline to 120 seconds from
receipt. The app cancels on a failed heartbeat, or on `cancelled: true`.
Leases are not automatically retried: use a new ID after expiry/failure.

## Files and metadata

On iOS every original DNG/JPEG is saved unchanged using
`fileDataRepresentation()`. Android writes each RAW image using `DngCreator`
with its timestamp-matched `TotalCaptureResult`, without averaging or
demosaicing. The corresponding JSON contains native photo metadata, converted to JSON;
binary fields become base64. A processed thumbnail may be saved separately.

Upload headers: `Content-Length`, `Content-Type`, `X-Content-SHA256`. Max file:
128 MiB; max job: 4 GiB. Uploads stream to a generated temporary path and are
atomically renamed only after hash verification and a final lease check.
Same-name/same-hash/same-size repeats succeed; changed content returns 409.
Receipt: `{"name":"...","bytes":123,"sha256":"...","mediaType":"..."}`.
This checks byte integrity, not full image decoding or physical correctness.

The completion manifest includes the request, capabilities, per-frame file
receipts, dimensions, timestamps, actual EXIF exposure/ISO if present, device
readings, start/end UTC timestamps, and processing description. Every image
and metadata sidecar must exist, and every uploaded file must be referenced.
Missing EXIF is null/omitted and must never be replaced by requested values.
ProRAW originals use `.dng`/`image/dng`, with `isBayerRAW: false` and the
required `isAppleProRAW: true`. For older Bayer/JPEG manifests, an absent
`isAppleProRAW` means false. Mismatched flags are rejected. The processing
description identifies Apple's demosaicing, computational processing and
possible multi-frame fusion. Original lossless file bytes are retained.
For an Android client declaring `actualControlsSource: camera2CaptureResult`,
actual exposure/ISO and timestamps come from that frame's matched Camera2
result instead of EXIF; native result fields remain in the hashed sidecar.
Android 0.3.1/build 5 adds optional `captureDiagnostics` in each sidecar:
request/sequence/frame identifiers, request/start-callback/result/image/write
times, and sparse RAW16 sample statistics/hashes before and after DngCreator.
`*ElapsedNs` uses elapsed realtime; `*SensorTimestampNs`/`imageTimestampNs`
uses the camera clock. Do not subtract different clocks without timestamp-source
evidence. The raw sampler reads all four sites in up to 96x64 evenly distributed
2x2 Bayer blocks, respecting buffer strides, without changing bytes or position.
The canonical sample hash uses low-byte-first unsigned 16-bit samples. Statistics
describe sparse native samples, not exposure accuracy or calibrated image quality.
Native metadata also includes frame number, timestamp source, pipeline depth,
rolling-shutter skew, dynamic black/white levels, and separately labeled physical
capture results when Camera2 supplies them (an empty object means unavailable).
These additive diagnostics do not change requests, original counts, bytes,
capture selection or the generic protocol version. The client still returns
every requested original, including suspicious ones; consumers own selection
and requests for additional jobs.
Android 0.4.1/build 7 adds `captureDiagnostics.diagnosticsVersion:2`: sparse
RAW samples at acquisition, pre/post-DngCreator and saved-DNG readback, plus
pairwise comparisons, per-Bayer-position statistics, stored DNG black/white
metadata and additional timing. Read [RAW diagnostics](raw-diagnostics.md)
for the bounded reader, interpretation and limitations. This is additive
sidecar evidence only. Separately, the optional capability-gated `averaging`
request field adds Android `bayer-mean-v1` and iOS `bayer-mean-v2`; see [frame averaging](frame-averaging.md) for
the strict request, derived payloads, usable-frame replacement and transfer
subset contract. Original-file requests remain unchanged. iOS 0.4.1 adds
diagnostics v3 for native-buffer/file-byte checks, distinct from Android's v2
decoded-DNG sample checks. In averaging
mode `frameCount` is the target usable count and manifest `frames` includes all
attempts, up to `2 * frameCount`.
Completion is idempotent only for an identical manifest. Completed captures
are immutable. Error/cancel/expiry never erases already written originals.

Successful `/complete` replies contain `protocolVersion:1`, `id:<job-id>` and
`status:"complete"`. Both phone clients validate this matching acknowledgement
before default-on automatic removal of remote originals. A missing/lost/invalid
reply retains the phone archive. Local captures are never automatically removed.
Manual deletion and confirmed-upload cleanup retain tiny used-ID records to
prevent recapturing an ID whose image archive was deleted. There is no endpoint
for a controller to enumerate or fetch older phone archives; manual export is
the recovery path. Windows originals are unaffected by phone deletion.

## Security limits

The reference controller is deliberately single-device and trusted-LAN only.
It uses bounded bodies, deadlines, canonical paths, bearer authentication,
leased writes, idempotent hashes, and atomic persistence. HTTP does not protect
tokens or captures from observers on the LAN. SHA-256 is not a substitute for
TLS authentication. The app refuses public-host HTTP and all capture redirects.
Update feeds are fetched separately over HTTPS without pairing credentials.
