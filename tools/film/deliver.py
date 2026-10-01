#!/usr/bin/env python3
"""Turns a finished render of the Reddit showcase into the delivery folder.

Usage: python tools/film/deliver.py <frames dir> <delivery dir> [--name slipway-4x5] [--thumb spill+40] [--back-seconds 2.4]

Runs the frame checks (check_encode.py; a failure stops the delivery), assembles the captioned video (assemble.py),
and writes next to it: the caption file, segments.json, the frame sheet of the raw render, a sheet with one frame per
second of the final video, and a thumbnail (a raw frame without caption, given as <event>+<frames> or a frame number).
Then verifies the encode with ffprobe (H.264 High, yuv420p, 60 fps, no audio, under 100 MB) and compares the first and
the last frame of the render (the loop point).
"""
import argparse
import json
import pathlib
import shutil
import subprocess
import sys

import numpy as np
from PIL import Image

HERE = pathlib.Path(__file__).resolve().parent


def run(*cmd):
    print("> " + " ".join(str(c) for c in cmd), flush=True)
    return subprocess.run([str(c) for c in cmd], check=False).returncode


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("dir")
    p.add_argument("out")
    p.add_argument("--name", default="slipway-4x5")
    p.add_argument("--thumb", default="spill+40")
    p.add_argument("--back-seconds", default="2.4")
    p.add_argument("--crf", default="17")
    p.add_argument("--roll-caption", default=None)
    a = p.parse_args()
    d = pathlib.Path(a.dir)
    out = pathlib.Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    suffix = a.name.split("-", 1)[1] if "-" in a.name else a.name

    if run(sys.executable, HERE / "check_encode.py", d, "--no-encode") != 0:
        print("frame checks failed; nothing delivered")
        return 1
    video = out / f"{a.name}.mp4"
    assemble = [sys.executable, HERE / "assemble.py", d, "--out", video, "--back-seconds", a.back_seconds, "--crf", a.crf]
    if a.roll_caption:
        assemble += ["--roll-caption", a.roll_caption]
    if run(*assemble) != 0:
        return 1
    shutil.move(str(out / f"{a.name}-contact.jpg"), str(out / f"contact-video-{suffix}.jpg"))
    shutil.copyfile(d / "contact.jpg", out / f"contact-frames-{suffix}.jpg")
    shutil.copyfile(d / "segments.json", out / "segments.json")

    seg = json.loads((d / "segments.json").read_text(encoding="utf-8"))
    events = {e["name"]: e["frame"] for e in seg["events"]}
    name, _, offset = a.thumb.partition("+")
    frame = (events[name] if name in events else int(name)) + int(offset or 0)
    thumb = Image.open(d / f"f{frame:06d}.png").convert("RGB")
    thumb.save(out / f"thumbnail-{suffix}.jpg", quality=92)
    print(f"thumbnail: frame {frame}")

    probe = json.loads(subprocess.run(["ffprobe", "-v", "error", "-show_streams", "-show_format", "-of", "json", str(video)], capture_output=True, text=True, check=True).stdout)
    v = [s for s in probe["streams"] if s["codec_type"] == "video"]
    audio = [s for s in probe["streams"] if s["codec_type"] == "audio"]
    size_mb = video.stat().st_size / 1e6
    facts = {
        "codec": v[0]["codec_name"], "profile": v[0]["profile"], "pix_fmt": v[0]["pix_fmt"], "size": f'{v[0]["width"]}x{v[0]["height"]}',
        "fps": v[0]["r_frame_rate"], "seconds": round(float(probe["format"]["duration"]), 2), "MB": round(size_mb, 1), "audio streams": len(audio),
    }
    print("encode: " + ", ".join(f"{k} {val}" for k, val in facts.items()))
    with open(video, "rb") as f:
        head = f.read(4096)
    faststart = head.find(b"moov") != -1 and (head.find(b"mdat") == -1 or head.find(b"moov") < head.find(b"mdat"))
    problems = []
    if facts["codec"] != "h264" or facts["profile"] != "High" or facts["pix_fmt"] != "yuv420p" or facts["fps"] != "60/1" or audio:
        problems.append("the encode is not H.264 High yuv420p 60 fps without audio")
    if size_mb >= 100:
        problems.append("the video is 100 MB or more")
    if not faststart:
        problems.append("the moov atom is not at the start (+faststart)")

    first = np.asarray(Image.open(d / "f000000.png").convert("RGB"), dtype=np.int16)
    last_index = events["end"] - 1
    last = np.asarray(Image.open(d / f"f{last_index:06d}.png").convert("RGB"), dtype=np.int16)
    diff = np.abs(first - last)
    print(f"loop point: frame 0 against frame {last_index}: mean difference {diff.mean():.2f} of 255, {100 * (diff.max(axis=2) > 24).mean():.2f}% of pixels differ by more than 24")
    for pr in problems:
        print("PROBLEM: " + pr)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
