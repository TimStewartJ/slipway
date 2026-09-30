#!/usr/bin/env python3
"""Checks and encodes a film shot rendered by `gradlew runFilm`.

Usage: python tools/film/check_encode.py <frames dir> [--fps 60] [--out clip.mp4]

- Smoothness: from frames.csv, every frame must advance the vessel and the camera by a step close to its neighbours'
  (no repeated frames, no jumps): the ratio of consecutive per-frame steps stays within [0.5, 2] wherever the motion
  is not near zero.
- Frames: none black or identical to the previous one.
- Writes <dir>/contact.jpg (every nth frame) and encodes an H.264 MP4 (yuv420p, CRF 16, +faststart).
"""
import argparse
import csv
import math
import pathlib
import subprocess
import sys

import numpy as np
from PIL import Image


def steps(rows, keys):
    out = []
    for a, b in zip(rows, rows[1:]):
        out.append(math.sqrt(sum((float(b[k]) - float(a[k])) ** 2 for k in keys)))
    return out


def ratio_problems(name, values, floor):
    problems = []
    for i, (a, b) in enumerate(zip(values, values[1:])):
        if max(a, b) < floor:
            continue
        r = (b + 1e-9) / (a + 1e-9)
        if r < 0.5 or r > 2.0:
            problems.append(f"{name}: step ratio {r:.2f} between frames {i + 1} and {i + 2} ({a:.4f} -> {b:.4f})")
    return problems


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("dir")
    p.add_argument("--fps", type=int, default=60)
    p.add_argument("--out")
    p.add_argument("--sheet-every", type=int, default=30)
    a = p.parse_args()
    d = pathlib.Path(a.dir)
    rows = list(csv.DictReader((d / "frames.csv").open(encoding="utf-8")))
    problems = []
    if "vesX" in rows[0]:
        problems += ratio_problems("vessel", steps(rows, ["vesX", "vesY", "vesZ"]), 0.01)
    problems += ratio_problems("camera", steps(rows, ["camX", "camY", "camZ"]), 0.01)
    frames = sorted(d.glob("f*.png"))
    prev = None
    thumbs = []
    for i, f in enumerate(frames):
        img = np.asarray(Image.open(f).convert("RGB"), dtype=np.int16)
        if img.mean() < 8:
            problems.append(f"frame {f.name} is black (mean {img.mean():.1f})")
        if prev is not None and prev.shape == img.shape and np.abs(img - prev).mean() < 0.05:
            problems.append(f"frame {f.name} is identical to the previous frame")
        prev = img
        if i % a.sheet_every == 0:
            t = Image.open(f).convert("RGB")
            t.thumbnail((360, 360))
            thumbs.append(t)
    if thumbs:
        cols = 6
        w, h = thumbs[0].size
        sheet = Image.new("RGB", (cols * w, math.ceil(len(thumbs) / cols) * h))
        for i, t in enumerate(thumbs):
            sheet.paste(t, ((i % cols) * w, (i // cols) * h))
        sheet.save(d / "contact.jpg", quality=85)
    millis = [int(r["millis"]) for r in rows]
    print(f"{len(frames)} frames, {len(rows)} log rows, render {np.mean(millis):.0f} ms/frame (median {np.median(millis):.0f})")
    out = pathlib.Path(a.out) if a.out else d / "clip.mp4"
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-framerate", str(a.fps), "-i", str(d / "f%06d.png"),
                    "-c:v", "libx264", "-preset", "slow", "-crf", "16", "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(out)], check=True)
    print(f"encoded {out}")
    if problems:
        print(f"{len(problems)} problem(s):")
        for pr in problems[:40]:
            print("  " + pr)
        return 1
    print("smoothness and frame checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
