#!/usr/bin/env python3
"""Generate the project's own circuit-gate icon without third-party artwork."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import struct
import sys
import zlib

ROOT = Path(__file__).resolve().parents[1]
NAVY = (20, 49, 74)
TEAL = (65, 207, 176)
WHITE = (245, 250, 252)


def color_at(x: float, y: float) -> tuple[int, int, int]:
    gate = 40 <= x <= 53 and 34 <= y <= 74
    gate = gate or (x >= 53 and ((x - 53) / 20) ** 2 + ((y - 54) / 20) ** 2 <= 1)
    wire = (22 <= x <= 40 and (abs(y - 42) <= 2 or abs(y - 66) <= 2))
    wire = wire or (73 <= x <= 86 and abs(y - 54) <= 2)
    terminal = any((x - cx) ** 2 + (y - cy) ** 2 <= 16 for cx, cy in ((22, 42), (22, 66), (86, 54)))
    return WHITE if gate else TEAL if wire or terminal else NAVY


def png(size: int) -> bytes:
    rows = bytearray()
    samples = ((0.25, 0.25), (0.75, 0.25), (0.25, 0.75), (0.75, 0.75))
    for y in range(size):
        rows.append(0)
        for x in range(size):
            colors = [color_at((x + dx) * 108 / size, (y + dy) * 108 / size) for dx, dy in samples]
            rows.extend(sum(color[channel] for color in colors) // len(colors) for channel in range(3))
    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(rows, 9)) + chunk(b"IEND", b"")


def generated_files() -> dict[str, bytes]:
    svg = '''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="1024" height="1024">
  <rect width="108" height="108" fill="#14314a"/>
  <path d="M22 42H40M22 66H40M73 54H86" fill="none" stroke="#41cfb0" stroke-width="4"/>
  <circle cx="22" cy="42" r="4" fill="#41cfb0"/>
  <circle cx="22" cy="66" r="4" fill="#41cfb0"/>
  <circle cx="86" cy="54" r="4" fill="#41cfb0"/>
  <path d="M40 34H53A20 20 0 0 1 53 74H40Z" fill="#f5fafc"/>
</svg>
'''
    foreground = '''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <path android:pathData="M22,42 H40 M22,66 H40 M73,54 H86" android:strokeColor="#41CFB0" android:strokeWidth="4" android:fillColor="@android:color/transparent"/>
    <path android:pathData="M18,42 a4,4 0,1 0,8 0 a4,4 0,1 0,-8 0 M18,66 a4,4 0,1 0,8 0 a4,4 0,1 0,-8 0 M82,54 a4,4 0,1 0,8 0 a4,4 0,1 0,-8 0" android:fillColor="#41CFB0"/>
    <path android:pathData="M40,34 H53 A20,20 0,0 1,53 74 H40 Z" android:fillColor="#F5FAFC"/>
</vector>
'''
    monochrome = foreground.replace("#41CFB0", "#FFFFFF").replace("#F5FAFC", "#FFFFFF")
    adaptive = '''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/app_icon_background"/>
    <foreground android:drawable="@drawable/app_icon_foreground"/>
%s</adaptive-icon>
'''
    files = {
        "artwork/app-icon.svg": svg.encode(),
        "androidApp/src/main/res/drawable/app_icon_foreground.xml": foreground.encode(),
        "androidApp/src/main/res/drawable/app_icon_monochrome.xml": monochrome.encode(),
        "androidApp/src/main/res/values/app_icon_colors.xml": b'<?xml version="1.0" encoding="utf-8"?>\n<resources><color name="app_icon_background">#14314A</color></resources>\n',
        "androidApp/src/main/res/mipmap-anydpi-v26/ic_launcher.xml": (adaptive % "").encode(),
        "androidApp/src/main/res/mipmap-anydpi-v33/ic_launcher.xml": (adaptive % '    <monochrome android:drawable="@drawable/app_icon_monochrome"/>\n').encode(),
        "iosApp/Assets.xcassets/Contents.json": b'{"info":{"author":"xcode","version":1}}\n',
        "iosApp/Assets.xcassets/AppIcon.appiconset/Contents.json": (json.dumps({"images": [{"filename": "AppIcon.png", "idiom": "universal", "platform": "ios", "size": "1024x1024"}], "info": {"author": "xcode", "version": 1}}, indent=2) + "\n").encode(),
        "iosApp/Assets.xcassets/AppIcon.appiconset/AppIcon.png": png(1024),
    }
    for bucket, size in (("mdpi",48),("hdpi",72),("xhdpi",96),("xxhdpi",144),("xxxhdpi",192)):
        files[f"androidApp/src/main/res/mipmap-{bucket}/ic_launcher.png"] = png(size)
    return files


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    options = parser.parse_args()
    stale = []
    for relative, contents in generated_files().items():
        path = ROOT / relative
        if options.check:
            if not path.is_file() or path.read_bytes() != contents:
                stale.append(relative)
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(contents)
    if stale:
        print("Generated application icons are stale: " + ", ".join(stale), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
