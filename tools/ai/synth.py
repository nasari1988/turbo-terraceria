"""Escenas sintéticas de pixel art estilo GBA (todo generado, sin material de juegos).
Sirven para entrenar la red ligera imitando a Real-ESRGAN."""
import random

import numpy as np
from PIL import Image, ImageDraw, ImageFont

W, H = 240, 160
FONT = ImageFont.load_default_imagefont() if hasattr(ImageFont, "load_default_imagefont") else ImageFont.load_default()


def rand_color(lo=0, hi=255):
    return tuple(random.randint(lo, hi) & 0xF8 for _ in range(3))  # 5 bits como el GBA


def shade(c, f):
    return tuple(max(0, min(255, int(v * f))) & 0xF8 for v in c)


def tile_pattern(c1, c2):
    kind = random.choice(["speck", "brick", "wave", "checker", "grad", "plain", "dots", "stripe"])
    t = Image.new("RGB", (16, 16), c1)
    d = ImageDraw.Draw(t)
    if kind == "speck":
        for _ in range(random.randint(3, 12)):
            x, y = random.randrange(16), random.randrange(16)
            d.rectangle([x, y, x + random.randint(0, 1), y], fill=c2)
    elif kind == "brick":
        for y in range(0, 16, 4):
            d.line([0, y, 15, y], fill=c2)
            off = 0 if (y // 4) % 2 else 4
            for x in range(off, 16, 8):
                d.line([x, y, x, y + 3], fill=c2)
    elif kind == "wave":
        for y in range(2, 16, 6):
            for x in range(16):
                yy = y + (1 if (x // 3) % 2 else 0)
                d.point((x, yy), fill=c2)
    elif kind == "checker":
        s = random.choice([2, 4, 8])
        for y in range(16):
            for x in range(16):
                if ((x // s) + (y // s)) % 2:
                    d.point((x, y), fill=c2)
    elif kind == "grad":
        for y in range(16):
            for x in range(16):
                if (x + y) % 2 == 0 and y > 7:
                    d.point((x, y), fill=c2)  # tramado
    elif kind == "dots":
        for y in range(1, 16, 4):
            for x in range(1 + (y // 4) % 2 * 2, 16, 4):
                d.point((x, y), fill=c2)
    elif kind == "stripe":
        for x in range(0, 16, random.choice([2, 3, 4])):
            d.line([x, 0, x, 15], fill=c2)
    return t


def background(img):
    c1 = rand_color(30, 230)
    c2 = shade(c1, random.choice([0.7, 0.8, 1.2, 1.3]))
    t = tile_pattern(c1, c2)
    for y in range(0, H, 16):
        for x in range(0, W, 16):
            img.paste(t, (x, y))
    # una segunda zona (camino, agua, piso)
    if random.random() < 0.7:
        c3 = rand_color(30, 230)
        t2 = tile_pattern(c3, shade(c3, 0.8))
        x0, y0 = random.randrange(0, W, 16), random.randrange(0, H, 16)
        x1, y1 = x0 + random.randrange(32, 160, 16), y0 + random.randrange(16, 96, 16)
        for y in range(y0, min(y1, H), 16):
            for x in range(x0, min(x1, W), 16):
                img.paste(t2, (x, y))
        d = ImageDraw.Draw(img)
        d.rectangle([x0, y0, x1 - 1, y1 - 1], outline=shade(c3, 0.6))


def blob(d, cx, cy, rx, ry, base):
    out = shade(base, 0.35)
    d.ellipse([cx - rx, cy - ry, cx + rx, cy + ry], fill=out)
    d.ellipse([cx - rx + 1, cy - ry + 1, cx + rx - 1, cy + ry - 1], fill=base)
    d.ellipse([cx - rx + 2, cy + ry // 3, cx + rx - 1, cy + ry - 1], fill=shade(base, 0.75))
    d.ellipse([cx - rx // 2, cy - ry + 2, cx, cy - ry // 3], fill=shade(base, 1.35))


def character(d, x, y):
    s = random.choice([1, 1, 2])
    skin = rand_color(120, 255)
    body = rand_color(20, 230)
    blob(d, x, y, 5 * s, 5 * s, skin)
    d.rectangle([x - 2 * s, y - s, x - s, y + s // 2], fill=(16, 16, 24))
    d.rectangle([x + s, y - s, x + 2 * s, y + s // 2], fill=(16, 16, 24))
    d.rectangle([x - 4 * s, y + 5 * s, x + 4 * s, y + 12 * s], fill=shade(body, 0.4))
    d.rectangle([x - 3 * s, y + 5 * s, x + 3 * s, y + 11 * s], fill=body)
    d.rectangle([x - 3 * s, y + 9 * s, x + 3 * s, y + 11 * s], fill=shade(body, 0.75))


def tree(d, x, y):
    leaf = rand_color(20, 160)
    d.rectangle([x - 2, y + 8, x + 2, y + 20], fill=shade(rand_color(80, 200), 0.6))
    blob(d, x, y, random.randint(7, 14), random.randint(7, 12), leaf)


def polygon_obj(d, x, y):
    base = rand_color(40, 255)
    pts = [(x + random.randint(-14, 14), y + random.randint(-14, 14)) for _ in range(random.randint(3, 6))]
    d.polygon(pts, fill=base, outline=shade(base, 0.35))


def text_box(img, d):
    d.fontmode = "1"
    x0 = random.randint(0, 40)
    y0 = random.choice([H - 44, random.randint(0, H - 44)])
    x1 = W - random.randint(0, 40)
    fill = random.choice([(248, 248, 248), rand_color(0, 80), rand_color(160, 255)])
    border = rand_color(0, 255)
    d.rectangle([x0, y0, x1, y0 + 42], fill=fill, outline=border)
    d.rectangle([x0 + 2, y0 + 2, x1 - 2, y0 + 40], outline=shade(border, 0.6))
    ink = (40, 40, 48) if sum(fill) > 400 else (248, 248, 248)
    words = "".join(random.choice("ABCDEFGHIJKLMNOPQRSTUVWXYZ abcdefghijklmnopqrstuvwxyz0123456789!?.") for _ in range(30))
    d.text((x0 + 8, y0 + 6), words[:28], fill=ink, font=FONT)
    d.text((x0 + 8, y0 + 22), words[::-1][:24], fill=ink, font=FONT)


def big_title(d):
    c = rand_color(120, 255)
    out = rand_color(0, 120)
    x, y = random.randint(0, 60), random.randint(0, 90)
    txt = "".join(random.choice("ABCDEFGHIJKLMNOPQRSTUVWXYZ") for _ in range(random.randint(4, 8)))
    for dx in (-1, 0, 1):
        for dy in (-1, 0, 1):
            d.text((x + dx, y + dy), txt, fill=out, font=FONT)
    d.text((x, y), txt, fill=c, font=FONT)


def scene(seed):
    random.seed(seed)
    img = Image.new("RGB", (W, H))
    background(img)
    d = ImageDraw.Draw(img)
    d.fontmode = "1"  # texto sin suavizado, como en la consola
    for _ in range(random.randint(0, 5)):
        tree(d, random.randrange(W), random.randrange(H))
    for _ in range(random.randint(0, 4)):
        polygon_obj(d, random.randrange(W), random.randrange(H))
    for _ in range(random.randint(1, 5)):
        character(d, random.randrange(10, W - 10), random.randrange(10, H - 30))
    for _ in range(random.randint(0, 3)):
        blob(d, random.randrange(W), random.randrange(H), random.randint(4, 30), random.randint(4, 24), rand_color(40, 255))
    if random.random() < 0.5:
        text_box(img, d)
    if random.random() < 0.3:
        big_title(d)
    # Algunas escenas se escalan 2x en bloques, como sprites grandes de GBA
    if random.random() < 0.25:
        x, y = random.randrange(0, W - 60), random.randrange(0, H - 40)
        crop = img.crop((x, y, x + 60, y + 40)).resize((120, 80), Image.NEAREST)
        img.paste(crop, (random.randrange(0, W - 120), random.randrange(0, H - 80)))
    return img


if __name__ == "__main__":
    import sys

    out = sys.argv[1]
    n = int(sys.argv[2])
    import os

    os.makedirs(out, exist_ok=True)
    for i in range(n):
        scene(i).save(f"{out}/{i:04d}.png")
