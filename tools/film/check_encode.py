#!/usr/bin/env python3
"""Checks and encodes a film shot rendered by `gradlew runFilm`.

Usage: python tools/film/check_encode.py <frames dir> [--fps 60] [--out clip.mp4]

- Cuts: frames.csv's cut column (1 on each shot's first frame) and --cuts declare deliberate cuts; motion is compared only
  within a shot.
- Smoothness: from frames.csv, every frame must advance the vessel and the camera by a step close to its neighbours'
  (no repeated frames, no jumps): the ratio of consecutive per-frame steps stays within [0.5, 2] wherever the motion
  is not near zero.
- Frames: none black, none identical to the previous one unless nothing moved (an intended hold).
- Writes <dir>/contact.jpg (every nth frame) and encodes an H.264 MP4 (yuv420p, CRF 16, +faststart).
"""
import argparse
import csv
import math
import pathlib
import subprocess
import sys

import numpy as np
from PIL import Image, ImageDraw


def steps(rows, keys):
    out = []
    for a, b in zip(rows, rows[1:]):
        out.append(math.sqrt(sum((float(b[k]) - float(a[k])) ** 2 for k in keys)))
    return out


def ratio_problems(name, values, floor, cuts=frozenset()):
    """values[i] is the step from frame i to i + 1. Pairs of steps that touch a declared cut are not compared, nor
    pairs whose steps differ by less than `floor` (a change of speed too small to see, e.g. starting from rest)."""
    problems = []
    for i, (a, b) in enumerate(zip(values, values[1:])):
        if i + 1 in cuts or i + 2 in cuts:
            continue
        if max(a, b) < floor or abs(b - a) < floor:
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
    p.add_argument("--cuts", default="", help="extra frame indices (comma-separated) that start a new shot")
    p.add_argument("--no-encode", action="store_true")
    a = p.parse_args()
    d = pathlib.Path(a.dir)
    rows = list(csv.DictReader((d / "frames.csv").open(encoding="utf-8")))
    # Declared cuts: frames.csv's `cut` column (1 on a shot's first frame) and --cuts. Motion is only compared within
    # a shot; a new shot may start anywhere.
    cuts = {int(r["frame"]) for r in rows if r.get("cut") == "1"} | {int(c) for c in a.cuts.split(",") if c.strip()}
    cuts.discard(0)
    problems = []
    if "vesX" in rows[0]:
        problems += ratio_problems("vessel", steps(rows, ["vesX", "vesY", "vesZ"]), 0.01, cuts)
    problems += ratio_problems("camera", steps(rows, ["camX", "camY", "camZ"]), 0.01, cuts)
    if "yaw" in rows[0]:
        # camera turning: yaw/pitch/roll change per frame (degrees; yaw wrapped)
        turn = []
        for r0, r1 in zip(rows, rows[1:]):
            dy = (float(r1["yaw"]) - float(r0["yaw"]) + 180) % 360 - 180
            turn.append(math.sqrt(dy ** 2 + (float(r1["pitch"]) - float(r0["pitch"])) ** 2 + ((float(r1["roll"]) - float(r0["roll"]) + 180) % 360 - 180) ** 2))
        problems += ratio_problems("camera turn", turn, 0.05, cuts)
    frames = sorted(d.glob("f*.png"))
    moving = [True] * len(frames)
    if "vesX" in rows[0]:
        ves = steps(rows, ["vesX", "vesY", "vesZ"])
        cam = steps(rows, ["camX", "camY", "camZ"])
        for i in range(1, min(len(frames), len(rows))):
            moving[i] = ves[i - 1] > 1e-4 or cam[i - 1] > 1e-4 or turn[i - 1] > 1e-3
    prev = None
    thumbs = []
    holds = 0
    for i, f in enumerate(frames):
        img = np.asarray(Image.open(f).convert("RGB"), dtype=np.int16)
        if img.mean() < 8:
            problems.append(f"frame {f.name} is black (mean {img.mean():.1f})")
        if prev is not None and prev.shape == img.shape and np.abs(img - prev).mean() < 0.05 and i not in cuts:
            if moving[i]:
                problems.append(f"frame {f.name} is identical to the previous frame though the camera or vessel moved")
            else:
                holds += 1
        prev = img
        if i % a.sheet_every == 0:
            t = Image.open(f).convert("RGB")
            t.thumbnail((360, 360))
            ImageDraw.Draw(t).text((4, 4), str(i), fill=(255, 255, 0))
            thumbs.append(t)
    if thumbs:
        cols = 6
        w, h = thumbs[0].size
        sheet = Image.new("RGB", (cols * w, math.ceil(len(thumbs) / cols) * h))
        for i, t in enumerate(thumbs):
            sheet.paste(t, ((i % cols) * w, (i // cols) * h))
        sheet.save(d / "contact.jpg", quality=85)
    millis = [int(r["millis"]) for r in rows]
    print(f"{len(frames)} frames, {len(rows)} log rows, {len(cuts)} declared cuts at {sorted(cuts)}, {holds} intended holds (nothing moved), "
          f"render {np.mean(millis):.0f} ms/frame (median {np.median(millis):.0f})")
    if len(frames) != len(rows):
        problems.append(f"{len(frames)} frames but {len(rows)} log rows")
    if not a.no_encode:
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
