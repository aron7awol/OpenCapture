#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
command -v xcodebuild >/dev/null || { echo "A Mac with Xcode is required. Use the GitHub Actions build from Windows."; exit 1; }
mkdir -p build/tools
curl --fail --location --retry 3 https://github.com/yonaskolb/XcodeGen/releases/download/2.46.0/xcodegen.zip -o build/xcodegen.zip
echo "4d9e34b62172d645eed6457cac13fc222569974098ef4ee9c3368bedf0196806  build/xcodegen.zip" | shasum -a 256 -c -
unzip -oq build/xcodegen.zip -d build/tools
XCODEGEN="$(find build/tools -type f -name xcodegen | head -1)"
test -n "$XCODEGEN"
chmod +x "$XCODEGEN"
python3 scripts/make_icon.py
python3 -m unittest discover -s controller/tests -v
swift test
swift test -c release
"$XCODEGEN" generate
xcodebuild -version
xcodebuild -project OpenCapture.xcodeproj -scheme OpenCapture -configuration Release \
  -destination 'generic/platform=iOS' -derivedDataPath build/DerivedData \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CURRENT_PROJECT_VERSION="${BUILD_NUMBER:-1}" build
python3 scripts/package_release.py --app build/DerivedData/Build/Products/Release-iphoneos/OpenCapture.app \
  --repository "${GITHUB_REPOSITORY:-aron7awol/OpenCapture}"
{
  xcodebuild -version
  swift --version
  "$XCODEGEN" --version
  git rev-parse HEAD
} > dist/build-info.txt
