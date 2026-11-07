"""Render the project's geometric lens mark without external image dependencies."""
import json
import math
from pathlib import Path
import struct
import zlib

ROOT = Path(__file__).resolve().parents[1]


def png(size=1024):
    pixels = bytearray()
    for y in range(size):
        pixels.append(0)
        for x in range(size):
            dx, dy = (x + 0.5) / size - 0.5, (y + 0.5) / size - 0.5
            radius = math.hypot(dx, dy)
            color = (25, 31, 34)
            if 0.265 < radius < 0.335:
                color = (236, 246, 244)
            elif 0.15 < radius < 0.22:
                color = (33, 181, 158)
            elif radius <= 0.105:
                color = (236, 246, 244)
            if math.hypot(dx - 0.28, dy + 0.28) < 0.045:
                color = (245, 161, 70)
            pixels.extend(color)
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(pixels, 9)) + chunk(b"IEND", b"")


if __name__ == "__main__":
    directory = ROOT / "Resources" / "Assets.xcassets" / "AppIcon.appiconset"
    directory.mkdir(parents=True, exist_ok=True)
    (directory / "icon.png").write_bytes(png())
    (directory / "Contents.json").write_text(json.dumps({"images": [{"filename": "icon.png", "idiom": "universal", "platform": "ios", "size": "1024x1024"}], "info": {"author": "OpenCapture", "version": 1}}, indent=2) + "\n")
