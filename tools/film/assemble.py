#!/usr/bin/env python3
"""Assembles a rendered film (`gradlew runFilm -PslipwayFilm=reddit`) into the final video.

Usage: python tools/film/assemble.py <frames dir> --out video.mp4 [--xfade 6] [--crf 17]

- Shots come from <dir>/segments.json (events named after shots: hook, flyby, roll, deck, return; `end` closes the
  last). Consecutive shots are joined with a short crossfade of --xfade frames (the outgoing shot's last frames blended
  into the incoming shot's first ones), which makes the video that many frames shorter per cut.
- Captions are an ASS file (<out>.ass, kept next to the video) timed from the events and burned in with libass:
  Segoe UI Black, white with a dark outline and shadow, inside safe margins. The end card sits at the top.
- Encode: H.264 High, yuv420p, 60 fps, CRF --crf, +faststart, no audio track.
- Writes <out>-contact.jpg: a sheet of the encoded video every second (captions included).
"""
import argparse
import json
import pathlib
import subprocess
import sys

import numpy as np
from PIL import Image

SHOTS = ["hook", "flyby", "roll", "deck", "return"]


def ass_time(seconds):
    cs = max(0, int(round(seconds * 100)))
    return f"{cs // 360000}:{cs // 6000 % 60:02d}:{cs // 100 % 60:02d}.{cs % 100:02d}"


def captions(ev, fps, w, h, total_seconds):
    """The caption script: list of (start s, end s, style, text), from event times in output seconds."""
    t = {k: v / fps for k, v in ev.items()}
    lines = [
        (0.10, t["flyby"] - 0.15, "Cap", "Every block stays a real block"),
        (t["flyby"] + 0.35, t["roll"] - 0.25, "Cap", "Built from any blocks"),
        (t["roll"] + 0.15, t["door"] - 0.1, "Cap", "Full 3-axis physics"),
        (t["door"] + 0.05, t["deck"] - 0.2, "Cap", "Doors. Redstone. Chests."),
        (t["deck"] + 0.3, t["return"] - 0.25, "Cap", "Riders stay on deck"),
        (t["return"] + 0.4, t["disassemble"] - 0.15, "Cap", "Bring it home level"),
        (t["disassemble"] + 0.05, t["disassemble"] + 1.3, "Cap", "Back to plain blocks"),
        (t["disassemble"] + 1.3, total_seconds, "Card", "Slipway — open source · built by AI agents\\N{\\rUrl}github.com/TimStewartJ/slipway"),
    ]
    portrait = h > w
    size = round(h * (0.058 if portrait else 0.068))
    card = round(size * (0.62 if portrait else 0.7))
    url = round(card * 0.85)
    margin_v = round(h * (0.13 if portrait else 0.075))
    margin_lr = round(w * 0.06)
    outline = max(3, round(size * 0.075))
    shadow = max(2, round(size * 0.045))
    head = f"""[Script Info]
ScriptType: v4.00+
PlayResX: {w}
PlayResY: {h}
WrapStyle: 0
ScaledBorderAndShadow: yes

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Cap,Segoe UI Black,{size},&H00FFFFFF,&H00FFFFFF,&H00141414,&H96000000,0,0,0,0,100,100,0,0,1,{outline},{shadow},2,{margin_lr},{margin_lr},{margin_v},1
Style: Card,Segoe UI Black,{card},&H00FFFFFF,&H00FFFFFF,&H00141414,&H96000000,0,0,0,0,100,100,0,0,1,{max(3, round(card * 0.08))},{max(2, round(card * 0.05))},8,{margin_lr},{margin_lr},{round(h * 0.09)},1
Style: Url,Segoe UI Semibold,{url},&H00FFFFFF,&H00FFFFFF,&H00141414,&H96000000,0,0,0,0,100,100,0,0,1,{max(3, round(url * 0.08))},{max(2, round(url * 0.05))},8,{margin_lr},{margin_lr},{round(h * 0.09)},1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
"""
    body = ""
    for start, end, style, text in lines:
        if end - start < 0.4:
            print(f"warning: caption '{text}' is only {end - start:.2f} s", file=sys.stderr)
        body += f"Dialogue: 0,{ass_time(start)},{ass_time(end)},{style},,0,0,0,,{{\\fad(160,160)}}{text}\n"
    return head + body, lines


def main():
    p = argparse.ArgumentParser()
    p.add_argument("dir")
    p.add_argument("--out", required=True)
    p.add_argument("--xfade", type=int, default=6)
    p.add_argument("--crf", type=int, default=17)
    a = p.parse_args()
    d = pathlib.Path(a.dir)
    seg = json.loads((d / "segments.json").read_text(encoding="utf-8"))
    fps = seg["fps"]
    w, h = seg["size"]
    src = {e["name"]: e["frame"] for e in seg["events"]}
    starts = [src[s] for s in SHOTS] + [src["end"]]
    x = a.xfade

    def out_index(frame):
        """Output frame of a source frame: every cut before it removed x frames."""
        cuts_before = sum(1 for s in starts[1:-1] if s <= frame)
        return frame - cuts_before * x

    ev = {k: out_index(v) for k, v in src.items()}
    total = out_index(starts[-1] - 1) + 1
    script, lines = captions(ev, fps, w, h, total / fps)
    out = pathlib.Path(a.out).resolve()
    ass = out.with_suffix(".ass")
    ass.write_text(script, encoding="utf-8")

    def load(i):
        return np.asarray(Image.open(d / f"f{i:06d}.png").convert("RGB"), dtype=np.float32)

    cmd = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{w}x{h}", "-r", str(fps), "-i", "-",
           "-vf", f"ass={ass.name}", "-c:v", "libx264", "-profile:v", "high", "-preset", "slow", "-crf", str(a.crf), "-pix_fmt", "yuv420p",
           "-movflags", "+faststart", "-an", str(out)]
    proc = subprocess.Popen(cmd, stdin=subprocess.PIPE, cwd=str(ass.parent))
    written = 0
    for si in range(len(SHOTS)):
        first, last = starts[si], starts[si + 1]
        lo = first + (x if si > 0 else 0)
        hi = last - (x if si + 1 < len(SHOTS) else 0)
        if si > 0:
            # the crossfade into this shot: the previous shot's last x frames over this shot's first x
            for j in range(x):
                k = (j + 1) / (x + 1)
                frame = load(starts[si] - x + j) * (1 - k) + load(first + j) * k
                proc.stdin.write(np.clip(frame + 0.5, 0, 255).astype(np.uint8).tobytes())
                written += 1
        for i in range(lo, hi):
            proc.stdin.write(np.asarray(Image.open(d / f"f{i:06d}.png").convert("RGB")).tobytes())
            written += 1
    proc.stdin.close()
    if proc.wait() != 0:
        sys.exit("ffmpeg failed")
    assert written == total, (written, total)
    sheet = out.with_name(out.stem + "-contact.jpg")
    cols = 6
    rows = -(-total // fps // cols) + 1
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", str(out), "-vf",
                    f"select='not(mod(n\\,{fps}))',scale=320:-1,tile={cols}x{rows}", "-frames:v", "1", "-q:v", "3", str(sheet)], check=True)
    print(f"{out}: {written} frames, {written / fps:.2f} s, {out.stat().st_size / 1e6:.1f} MB; captions {ass.name}; sheet {sheet.name}")
    for start, end, style, text in lines:
        print(f"  {start:6.2f}-{end:6.2f} s  {text}")


if __name__ == "__main__":
    main()
