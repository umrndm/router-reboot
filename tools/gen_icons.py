"""Generates the launcher icon PNGs (pre-Android-8 devices). Run from the repo root."""
import math
import os
from PIL import Image, ImageDraw

SIZES = {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}
RES = os.path.join('app', 'src', 'main', 'res')


def make(size):
    s = size * 4
    img = Image.new('RGBA', (s, s), (0, 0, 0, 0))
    grad = Image.new('RGBA', (s, s))
    gd = ImageDraw.Draw(grad)
    for y in range(s):
        t = y / s
        gd.line([(0, y), (s, y)], fill=(int(30 - 15 * t), int(41 - 18 * t), int(59 - 16 * t), 255))
    mask = Image.new('L', (s, s), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, s - 1, s - 1], radius=int(s * 0.22), fill=255)
    img.paste(grad, (0, 0), mask)
    d = ImageDraw.Draw(img)
    cx, cy = s / 2, s / 2 + s * 0.03
    r, w = s * 0.255, int(s * 0.075)
    col = (45, 212, 191, 255)
    d.arc([cx - r, cy - r, cx + r, cy + r], start=310, end=230, fill=col, width=w)
    rc = r - w / 2
    for ang in (310, 230):
        x = cx + rc * math.cos(math.radians(ang))
        y = cy + rc * math.sin(math.radians(ang))
        d.ellipse([x - w / 2, y - w / 2, x + w / 2, y + w / 2], fill=col)
    top, bot = s / 2 - r * 1.05, s / 2 - s * 0.02
    d.line([(cx, top), (cx, bot)], fill=col, width=w)
    for y in (top, bot):
        d.ellipse([cx - w / 2, y - w / 2, cx + w / 2, y + w / 2], fill=col)
    return img.resize((size, size), Image.LANCZOS)


for name, px in SIZES.items():
    folder = os.path.join(RES, 'mipmap-' + name)
    os.makedirs(folder, exist_ok=True)
    make(px).save(os.path.join(folder, 'ic_launcher.png'))
    print('wrote', folder)
