# Build And Release

Read `AGENTS.md` before changing capture behavior. Source versions are not
release promises: distinguish compilation, simulator tests and physical-phone
evidence. The old Python push controller is retained as a regression fixture;
new desktop clients implement `camera-server.md`.

## iOS

On a Mac with Xcode, run `bash scripts/build-ios.sh`. It verifies the pinned
XcodeGen 2.46.0 download, runs Python/Swift tests, resolves SwiftNIO 2.105.0 and
builds an unsigned arm64 IPA. `BUILD_NUMBER` overrides the build number.
Run `bash scripts/simulator-test.sh` for UI tests and screenshots. Windows
cannot compile AVFoundation; controller tests on Windows do not verify iOS.

The GitHub iOS workflow runs both scripts. Ordinary pushes and source-only
validation builds produce artifacts, not releases. To release an approved
build, dispatch the workflow on `main` with `publish=true`. Otherwise leave it
false. Source-linked release archives include GPLv3 source and build scripts.
The AltStore feed hashes the exact IPA and derives version/permissions from
its plist. Do not supply Apple ID passwords, signing certificates or private
keys to this workflow. AltStore performs installation and signing refresh.

The stable iOS source is
`https://github.com/aron7awol/OpenCapture/releases/download/ios-updates/altstore.json`.
New builds and the README use it. Existing apps and AltStore subscriptions using
`releases/latest/download/altstore.json` remain supported; no user must switch
sources to keep receiving updates. Do not add a new source identity or change
the bundle identifier as part of this migration.

Future iOS tags use `vVERSION-ios.BUILD` (for example `v0.4.7-ios.46`). Old
`vVERSION-bBUILD` releases and assets remain immutable and downloadable. The
publisher first creates the versioned release with `--latest=false`, verifies
its public IPA/icon, updates the dedicated feed, then makes that iOS release
Latest and verifies the old feed address too. The publish job is serialized.
`python scripts/publish_ios_feed.py --feed dist/altstore.json` is a read-only
verification; `--publish --promote TAG` updates both paths. It rejects downgrades,
changed same-version metadata and detectable concurrent feed changes. GitHub
asset replacement is not transactional; do not run manual publishers concurrently.
A failed promotion can be retried with this script against the same immutable
versioned release. Do not recreate its IPA or change its hashes.

Keep Latest reserved for iOS while old clients depend on it. A separate feed
does not itself migrate installed clients or fix all historical release sorting.

## Android

See `android/README.md` for the standalone JDK/SDK build. GitHub Android CI
uses an isolated stable SDK to avoid unrelated installed preview platforms.
Run debug compilation, lint, and both debug/release JVM test variants. Retain
reports and record any environment blocker instead of claiming skipped tests.

Prepare releases locally with the established signing key, not a runner's
temporary debug key. From `android/`:

```sh
python scripts/package_release.py --apk app/build/outputs/apk/debug/app-debug.apk --output dist
```

The script verifies APK signature/package/version, then writes APK, update
manifest, release metadata, hashes, GPL license and build instructions.
Publish on a new `android-vVERSION-bBUILD` release with `--latest=false` so
iOS's latest-release AltStore source is preserved. Once those assets are
downloadable, replace only `android.json` on the separate `android-updates`
release, also with `--latest=false`. Never replace an existing versioned APK.

Android's legacy tag spelling is a compatibility contract: installed updaters
construct and require that exact APK URL. Do not change it to a cleaner-looking
tag without a separately tested compatibility migration. Platform labels belong
in release titles; preserve all historical tags and feed URLs. GitHub's listing
may therefore remain out of numeric order, especially 0.4.9 versus 0.4.10.

Android About checks this dedicated HTTPS feed and verifies byte count,
SHA-256, package/version and continuity with the installed signing key before
opening the system installer. Installation permission and confirmation remain
Android-owned. A different signing key cannot update an existing install.

Android signing compatibility: if the platform's `GET_SIGNING_CERTIFICATES`
lookup supplies no usable current signers, the verifier explicitly retries
`GET_SIGNATURES` for that package. Legacy results require exact nonempty signer
sets on both sides, cannot authorize rotation, and cannot override available
modern signing metadata or a package/version mismatch. Known modern rotation
history is rejected when either side needs legacy lookup. The legacy API can
expose an old rotation certificate; it is not proof of current-key lineage,
and Android's installer still performs the final update-signature enforcement.
Normal modern lookups keep the existing lineage-aware path. The release feed
hash is never a replacement for the installed app's signing identity.

Signing failures identify the installed app versus downloaded APK and include
Android API/device, package/build, lookup flags and signer counts in the error
dialog and `OpenCaptureUpdate` logcat. Successful fallback is logged too. No
private keys, APK contents, controller addresses or download tokens are logged.
These updater diagnostics are not camera sidecars or automatically uploaded to
the desktop. A phone stuck on an older verifier may need one manual installation
of the official APK over the existing app; do not uninstall to bypass a signing
error. Device-specific success must be confirmed on the affected phone.

## Contracts And Evidence

Run `python -m unittest discover -s controller/tests -v` for the retained
controller contract, `swift test` for Foundation core, and the Android tests.
Keep originals, captures, tokens, generated projects, build output, tool
downloads and signing material out of Git. Preserve original photo bytes and
separate requested controls from actual capture metadata. See `test-plan.md`
for physical-device checks that synthetic images cannot prove.

Runtime notices are bundled in About and source. Keep them current when
changing SwiftNIO/NanoHTTPD or their dependencies. XcodeGen/Gradle are build
tools, not a reason to omit notices for libraries actually linked into the app.
