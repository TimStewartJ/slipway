#!/usr/bin/env python3
"""Draws a scout CSV (from `gradlew runFilm -PslipwayFilm=scout`) as a map and ranks scenic coast spots.

Usage: python tools/film/scout_map.py build/film/out/scout-<seed>.csv [--top 12]

A spot scores when, within 256 blocks, it has open ocean, a showpiece biome (cherry grove, meadow) and high peaks,
and is itself near the shore. Writes <csv>.png (biome colours shaded by height; candidates circled and numbered).
"""
import argparse
import csv
import math
import pathlib

import numpy as np
from PIL import Image, ImageDraw

COLOURS = {
    "ocean": (40, 80, 170), "deep_ocean": (20, 50, 130), "lukewarm_ocean": (40, 110, 190), "deep_lukewarm_ocean": (25, 80, 160),
    "warm_ocean": (60, 140, 210), "cold_ocean": (40, 70, 150), "deep_cold_ocean": (20, 45, 120), "frozen_ocean": (120, 140, 200),
    "deep_frozen_ocean": (90, 110, 180), "river": (60, 120, 220), "frozen_river": (140, 160, 230),
    "beach": (230, 215, 150), "snowy_beach": (235, 235, 220), "stony_shore": (140, 140, 140),
    "cherry_grove": (255, 150, 200), "meadow": (150, 220, 110), "jagged_peaks": (245, 245, 255), "frozen_peaks": (210, 230, 255),
    "stony_peaks": (170, 160, 150), "snowy_slopes": (225, 235, 245), "grove": (120, 170, 140),
    "plains": (120, 190, 80), "sunflower_plains": (170, 200, 60), "forest": (50, 130, 50), "birch_forest": (90, 160, 80),
    "dark_forest": (30, 80, 30), "flower_forest": (120, 170, 90), "taiga": (60, 110, 80), "savanna": (180, 170, 90),
    "desert": (220, 200, 120), "jungle": (40, 150, 40), "swamp": (70, 100, 60), "mangrove_swamp": (60, 110, 70),
    "windswept_hills": (120, 130, 120), "badlands": (200, 110, 60),
}
OCEAN = {k for k in COLOURS if "ocean" in k}
SHOW = {"cherry_grove", "meadow"}
PEAKS = {"jagged_peaks", "frozen_peaks", "stony_peaks", "snowy_slopes"}


def main():
    p = argparse.ArgumentParser()
    p.add_argument("csv")
    p.add_argument("--top", type=int, default=12)
    a = p.parse_args()
    rows = list(csv.DictReader(open(a.csv, encoding="utf-8")))
    xs = sorted({int(r["x"]) for r in rows})
    zs = sorted({int(r["z"]) for r in rows})
    step = xs[1] - xs[0]
    ix = {x: i for i, x in enumerate(xs)}
    iz = {z: i for i, z in enumerate(zs)}
    h = np.zeros((len(zs), len(xs)))
    biome = np.empty((len(zs), len(xs)), dtype=object)
    for r in rows:
        h[iz[int(r["z"])], ix[int(r["x"])]] = int(r["height"])
        biome[iz[int(r["z"])], ix[int(r["x"])]] = r["biome"]
    img = np.zeros((len(zs), len(xs), 3))
    for j in range(len(zs)):
        for i in range(len(xs)):
            c = np.array(COLOURS.get(biome[j, i], (255, 0, 255)), dtype=float)
            shade = 0.75 + 0.25 * np.clip((h[j, i] - 40) / 160, 0, 1.2)
            img[j, i] = np.clip(c * shade, 0, 255)
    r_cells = max(1, int(256 / step))
    is_ocean = np.vectorize(lambda b: b in OCEAN)(biome)
    is_show = np.vectorize(lambda b: b in SHOW)(biome)
    is_peak = np.vectorize(lambda b: b in PEAKS)(biome)
    cands = []
    for j in range(r_cells, len(zs) - r_cells, 2):
        for i in range(r_cells, len(xs) - r_cells, 2):
            if not is_ocean[j, i]:
                continue
            win = (slice(j - r_cells, j + r_cells + 1), slice(i - r_cells, i + r_cells + 1))
            near = (slice(j - 2, j + 3), slice(i - 2, i + 3))
            ocean = is_ocean[win].mean()
            show = is_show[win].mean()
            peak = is_peak[win].mean()
            land_near = 1 - is_ocean[near].mean()
            relief = h[win].max() - 63
            if ocean < 0.25 or land_near < 0.1 or show < 0.03:
                continue
            score = min(ocean, 0.5) + 2 * min(show, 0.3) + 2 * min(peak, 0.25) + min(relief, 140) / 140
            cands.append((score, xs[i], zs[j], ocean, show, peak, relief))
    cands.sort(reverse=True)
    picked = []
    for c in cands:
        if all(math.hypot(c[1] - q[1], c[2] - q[2]) > 400 for q in picked):
            picked.append(c)
        if len(picked) >= a.top:
            break
    scale = 4
    im = Image.fromarray(img.astype(np.uint8)).resize((len(xs) * scale, len(zs) * scale), Image.NEAREST)
    d = ImageDraw.Draw(im)
    ox, oz = ix.get(0), iz.get(0)
    if ox is not None and oz is not None:
        d.rectangle([ox * scale - 3, oz * scale - 3, ox * scale + 3, oz * scale + 3], outline=(255, 0, 0))
    for n, c in enumerate(picked, 1):
        px, pz = ix[c[1]] * scale, iz[c[2]] * scale
        rr = r_cells * scale
        d.ellipse([px - rr, pz - rr, px + rr, pz + rr], outline=(255, 255, 0), width=2)
        d.text((px + 4, pz + 4), str(n), fill=(255, 255, 0))
        print(f"{n:2d} score {c[0]:.2f} at x={c[1]} z={c[2]}: ocean {c[3]:.2f} show {c[4]:.2f} peaks {c[5]:.2f} relief {c[6]:.0f}")
    out = pathlib.Path(a.csv).with_suffix(".png")
    im.save(out)
    print(f"map {out} ({xs[0]}..{xs[-1]} x, {zs[0]}..{zs[-1]} z, {step} per pixel/{scale})")


if __name__ == "__main__":
    main()
