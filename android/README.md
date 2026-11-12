# OpenCapture for Android

Native Android companion to the iPhone app in this repository. It uses the
same generic `/oc/v1/` controller protocol, with Camera / Captures / Connection
tabs, manual camera controls, local archives, original-file export and nearby
controller discovery. Android captures 1–32 individual Bayer DNG files with
timestamp-matched Camera2 metadata. Preview is for aiming; captures are not
averaged or demosaiced by OpenCapture.

Android 9 or newer is required. The selected rear camera must advertise RAW,
manual sensor and manual post-processing support. Unsupported modes fail
explicitly. Normal RAW sizes favor the largest advertised size at or below
13 MP, falling back to the smallest at or below 60 MP. The separate
maximum-resolution sensor mode is not enabled. Actual sizes and controls
are recorded; phone camera behavior remains subject to device testing.

## Build

Install JDK 17 and Android SDK platform 36/build-tools 36.0.0, then from this
directory run:

```sh
./gradlew --no-daemon assembleDebug lint testDebugUnitTest testReleaseUnitTest
```

Windows uses `gradlew.bat`. Set `ANDROID_HOME` or create an ignored
`local.properties` containing `sdk.dir=...`. The Gradle wrapper verifies its
distribution checksum. The APK is `app/build/outputs/apk/debug/app-debug.apk`.
This standalone project does not require the original development workspace.

Install the APK, allow camera access, and start with one local frame, then four.
Export captures from **Captures**. For remote operation, run the repository's
reference controller as described in the [main README](../README.md), then
select it under **Connection**. Backgrounding cancels capture/network work;
completed originals survive cancellation. Uninstalling removes local archives.

## GitHub updates

Version 0.3.1 / build 5 source adds RAW-capture diagnostics to each hashed JSON
sidecar and `OpenCaptureRAW` logcat: request, shutter callback, result/image
arrival and DNG-write times, plus sparse RAW-buffer statistics before/after
serialization. Sensor timestamps and elapsed-realtime timestamps are labeled
separately. It preserves every original and does not filter or average photos.
Actual compile/release/install status must be checked separately; a source
version bump does not publish an update.

Version 0.3.0 / build 4 adds **Connection > Remote ready**. Enter/select the PC
and optional token, then enable it before mounting the phone. It remembers
that exact controller, reconnects after temporary network failures and uses a
dim black stay-awake screen. **Show controls** restores brightness without
disconnecting; **End Remote ready** stops reconnecting. Camera/network work
stops when the app is backgrounded or the phone is locked, and resumes when
you return to the open app. Credentials are encrypted with Android Keystore.

**Captures** supports individual and bulk deletion with confirmation. End
Remote ready/disconnect first. **Auto-delete uploaded remote captures** is on
by default: only a matching complete-upload acknowledgement allows removal.
Local captures, failed/unconfirmed uploads and existing archives are kept.
Turn the option off to retain future remote captures. Tiny used-ID markers
remain so a deleted job cannot be recaptured. Windows cannot browse old phone
archives; retained originals can still be exported manually.

Build 3 / version 0.2.1 checks the dedicated
[Android feed](https://github.com/aron7awol/OpenCapture/releases/download/android-updates/android.json).
No PC address, pairing token or PC connection is needed to check or download
updates. The feed points to a versioned release APK, and the app checks byte
count, SHA-256, package/version and continuity with its installed signing key.
GitHub HTTPS asset redirects are bounded and restricted to GitHub's download
hosts. Android's system installer always requires confirmation.

Install this APK once to migrate from build 1/2. Keep the same signing key
for subsequent releases. A different developer's debug build will not update
an existing installation signed with another key. CI builds use temporary
runner signing keys and are compile/test artifacts, not feed releases.

### Prepare a release

Build from the public source commit with the established local signing key.
The key stays on the release machine. Run from this directory:

```sh
python scripts/package_release.py --apk app/build/outputs/apk/debug/app-debug.apk --output dist
```

The script reads the APK's actual package/version/minimum SDK and verifies
its signature using the Android SDK. It creates the APK, `android.json`,
`release.json`, hashes, GPL license and these build instructions in `dist`.
Publish those files on the versioned `android-vVERSION-bBUILD` release, using
`--latest=false` so the iPhone AltStore feed keeps its own latest release.
After the versioned assets are downloadable, update only `android.json` on
the separate `android-updates` release, also with `--latest=false`.
Do not replace an existing versioned APK; increment the build number.

The owner licensed this Android OpenCapture source GPL-3.0-only, matching
the iPhone project. See [LICENSE](../LICENSE) and [third-party notices](THIRD_PARTY_NOTICES.md).
This license choice does not apply to unrelated private projects.
