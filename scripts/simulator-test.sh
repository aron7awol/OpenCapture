#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
DEVICE=$(xcrun simctl list devices available --json | python3 -c 'import json,sys; devices=json.load(sys.stdin)["devices"]; candidates=[(runtime,d) for runtime,group in devices.items() for d in group if "iPhone" in d["name"]]; candidates.sort(key=lambda item: ("iOS-18-5" not in item[0], item[1]["name"] != "iPhone 16 Pro")); print(candidates[0][1]["udid"])')
xcodebuild -project OpenCapture.xcodeproj -scheme OpenCapture -configuration Debug \
  -destination "platform=iOS Simulator,id=$DEVICE" -derivedDataPath build/Simulator \
  -resultBundlePath build/UITests.xcresult -parallel-testing-enabled NO \
  CODE_SIGNING_ALLOWED=NO test
xcrun xcresulttool export attachments --path build/UITests.xcresult --output-path dist/ui-screenshots
