# OpenCapture Protocol 1

This contract is independent of any display calibration protocol. The phone
is the client; a user-selected controller leases capture jobs and receives
original files. All paths below are relative to that configured origin.
All calls except GET `/` require `Authorization: Bearer <pairing-token>`.
Tokens are never URL parameters. Versioned JSON has `protocolVersion: 1`.

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
the token-free UI; browser requests carry the entered token in a header.

## Request

See `protocol/request.schema.json` and `protocol/request.example.json`.
`exposureSeconds` and `iso` are either both omitted/null (auto then lock) or
both provided. Focus position is AVFoundation's normalized 0...1 lens
position, **not meters or diopters**. WB values are device gains, not Kelvin.
Omitted focus/WB means auto then lock where supported. Explicit unsupported
manual controls fail. `context` is opaque string-to-string data; its values
must not influence paths, commands, or camera settings.

Formats: `bayerRaw` or `jpeg`. Quality: `speed` or `quality`; Bayer RAW requires
speed. 1...32 frames, 0...10000 ms additional settling. Schema and runtime
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

Every original DNG/JPEG is saved unchanged using `fileDataRepresentation()`.
The corresponding JSON contains the native photo metadata, converted to JSON;
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
Completion is idempotent only for an identical manifest. Completed captures
are immutable. Error/cancel/expiry never erases already written originals.

## Security limits

The reference controller is deliberately single-device and trusted-LAN only.
It uses bounded bodies, deadlines, canonical paths, bearer authentication,
leased writes, idempotent hashes, and atomic persistence. HTTP does not protect
tokens or captures from observers on the LAN. SHA-256 is not a substitute for
TLS authentication. The app refuses public-host HTTP and all capture redirects.
Update feeds are fetched separately over HTTPS without pairing credentials.
