# OpenCapture For Android

Android 9+; a rear camera with RAW and manual-control support is required.
Install the APK from [Releases](https://github.com/aron7awol/OpenCapture/releases),
allow camera access, and leave the app open on your trusted private LAN.
Select the phone and lens from a compatible desktop controller.

The preview fits without scrolling. Stop/Resume and About are below it in
portrait and beside it in landscape. About includes version and updates.
Border and center guides are for aiming only and never enter the captures.

Captures contain 1..32 original Bayer DNG files and actual Camera2 metadata.
Files are temporary on the phone: the computer verifies receipt, then the phone
clears them. Failed downloads retry during the active session. Stop,
backgrounding, disconnect or expiry cancels work and clears remaining staging.
Old pre-update archives are left intact. The computer retains received originals.

## Build

Install JDK 17 and Android SDK platform 36/build-tools 36.0.0:

```sh
./gradlew --no-daemon assembleDebug lint testDebugUnitTest testReleaseUnitTest
```

Use `gradlew.bat` on Windows. Set `ANDROID_HOME` or `local.properties` with
`sdk.dir=...`. The APK is `app/build/outputs/apk/debug/app-debug.apk`.
Use the existing signing key for in-place updates. CI's temporary debug keys
are test-only; those APKs must not enter the public update feed.

## Details

The phone advertises `_oc-camera._tcp` on TCP 8767. It is an unauthenticated
private-LAN server, not suitable for public Wi-Fi or port forwarding.
Normal RAW sizes prefer the largest advertised size <=13 MP; separate
maximum-resolution sensor modes are not enabled. Support, actual dimensions
and controls are recorded per device. No averaging or demosaicing is performed.

[Usage](../README.md) | [Server API](../docs/camera-server.md) |
[Build/release guide](../docs/development.md) | [Testing](../docs/test-plan.md)

GPL-3.0-only: [LICENSE](../LICENSE), [third-party notices](THIRD_PARTY_NOTICES.md).
