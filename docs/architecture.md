# Architecture and Handoff

Optional [Bayer averaging](frame-averaging.md) is a generic capture operation,
not a calibration solver. Android `BayerAverage.kt` streams fractional moments
from staged DNGs (v1). iOS `Core/BayerDNG.swift` parses bounded normalization
metadata; `Core/BayerAverage.swift` selects attempts and streams moments from
native rasters with the embedded radial gain model (v2). Native-buffer/file
evidence lives in `Sources/RawCaptureDiagnostics.swift`. No compressed DNG pixel
decoder or private calibration math is embedded. Original-file mode remains
the default and retains each requested original unchanged.

## Boundaries

- `Core/CameraDiagnostics.swift`: bounded thread-safe iOS session evidence;
  active plus eight completed job snapshots, no pixels/context/userInfo,
  first fault retained independently of the ring. CameraService observes
  session/control events; CameraServerSession attaches optional snapshots to
  existing job states. No exposure policy or retry behavior changes.

- `Core/CameraServerSession.swift`: automatic single-PC reservation, bounded
  session/job state, replay protection, result-hash ACK and temporary-file cleanup.
- `Core/ExposurePipeline.swift`: ordered original-file consumers with at most
  two unfinished captures, exposure-completion signaling, nonblocking lookahead
  admission and cancellation drain. See [exposure overlap](exposure-overlap.md)
  for current source-only verification status on Android and iOS.
- `Sources/LANHTTPServer.swift`: SwiftNIO HTTP/1 server bound to the private
  Wi-Fi IPv4 interface. Bounded requests/connections; 256 KiB file reads on a
  dedicated file pool, with each byte-buffer socket write completing before
  the next read. Never send a FileRegion to the socket: iOS terminated build
  42 with SIGSYS at `sendfile`. FileRegion remains a disk-read range only.
- `Core/RemoteReconnect.swift`: legacy outbound retry policy, retained as a fixture.
- `Core/CaptureArchiveStore.swift`: archive creation/deletion, upload-acknowledgement
  checks and persistent ID replay protection after deletion.
- `android/` in the public repository: standalone Camera2 DNG server with the
  same foreground session/file contract, using NanoHTTPD 2.3.1 for HTTP.
  Its camera lifecycle contains no private measurement logic.
- Android `RawCaptureDiagnostics.kt`: bounded, pure-JVM sparse native-buffer and
  saved-DNG sampling with checkpoint comparison. It records evidence, not frame
  acceptance or normalization. See `docs/raw-diagnostics.md`.

- `Core/Protocol.swift`: Codable contract, bounded setting validation,
  controller URL policy, numeric release-version comparison. Foundation only.
- `Sources/CameraService.swift`: physical rear-camera discovery, AVFoundation
  session, verified manual-control reuse, at most two original-photo delegates, exact
  DNG/JPEG files, metadata sidecars, and throttled processed preview.
- `Sources/AppModel.swift`: foreground server lifecycle, Bonjour advertisement,
  remote lens/control routing, capture and cancellation into temporary storage.
  Capability-advertised remote apply-controls jobs settle and lock the camera
  without taking a photo; their result labels device readings separately.
- `Sources/ControllerDiscovery.swift`: legacy Bonjour browsing for
  `_opencapture._tcp`, protocol/auth TXT parsing, and private IPv4 resolution.
  Discovery lists controllers; the user selects which one to connect to.
- `Sources/Transport.swift`: legacy outbound pairing credentials, no redirects,
  ephemeral URLSession, disk-backed uploads, integrity receipts, Keychain.
- `Sources/UpdateService.swift`: public HTTPS AltStore feed; never downloads or
  executes code itself. Uses the original bundle ID because AltStore may
  rewrite the installed application's identifier.
- `Sources/OpenCaptureApp.swift`: bounded SwiftUI preview with one-pixel border
  and bisecting guides, portrait bottom/landscape side toolbar, Stop/Resume,
  and About/version/licenses/update. SF Symbols are native UI controls.
  Preview rotation uses the window's interface orientation: portrait 90,
  upside-down portrait 270, landscape-left 180, landscape-right 0 degrees.
  Interface landscape names are opposite to physical device-orientation names.
  This only rotates the aiming layer; capture outputs retain their fixed
  sensor-coordinate orientation and native image metadata.
- `controller/`: legacy Python stdlib push controller and contract fixtures.
  New desktop consumers need the pull transport in `docs/camera-server.md`.
- `scripts/`: deterministic icon renderer, device-IPA packager, macOS build.

## Capture decisions

Android 0.4.5/build 13 skips the manual-preview round trip
for explicitly identical fully manual captures in the same healthy camera,
RAW reader, preview surface and session. `ManualCaptureState.kt` owns exact
request keys, generation-based invalidation, actual-state comparisons and
per-run callback tags. Since Android 0.4.13/build 21, a verified controls-only
manual preview can also seed reuse, including continuous-preview mode;
automatic/partial requests and `applyControls` still obtain fresh setup
readings. Preview verification establishes controls, never RAW quality.
The requested scene wait (minimum 250 ms) remains, without waiting
for another long-exposure preview result. This is not scene-pixel stability detection.

Exposure, ISO, focus, both WB greens, color transform, crop, stabilization,
processing modes and physical-lens identity are checked against actual still
metadata. Every DNG uses its own timestamp-matched full result and shading map.
The unreleased November 20 fix retains separate actual preview and still
readbacks. For the first still compared with preview, a differing color matrix
is accepted only when it matches the explicitly submitted still matrix within
the unchanged 1e-6 tolerance; all other state fields must still match. Later
stills compare strictly with the preceding still. Exposure-only setup compares
preview with the retained preview reading, not with a still-template matrix.
Original metadata is not rewritten and no extra capture is added.
A reused-state mismatch terminates the job before publishing that frame,
invalidates verification and makes the next job prepare normally; there is no
hidden retry or extra attempt budget. Cancellation/errors drain the bounded
pipeline and replace the reader. Camera/session/reader/surface changes,
backgrounding and stale completions cannot restore verification. See
[Android setup diagnostics](raw-diagnostics.md#android-045-verified-manual-fast-path-development).
Physical speed and camera behavior of this fast path remain unverified.

Version 0.4.0 reverses the transport: the phone advertises `_oc-camera._tcp`,
the desktop selects it and reserves a foreground session, then pulls exact
result/original bytes. Exposure and image-domain contracts are unchanged.
There is no phone-side pairing or local gallery. The automatic ownership UUID
is not authentication; this is explicitly a trusted-LAN-only server.

The PC verifies original hashes, durably completes its local result, then ACKs
the exact result hash. Interrupted transfer retries reuse that ID and those
bytes. The phone deletes staging on ACK or after cancellation/session-end/
expiry stops the writer. Startup removes abandoned transient job directories,
but not legacy Documents archives. Used-ID markers remain outside staging.
Backgrounding closes the listener and waits for active work before cleanup.
Foreground restart does not revive an interrupted session or replay a photo.

One physical lens is selected, defaulting to the main wide camera. Virtual
multi-camera devices and automatic lens switching are not used. Standard
Bayer/JPEG photo dimensions are selected at or below 13 million pixels when
the active format advertises such a size; otherwise the smallest advertised
size is used. Actual RAW dimensions are taken from resolved photo settings.
Apple ProRAW is enabled before session startup when supported, and selected
only by its explicit pixel format. It requests the active configuration's
largest advertised dimensions, while Bayer/JPEG settings retain their former
limit. Both RAW formats are checked against the returned RAW pixel buffer.
ProRAW is labeled separately in requests, capabilities, manifests and history.
Its original lossless DNG contains demosaiced RGB with Apple processing and
possible image fusion; it must never be labeled as Bayer RAW.

Bayer RAW and fast JPEG use `.speed`; ProRAW and quality JPEG use `.quality` so
the user can evaluate its processing. This may cause the system to override
requested exposure; EXIF is retained and displayed. Exposure/focus/WB lock
is not proof of radiometric linearity or the absence of sensor processing.

Original frames are individual still photos with bounded exposure overlap;
they do not promise the stock camera's burst cadence. Averaging is explicit and
capability-gated; never substitute an average of displayed RAW thumbnails.

Metadata records exposure and ISO from photo EXIF when available, and labels
device exposure/ISO/focus/WB readings separately. Missing EXIF stays missing.
Sensor photo timestamps use the camera's CMTime clock; manifest start/end
timestamps are UTC wall time. Never subtract these different clock domains.

## Known limitations and next work

1. Validate physical capture, ATS/local networking, landscape preview
   orientation, and settings-lock behavior on an iPhone 16 Pro. AltStore
   installation and access to the live preview have been reported working.
2. Measure repeatability, clipped pixels, and known synthetic perturbations
   before using frames to propose correction.
3. Validate optional fractional-precision averaging on physical Android/iOS devices.
   Do not default to visually pleasing photo development.
4. Add a separate consumer adapter for calibration; native DNG is not the
   Android RAW plane stream or averaged-YUV payload.
5. The controller keeps active leases in memory. Restarted controllers retain
   original files but do not resume leases. Use a new request ID after restart.
6. One controller/phone at a time. Session expiry is 120 seconds without
   renewal. Downloads retry the same original receipts within that session;
   there is no persistent recovery gallery after it ends.
7. The desktop HTTP service is for a trusted LAN, not internet deployment.
   For broader use, add TLS pairing and a hardened production server.
8. No raw histogram, clipping mask, stitched captures, or correction-map UI is
   claimed. Aiming guides never alter captured originals or remote preview JPEGs.

## Ownership and provenance

The iOS app/controller were independently authored for OpenCapture. Android
reuses explicitly selected generic DNG, preview and APK-verification helpers.
No dogegen, private Windows renderer, measurement engine, calibration solver,
captures or private project history are included. The owner selected
GPL-3.0-only for iOS on 2026-11-07 and Android on 2026-11-11.
Android dependency notices are in `android/THIRD_PARTY_NOTICES.md` in the public
repository. The full license text is the
SPDX GPL-3.0-only text. iOS runtime dependencies are Apple platform frameworks
and SwiftNIO 2.105.0 with its Swift dependencies. Licenses/notices ship in
`Resources/ThirdPartyNotices.txt` and About. The legacy Python controller uses
the standard library. XcodeGen 2.46.0 is downloaded
only as a checksummed build tool. No analytics or third-party camera SDK.
