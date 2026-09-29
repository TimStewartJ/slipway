"""Makes a small labelled JPEG contact sheet from screenshots, for reviewing many frames cheaply.

usage: python contact_sheet.py OUT.jpg TILE_WIDTH COLUMNS IMAGE [IMAGE ...]
"""
import os
import sys

from PIL import Image, ImageDraw

out, tile_w, columns, paths = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), sys.argv[4:]
tiles = []
for path in paths:
    img = Image.open(path).convert("RGB")
    h = round(img.height * tile_w / img.width)
    tiles.append((os.path.basename(path), img.resize((tile_w, h), Image.LANCZOS)))
tile_h = max(t[1].height for t in tiles)
label_h = 14
rows = (len(tiles) + columns - 1) // columns
sheet = Image.new("RGB", (columns * tile_w, rows * (tile_h + label_h)), (32, 32, 32))
draw = ImageDraw.Draw(sheet)
for i, (name, img) in enumerate(tiles):
    x = (i % columns) * tile_w
    y = (i // columns) * (tile_h + label_h)
    draw.text((x + 3, y + 1), name, fill=(255, 255, 160))
    sheet.paste(img, (x, y + label_h))
sheet.save(out, "JPEG", quality=70)
print(out, os.path.getsize(out))
