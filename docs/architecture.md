# Architecture and Handoff

## Boundaries

- `Core/Protocol.swift`: Codable contract, bounded setting validation,
  controller URL policy, numeric release-version comparison. Foundation only.
- `Sources/CameraService.swift`: physical rear-camera discovery, AVFoundation
  session, once-per-run control locking, sequential still delegates, exact
  DNG/JPEG files, metadata sidecars, and throttled processed preview.
- `Sources/AppModel.swift`: foreground run lifecycle, disk archives, local
  controls, remote leasing, heartbeat/cancellation, original-file upload.
  Capability-advertised remote apply-controls jobs settle and lock the camera
  without taking a photo; their result labels device readings separately.
- `Sources/ControllerDiscovery.swift`: foreground Bonjour browsing for
  `_opencapture._tcp`, protocol/auth TXT parsing, and private IPv4 resolution.
  Discovery lists controllers; the user selects which one to connect to.
- `Sources/Transport.swift`: optional trusted-LAN pairing credentials, no redirects,
  ephemeral URLSession, disk-backed uploads, integrity receipts, Keychain.
- `Sources/UpdateService.swift`: public HTTPS AltStore feed; never downloads or
  executes code itself. Uses the original bundle ID because AltStore may
  rewrite the installed application's identifier.
- `Sources/OpenCaptureApp.swift`: SwiftUI camera, history, detail/export,
  connection, and update views. SF Symbols are native UI controls.
  Preview rotation uses the window's interface orientation: portrait 90,
  upside-down portrait 270, landscape-left 180, landscape-right 0 degrees.
  Interface landscape names are opposite to physical device-orientation names.
  This only rotates the aiming layer; capture outputs retain their fixed
  sensor-coordinate orientation and native image metadata.
- `controller/`: independent Python stdlib reference controller and browser
  UI. It is not the existing display renderer and does not run its solver.
- `scripts/`: deterministic icon renderer, device-IPA packager, macOS build.

## Capture decisions

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

Original frames are sequential still photos, not video-frame grabs, not a
guarantee of the stock camera's burst cadence. Averaging is deferred until
the correct RAW black-level and sample-domain treatment is implemented and
tested. Never substitute an average of displayed RAW thumbnails.

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
3. Add controlled RAW development/inspection and fractional-precision
   averaging. Do not default to visually pleasing photo development.
4. Add a separate consumer adapter for calibration; native DNG is not the
   Android RAW plane stream or averaged-YUV payload.
5. The controller keeps active leases in memory. Restarted controllers retain
   original files but do not resume leases. Use a new request ID after restart.
6. One controller/phone at a time. Heartbeats expire after 120 seconds without
   renewal. Upload retries reuse the same lease and content hashes; originals
   remain exportable locally after an unrecoverable upload failure.
7. The desktop HTTP service is for a trusted LAN, not internet deployment.
   For broader use, add TLS pairing and a hardened production server.
8. No raw histogram, clipping mask, stitched captures, or correction-map UI is
   claimed in this version. Quick Look may render DNGs with its own processing.

## Ownership and provenance

New OpenCapture files were independently authored for this app. No dogegen,
Windows/Android app, calibration solver, or capture files are included.
The owner selected GPL-3.0-only on 2026-11-07. The full license text is the
SPDX GPL-3.0-only text. Runtime dependencies are Apple platform frameworks;
the Python controller uses the standard library. XcodeGen 2.46.0 is downloaded
only as a checksummed build tool. No analytics or third-party camera SDK.
