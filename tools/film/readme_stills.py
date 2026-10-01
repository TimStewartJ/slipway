#!/usr/bin/env python3
"""Picks stills for the repo README from a landscape render of the Reddit showcase.

Usage: python tools/film/readme_stills.py <frames dir> <out dir> [name=event+offset ...]

The render is a run of the `reddit` shot at 1600x900 (see FILM.md, "README stills"); each still is one raw frame (no
caption), given as an event from segments.json plus a frame offset. Stills are saved 1600 px wide as JPEG, with the
quality lowered until the file is under 500 KB.
"""
import io
import json
import pathlib
import sys

from PIL import Image

DEFAULT = ["galleon-over-the-bay=hook+0", "cargo-spill=cargo+240", "machine-wall=roll+314", "deck-farm=farm+146"]


def main() -> int:
    d = pathlib.Path(sys.argv[1])
    out = pathlib.Path(sys.argv[2])
    out.mkdir(parents=True, exist_ok=True)
    events = {e["name"]: e["frame"] for e in json.loads((d / "segments.json").read_text(encoding="utf-8"))["events"]}
    for spec in sys.argv[3:] or DEFAULT:
        name, _, where = spec.partition("=")
        event, _, offset = where.partition("+")
        if event not in events:
            print(f"{name}: no event '{event}' in this render, skipped")
            continue
        frame = events[event] + int(offset or 0)
        img = Image.open(d / f"f{frame:06d}.png").convert("RGB")
        if img.width != 1600:
            img = img.resize((1600, round(img.height * 1600 / img.width)), Image.LANCZOS)
        for quality in range(92, 40, -2):
            buf = io.BytesIO()
            img.save(buf, "JPEG", quality=quality, optimize=True, progressive=True)
            if buf.tell() < 500_000:
                break
        (out / f"{name}.jpg").write_bytes(buf.getvalue())
        print(f"{name}.jpg: frame {frame}, {img.width}x{img.height}, quality {quality}, {buf.tell() / 1000:.0f} KB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
