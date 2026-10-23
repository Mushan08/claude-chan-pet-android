#!/usr/bin/env python3
"""Extract the portable JDK / Android SDK zips used by tools/build-apk.ps1."""

from __future__ import annotations

import sys
import zipfile
from pathlib import Path


ROOT = Path(r"E:\android-toolchain")
SDK = ROOT / "sdk"

JOBS = [
    ("jdk17.zip", ROOT / "jdk-17", "jdk-17.0.2/"),
    ("build-tools.zip", SDK / "build-tools" / "35.0.0", "android-15/"),
    ("platform-35.zip", SDK / "platforms" / "android-35", "android-35/"),
    ("platform-tools.zip", SDK / "platform-tools", "platform-tools/"),
    ("gradle-8.9-bin.zip", ROOT / "gradle-8.9", "gradle-8.9/"),
    ("emulator.zip", SDK / "emulator", "emulator/"),
    ("sysimg-35.zip", SDK / "system-images" / "android-35" / "default" / "x86_64", "x86_64/"),
    ("cmdline-tools.zip", SDK / "cmdline-tools" / "latest", "cmdline-tools/"),
]


def extract(zip_path: Path, dest: Path, prefix: str) -> int:
    dest.mkdir(parents=True, exist_ok=True)
    count = 0
    with zipfile.ZipFile(zip_path) as z:
        for info in z.infolist():
            if not info.filename.startswith(prefix):
                continue
            rel = info.filename[len(prefix):]
            if not rel:
                continue
            target = dest / rel
            if info.is_dir():
                target.mkdir(parents=True, exist_ok=True)
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            with z.open(info) as src, open(target, "wb") as out:
                while True:
                    chunk = src.read(1024 * 1024)
                    if not chunk:
                        break
                    out.write(chunk)
            count += 1
    return count


def main() -> int:
    for name, dest, prefix in JOBS:
        src = ROOT / name
        if not src.exists():
            print(f"missing {src}", file=sys.stderr)
            return 1
        try:
            n = extract(src, dest, prefix)
            print(f"{name} -> {dest} ({n} files)")
        except PermissionError as err:
            print(f"{name}: skipped locked file ({err.filename})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
