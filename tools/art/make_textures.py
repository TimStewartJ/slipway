"""Generates Slipway's own 16x16 block textures (original pixel art, Apache-2.0 with the mod)."""
import random, sys, math
from PIL import Image

out = sys.argv[1]
rng = random.Random(1729)

def plank_base():
    img = Image.new("RGBA", (16, 16))
    px = img.load()
    for y in range(16):
        board = y // 4
        for x in range(16):
            base = (132, 94, 58) if board % 2 == 0 else (120, 84, 50)
            n = rng.randint(-8, 8)
            if y % 4 == 3:
                base = (86, 58, 34)
            if (x + board * 5) % 16 == 0 and y % 4 != 3:
                base = (98, 68, 40)
            px[x, y] = (max(0, base[0] + n), max(0, base[1] + n), max(0, base[2] + n), 255)
    return img

def frame(img, color=(70, 48, 28, 255)):
    px = img.load()
    for i in range(16):
        px[i, 0] = color; px[i, 15] = color; px[0, i] = color; px[15, i] = color
    return img

# side: planks with a dark frame
side = frame(plank_base())
side.save(f"{out}/helm_side.png")

# top: planks with a brass compass rose inlay
top = frame(plank_base())
px = top.load()
brass = (196, 160, 72, 255); dark = (120, 92, 40, 255)
for y in range(16):
    for x in range(16):
        dx, dy = x - 7.5, y - 7.5
        r = math.hypot(dx, dy)
        if 4.6 <= r <= 5.6:
            px[x, y] = brass
for i in range(3, 13):
    px[7, i] = dark if i in (3, 12) else brass
    px[8, i] = dark if i in (3, 12) else brass
for i in range(5, 11):
    px[i, 7] = brass; px[i, 8] = brass
px[7, 3] = (200, 40, 40, 255); px[8, 3] = (200, 40, 40, 255)
top.save(f"{out}/helm_top.png")

# front: a ship's wheel (rim, hub, eight spokes with handles) over darker planks
front = frame(plank_base(), (60, 40, 24, 255))
px = front.load()
wood = (176, 122, 64, 255); rim_dark = (104, 66, 34, 255); hub = (210, 170, 80, 255)
cx, cy = 7.5, 7.5
for y in range(16):
    for x in range(16):
        r = math.hypot(x - cx, y - cy)
        if 4.4 <= r <= 5.5:
            px[x, y] = wood
        elif 5.5 < r <= 6.1:
            px[x, y] = rim_dark
for k in range(8):
    a = k * math.pi / 4
    for t in [i * 0.5 for i in range(1, 15)]:
        x = int(round(cx + math.cos(a) * t)); y = int(round(cy + math.sin(a) * t))
        if 0 < x < 15 and 0 < y < 15:
            px[x, y] = wood if t <= 5.5 else rim_dark
for y in range(6, 10):
    for x in range(6, 10):
        px[x, y] = hub
front.save(f"{out}/helm_front.png")
print("ok")
