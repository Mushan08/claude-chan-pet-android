#!/usr/bin/env python3
"""Add classes.dex to an APK produced by aapt2 link."""

from __future__ import annotations

import sys
import zipfile


def main() -> int:
    if len(sys.argv) != 4:
        print("usage: add_dex.py <base.apk> <classes.dex> <out.apk>", file=sys.stderr)
        return 2
    base, dex, out = sys.argv[1:]
    with zipfile.ZipFile(base, "r") as src, zipfile.ZipFile(out, "w") as dst:
        for item in src.infolist():
            if item.filename == "classes.dex":
                continue
            dst.writestr(item, src.read(item.filename))
        dst.write(dex, "classes.dex", compress_type=zipfile.ZIP_DEFLATED)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
