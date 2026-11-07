# OpenCapture

A standalone iOS camera and remote capture instrument. No display-calibration
engine, account service, or cloud photo storage is embedded in the app.

## Capture modes

- **Bayer RAW:** standard sensor-mosaic DNG from a physical rear camera.
  ProRAW is disabled. Unsupported RAW fails explicitly, never falls back.
- **Fast JPEG burst:** sequential stills with AVFoundation speed priority.
  This is not a claim to reproduce Apple's Camera app burst pipeline or to
  disable all sharpening, denoising, or tone mapping.
- **Quality JPEG:** quality-prioritized stills for comparison. The pipeline
  may override requested exposure; inspect the recorded EXIF values.

Each mode preserves every original frame, native photo metadata, actual EXIF
exposure/ISO when present, observed lens position and white-balance gains,
dimensions, photo timestamps, and a capture manifest. Live preview and saved
thumbnails are processed aiming/inspection aids, not calibrated RAW views.

The first version uses up to 32 sequential frames and a photo configuration
around 12 MP. This is not high-speed video or a promised frame rate. RAW
dimensions are whatever the selected physical camera actually returns.
No automatic RAW averaging or measurement correction is performed yet.

## Try it on an iPhone

Requires iOS 17 or later. Intended first test device: iPhone 16 Pro.

1. Download `OpenCapture.ipa` from the repository's latest release.
2. Install through AltStore Classic, which signs the unsigned IPA for your
   Apple account. Initial AltStore installation uses AltServer on Windows.
3. Grant camera permission. Start in the **Camera** tab without a controller.
4. Choose a mode and frame count. Set manual controls or allow auto exposure,
   focus, and white balance to settle and lock for that burst.
5. **Apply controls** previews the chosen settings; **Use current camera
   settings** copies the device readings into manual fields for matched runs.
6. Capture, then inspect **Captures**. Export originals and metadata with the
   share button. Originals are also in Files > On My iPhone > OpenCapture.

Keep the app foregrounded. Going to the background cancels a run and stops the
camera. Partial files remain available. Cancellation waits for an in-flight
photo to return or hit its timeout; iOS has no general cancel-photo API.

## Compare near-black modes

Use the same tripod position, physical lens, screen stimulus, display mode,
and room lighting. Warm up the display. First find an exposure that does not
clip, copy the current settings to manual, and keep those settings across
modes. Capture 4-16 frames per mode. Compare both individual frames and the
actual exposure metadata, not just thumbnails. A quality JPEG can look
smoother while suppressing real spatial variation. Bayer RAW can look noisier
while preserving it. Neither is automatically the better measurement.

The native preview is not an exposure histogram for RAW. A proper linear RAW
inspection and averaging path is future work; originals are retained for it.

## Remote capture from Windows

The included controller requires Python 3.11+. No pip dependencies are needed.

Double-click `controller/start-controller.cmd` to start it on the trusted LAN
and open its browser control page. Allow a Windows firewall exception for
**Private networks only**, if prompted. The console shows a pairing token.
On the phone, enter `http://<PC-LAN-IP>:8766` and that token in **Connection**.
The phone will request Local Network permission. Click Connect.

The controller has preview, capture controls, cancellation, capabilities, and
job history. Captures are written under `captures/<request-id>/`. Nothing is
uploaded to GitHub. The CLI defaults to loopback; the double-click launcher
explicitly opts into LAN binding. Do not port-forward it or use it on public
Wi-Fi. Bearer authentication over HTTP is not encryption.

For developer use:

```powershell
python controller/server.py --listen 127.0.0.1 --port 8766
python scripts/make_icon.py
python -m unittest discover -s controller/tests -v
```

## Updates

Add this source to AltStore Classic once:

```text
https://github.com/aron7awol/OpenCapture/releases/latest/download/altstore.json
```

The app's Connection tab offers **Add AltStore source**, **Check for updates**,
and **Update in AltStore**. Installation is performed by AltStore, not by the
capture app itself. No Apple ID password or signing certificate is needed by
this repository or by GitHub Actions. Free-account signing refresh remains a
separate seven-day requirement. Maintain the same signing account/bundle ID
to preserve app data. Back up important captures before updating.

## Build

The GitHub **iOS** workflow runs Python and Swift core tests, then builds a real
arm64 device application using Xcode and packages an unsigned IPA. Normal
pushes produce an `OpenCapture-device` Actions artifact. To publish a reviewed
build to Releases and the AltStore feed, run the workflow manually with
`publish` selected. The feed hashes the exact IPA and reads permissions and
version from the built plist. Source archives at the same release tag provide
the corresponding GPLv3 source and build scripts.

On a Mac with Xcode:

```sh
bash scripts/build-ios.sh
```

The script downloads XcodeGen 2.46.0 with a pinned SHA-256, generates the Xcode
project, runs tests, and builds without signing. `BUILD_NUMBER` overrides the
build number. Build outputs are ignored. Windows cannot compile AVFoundation;
a successful Windows controller test is not an iOS compilation result.

## Scope and status

OpenCapture protocol 1 is **not** the UniformityComp Android v2 protocol.
They must not be pointed at each other's ports. A future adapter can translate
capture requests and preserve source formats, but no DNG is currently imported
into UniformityComp's still solver by this project. No calibration behavior is
changed by installing OpenCapture.

Physical-camera behavior, frame timing, image quality, and AltStore installation
must be validated on a real phone. Simulator and synthetic tests cannot prove
measurement performance. See [agent guide](AGENTS.md), [protocol](docs/protocol.md),
[architecture](docs/architecture.md), and [test plan](docs/test-plan.md).

## License

Copyright (C) 2026 aron7awol. This new OpenCapture source is licensed under
**GPL-3.0-only**; see [LICENSE](LICENSE). It comes with no warranty. This choice
does not change any other project's license. No Windows renderer, solver,
Android source, private captures, or private Git history belongs in this repo.

## Platform references

- [Apple: RAW and ProRAW](https://developer.apple.com/documentation/avfoundation/capturing-photos-in-raw-and-apple-proraw-formats)
- [Apple: photo quality priority](https://developer.apple.com/documentation/avfoundation/avcapturephotosettings/photoqualityprioritization)
- [AltStore: Windows setup](https://faq.altstore.io/altstore-classic/how-to-install-altstore-windows)
- [AltStore: updates](https://faq.altstore.io/developers/updating-apps)
- [AltStore: source format](https://faq.altstore.io/developers/make-a-source)
