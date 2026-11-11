"""Prepare a locally signed, source-linked Android release. Never uploads or reads a signing key."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess


def package(apk, output, sdk):
    root = Path(__file__).resolve().parents[1]
    tools = sorted((sdk / "build-tools").iterdir(),
                   key=lambda p: tuple(map(int, p.name.split("."))) if re.fullmatch(r"\d+\.\d+\.\d+", p.name) else (0,))[-1]
    suffix = ".exe" if os.name == "nt" else ""
    aapt = tools / ("aapt" + suffix)
    signer = tools / ("apksigner.bat" if os.name == "nt" else "apksigner")
    badging = subprocess.check_output([str(aapt), "dump", "badging", str(apk)], text=True)
    match = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    if not match or match[1] != "com.uniformitycomp.opencapture":
        raise ValueError("Not an OpenCapture APK")
    version, build = match[3], int(match[2])
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version) or len(version) > 80 or not 1 <= build <= 2_100_000_000:
        raise ValueError("Invalid release version/build")
    minimum = re.search(r"^sdkVersion:'(\d+)'", badging, re.M)
    if not minimum or not 28 <= int(minimum[1]) <= 10000:
        raise ValueError("Invalid Android minimum SDK")
    signing = subprocess.check_output([str(signer), "verify", "--print-certs", str(apk)], text=True)
    certificate = re.search(r"^(?:Signer #1|V\d+ Signer): certificate SHA-256 digest:\s*([0-9a-f:]+)\s*$", signing, re.M | re.I)
    if not certificate:
        raise ValueError("APK signer verification failed")
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    if not 0 < apk.stat().st_size <= 128 * 1024 * 1024:
        raise ValueError("APK exceeds updater bounds")
    tag = f"android-v{version}-b{build}"
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
    feed = dict(schemaVersion=1, packageName=match[1], versionName=version,
                versionCode=build, minSdk=int(minimum[1]), size=apk.stat().st_size,
                sha256=digest, downloadUrl=f"https://github.com/aron7awol/OpenCapture/releases/download/{tag}/OpenCapture-android.apk")
    output.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(apk, output / "OpenCapture-android.apk")
    (output / "android.json").write_text(json.dumps(feed, indent=2) + "\n", encoding="utf-8")
    release = dict(tag=tag, sourceCommit=commit, sourceUrl=f"https://github.com/aron7awol/OpenCapture/tree/{commit}/android",
                   signerSha256=certificate[1].replace(":", "").lower(), **feed)
    (output / "release.json").write_text(json.dumps(release, indent=2) + "\n", encoding="utf-8")
    for source, target in ((root.parent / "LICENSE", "LICENSE"), (root / "README.md", "BUILDING.md"),
                           (root / "THIRD_PARTY_NOTICES.md", "THIRD_PARTY_NOTICES.md")):
        shutil.copyfile(source, output / target)
    names = ["OpenCapture-android.apk", "android.json", "release.json", "LICENSE", "BUILDING.md", "THIRD_PARTY_NOTICES.md"]
    (output / "SHA256SUMS.txt").write_text("".join(f"{hashlib.sha256((output / name).read_bytes()).hexdigest()}  {name}\n" for name in names), encoding="utf-8")
    print(json.dumps(release, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--sdk", type=Path, default=Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or Path.home() / "AppData/Local/Android/Sdk"))
    args = parser.parse_args()
    package(args.apk.resolve(), args.output.resolve(), args.sdk.resolve())
