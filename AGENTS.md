# OpenCapture Agent Guide

Read README.md, docs/architecture.md, docs/protocol.md, and docs/test-plan.md
before changing capture behavior or the network contract.

This is a generic iOS/Android camera/capture client, not a calibration engine. Keep
display patterns, stimuli, alignment, solvers, LUTs, and correction logic out
of this repository. `context` is opaque controller-owned string metadata.

## Measurement invariants

- Standard Bayer RAW is distinct from ProRAW. Never silently substitute.
- Fast JPEG is speed-prioritized, not promised unprocessed or identical to
  Apple's built-in burst mode. Quality JPEG may alter requested exposure.
- Preserve original DNG/JPEG bytes. Metadata distinguishes actual EXIF values
  from device readings at shutter time and requested settings.
- Auto controls settle and lock once per burst, not once per frame.
- Don't claim untouched sensor data, linear JPEG measurements, or calibrated
  accuracy. Preview and thumbnails are explicitly processed.
- Frame dimensions and support are device-dependent. Never assume 48 MP RAW.
- One photo is in flight at a time. Keep captures on disk rather than retaining
  all full-resolution frame buffers. Partial originals survive failures.
- Cancellation is cooperative; backgrounding cancels jobs. No background
  camera entitlement, hidden recording, or private iOS APIs.

## Verification

Windows: `python scripts/make_icon.py`, then
`python -m unittest discover -s controller/tests -v`.
Mac: `swift test`, then `bash scripts/build-ios.sh` for device compilation.
Android: read `android/README.md`, then from `android/` run
`./gradlew --no-daemon assembleDebug lint testDebugUnitTest testReleaseUnitTest`.
CI is `.github/workflows/ios.yml` and `.github/workflows/android.yml`.
Inspect the actual build before publishing.
Run `git diff --check`. Do not commit captures, tokens, provisioning material,
IPAs, generated Xcode projects, DerivedData, or tool downloads.

Document what was actually tested. Real iPhone image quality and installation
remain unverified until a user/device test demonstrates them.

## Security and releases

Credentials stay in Keychain on iOS. No tokens in query parameters or logs.
Capture requests cannot supply arbitrary upload URLs or filesystem paths.
Camera traffic never follows HTTP redirects. Public hosts require HTTPS;
private IPv4/.local HTTP is a clearly identified trusted-LAN pilot only.
Upload hashes protect integrity/idempotency, not authenticity over HTTP.
Public iOS updates use HTTPS and AltStore; do not build a self-installing IPA path.
Android updates use the dedicated GitHub `android-updates/android.json` feed,
verified APK hashes/package/version and installed signing-key continuity.
Do not send controller credentials to update hosts. Android requires system
installer confirmation. CI's temporary debug keys must never sign feed releases.

The owner chose GPL-3.0-only for iOS and the explicitly exported Android
OpenCapture sources. Android reuses selected generic camera, preview and APK
verification code. Do not copy additional private parent-project code/history
or assume its license changed. Publish release source, license and build
instructions alongside IPAs/APKs. Keep package and bundle IDs stable.
Android releases must use `--latest=false` to preserve iOS's latest-release
AltStore URL. XcodeGen is a build tool, not an embedded runtime library.
