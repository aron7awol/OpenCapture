"""Package an unsigned device app and generate a matching AltStore Classic feed."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import plistlib
import re
import shutil
import zipfile


def package(app, output, repository):
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("Invalid GitHub repository")
    with (app / "Info.plist").open("rb") as stream:
        info = plistlib.load(stream)
    if info.get("DTPlatformName") != "iphoneos":
        raise ValueError("Only a device app, not a simulator build, can become an IPA")
    version, build = info["CFBundleShortVersionString"], info["CFBundleVersion"]
    if not re.fullmatch(r"[0-9]+(?:\.[0-9]+){0,2}", version) or not re.fullmatch(r"[0-9]+", build):
        raise ValueError("Version and build must be numeric")
    if info["CFBundleIdentifier"] != "io.github.aron7awol.OpenCapture":
        raise ValueError("Unexpected bundle identifier")
    output.mkdir(parents=True, exist_ok=True)
    ipa = output / "OpenCapture.ipa"
    with zipfile.ZipFile(ipa, "w", zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(app.rglob("*")):
            if path.is_file():
                archive.write(path, "Payload/" + app.name + "/" + path.relative_to(app).as_posix())
    with ipa.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    tag = f"v{version}-b{build}"
    base = f"https://github.com/{repository}/releases/download/{tag}"
    app_entry = {"name": "OpenCapture", "bundleIdentifier": info["CFBundleIdentifier"],
        "developerName": "aron7awol", "subtitle": "Controlled still capture",
        "localizedDescription": "A remote-controlled camera for iPhone. Open the app on a trusted private LAN, then select the lens and capture settings from a compatible desktop controller. Supports Bayer RAW, Apple ProRAW and JPEG where available. Original files and metadata are kept temporarily for verified transfer to the computer. Experimental: real-device measurement accuracy is not established.",
        "iconURL": base + "/icon.png", "tintColor": "007F75", "category": "photo-video",
        "versions": [{"version": version, "buildVersion": build,
                      "date": datetime.now(timezone.utc).isoformat(), "downloadURL": base + "/OpenCapture.ipa",
                      "size": ipa.stat().st_size, "sha256": digest, "minOSVersion": info.get("MinimumOSVersion", "17.0"),
                      "localizedDescription": "Original bursts can overlap up to two unfinished photos. Verified unchanged manual controls can be reused, and optional Bayer averaging overlaps saved-frame inspection with the next exposure. A compatible desktop controller can omit unused photo thumbnails. Bayer RAW, Apple ProRAW and JPEG retain their original formats and receipts. Actual phone speed and image quality still need device testing; keep the app open on a trusted LAN."}],
        "appPermissions": {"entitlements": [], "privacy": {key: value for key, value in info.items() if key.startswith("NS") and key.endswith("UsageDescription")}}}
    source = {"name": "OpenCapture", "website": f"https://github.com/{repository}", "apps": [app_entry], "news": []}
    (output / "altstore.json").write_text(json.dumps(source, indent=2) + "\n", encoding="utf-8")
    (output / "release.json").write_text(json.dumps({"tag": tag, "version": version, "build": build, "sha256": digest}, indent=2) + "\n")
    (output / "SHA256SUMS.txt").write_text(f"{digest}  OpenCapture.ipa\n")
    root = Path(__file__).resolve().parents[1]
    shutil.copy2(root / "Resources/Assets.xcassets/AppIcon.appiconset/icon.png", output / "icon.png")
    shutil.copy2(root / "LICENSE", output / "LICENSE")
    return source


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--app", required=True, type=Path)
    parser.add_argument("--output", default=Path("dist"), type=Path)
    parser.add_argument("--repository", default="aron7awol/OpenCapture")
    args = parser.parse_args()
    package(args.app, args.output, args.repository)
