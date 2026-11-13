# OpenCapture

A remote-controlled camera for iPhone and Android. Aim the phone, then control
the lens and capture settings from your computer.

## Install

- **iPhone:** iOS 17+. Install `OpenCapture.ipa` from
  [Releases](https://github.com/aron7awol/OpenCapture/releases) using AltStore Classic.
- **Android:** Android 9+ with a supported rear RAW camera. Install the Android
  APK from [Releases](https://github.com/aron7awol/OpenCapture/releases).

Development source can be newer than the published app. The workflow below
describes version 0.4.0 and requires a compatible desktop controller.

## Use

1. Put the phone and computer on the same **trusted private LAN**.
2. Open OpenCapture, allow camera/network access, and aim using the preview.
3. Select the phone in your desktop controller, connect, and choose the lens.
4. Set exposure, ISO, focus, white balance and frame count on the computer.

Keep the app open while capturing. Stop/Resume controls the camera server;
About shows version and updates. The toolbar moves to the side in landscape.
The thin border and center guides help aiming and are never recorded.

Original files are temporarily stored on the phone until verified receipt by
the computer. Failed transfers can retry during the session. Ending the session
clears temporary files; the computer keeps its received captures.

The phone server is unauthenticated. Use trusted networks only, never public
Wi-Fi or port forwarding.

## Formats

Android captures individual **Bayer RAW DNGs**. iPhone supports **Bayer RAW**,
**Apple ProRAW**, and **JPEG**, depending on the lens/device. ProRAW and JPEG
include Apple processing. Preview is for aiming, not calibrated measurement.
Bursts preserve 1..32 original frames plus metadata by default. Supported Android
cameras also offer optional [on-device averaging](docs/frame-averaging.md).

## Updates

Open **About > Check for updates**. Android uses its system installer; iPhone
updates are installed by AltStore. Add the [OpenCapture AltStore source](https://github.com/aron7awol/OpenCapture/releases/latest/download/altstore.json)
once. Keep the same signing identity when updating either app.

## Developers

[Build and release](docs/development.md) | [Android](android/README.md) |
[Server API](docs/camera-server.md) | [Architecture](docs/architecture.md) |
[Testing](docs/test-plan.md) | [Agent guide](AGENTS.md)

Copyright (C) 2026 aron7awol. **GPL-3.0-only**, without warranty.
See [LICENSE](LICENSE) and [third-party notices](Resources/ThirdPartyNotices.txt).
