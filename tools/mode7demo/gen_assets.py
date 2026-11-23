"""Genera los gráficos y tablas de la demo Mode 7 (todo original) -> assets.h"""
import math
import random

random.seed(7)

# ---------- Paleta (BGR555) ----------
def rgb(r, g, b):
    return (r >> 3) | ((g >> 3) << 5) | ((b >> 3) << 10)

pal = [0] * 256
colors = {
    "sky": (120, 180, 248),
    "grass1": (88, 184, 72), "grass2": (64, 152, 56), "speck": (120, 208, 96),
    "asphalt": (92, 92, 104), "asph2": (108, 108, 120),
    "white": (240, 240, 240), "red": (224, 48, 48), "yellow": (248, 216, 64), "black": (24, 24, 32),
    "dirt": (168, 112, 64),
}
idx = {}
for i, (k, c) in enumerate(colors.items()):
    pal[i] = rgb(*c)
    idx[k] = i

# ---------- Tiles 8x8 de 8bpp ----------
tiles = []

def tile(fn):
    tiles.append([fn(x, y) for y in range(8) for x in range(8)])
    return len(tiles) - 1

T_EMPTY = tile(lambda x, y: 0)
def noisy(base, alt, p):
    return lambda x, y: idx[alt] if random.random() < p else idx[base]
T_G1 = tile(noisy("grass1", "speck", 0.06))
T_G2 = tile(noisy("grass2", "grass1", 0.04))
T_ASPH = tile(noisy("asphalt", "asph2", 0.10))
T_LINE_H = tile(lambda x, y: idx["yellow"] if 3 <= y <= 4 and x < 5 else idx["asphalt"])
T_LINE_V = tile(lambda x, y: idx["yellow"] if 3 <= x <= 4 and y < 5 else idx["asphalt"])
T_CURB_H = tile(lambda x, y: idx["red"] if (x // 4) % 2 == 0 else idx["white"])
T_CURB_V = tile(lambda x, y: idx["red"] if (y // 4) % 2 == 0 else idx["white"])
T_START = tile(lambda x, y: idx["white"] if ((x // 2) + (y // 2)) % 2 == 0 else idx["black"])
T_DIRT = tile(noisy("dirt", "grass2", 0.05))

# ---------- Mapa 128x128 tiles (1024 px), pista tipo estadio ----------
N = 128
cx, cy = 64.0, 64.0
half_straight, radius, width = 22.0, 26.0, 5.0

def track_dist(x, y):
    """Distancia (en tiles) desde la línea central de una pista tipo estadio, y su dirección."""
    dx, dy = x - cx, y - cy
    if abs(dx) <= half_straight:
        return abs(abs(dy) - radius), "h"  # rectas horizontales
    ex = dx - math.copysign(half_straight, dx)
    d = math.hypot(ex, dy)
    # en curvas, la orientación del bordillo depende del ángulo
    return abs(d - radius), ("v" if abs(ex) > abs(dy) else "h")

tmap = []
for ty in range(N):
    for tx in range(N):
        x, y = tx + 0.5, ty + 0.5
        d, orient = track_dist(x, y)
        if d < width - 1:
            t = T_ASPH
            if d < 0.5:
                if orient == "h" and tx % 2 == 0:
                    t = T_LINE_H
                elif orient == "v" and ty % 2 == 0:
                    t = T_LINE_V
            if abs(x - cx) < 1 and y > cy:  # línea de meta en la recta inferior
                t = T_START
        elif d < width:
            t = T_CURB_H if orient == "h" else T_CURB_V
        elif d < width + 1.5:
            t = T_DIRT
        else:
            t = T_G1 if ((tx // 2) + (ty // 2)) % 2 == 0 else T_G2
        tmap.append(t)

# ---------- Tablas ----------
HZ, H = 32, 24  # horizonte (línea) y altura de cámara
sin_lut = [round(math.sin(2 * math.pi * i / 256) * 4096) for i in range(256)]
zd8 = [((H << 8) // (y - HZ)) if y > HZ else 0 for y in range(160)]
sky = []
for y in range(160):
    t = min(1.0, y / HZ)
    sky.append(rgb(int(70 + 110 * t), int(130 + 90 * t), 248))

def arr(name, ctype, data, per=16):
    rows = [", ".join(str(v) for v in data[i:i + per]) for i in range(0, len(data), per)]
    return f"static const {ctype} {name}[{len(data)}] = {{\n  " + ",\n  ".join(rows) + "\n};\n"

# ---------- Capa 2D (BG0, 4bpp, paleta 1): montañas, nubes y texto ----------
from PIL import Image, ImageDraw, ImageFont
bg_pal = [(0, 0, 0), (96, 80, 160), (120, 104, 184), (240, 240, 248), (200, 208, 232),
          (248, 248, 248), (24, 24, 40), (248, 200, 64)]
strip = Image.new("P", (256, 40), 0)
strip.putpalette([c for col in bg_pal for c in col] + [0] * (256 - len(bg_pal)) * 3)
d = ImageDraw.Draw(strip)
# montañas (dos tonos) apoyadas en el horizonte (y=33)
for x0, w, h, col in [(-10, 90, 16, 1), (60, 110, 20, 2), (150, 80, 14, 1), (200, 90, 18, 2)]:
    d.polygon([(x0, 33), (x0 + w // 2, 33 - h), (x0 + w, 33)], fill=col)
    d.polygon([(x0 + w // 2 - 4, 33 - h + 4), (x0 + w // 2, 33 - h), (x0 + w // 2 + 4, 33 - h + 4)], fill=3)
# nubes
for cx, cy in [(40, 20), (130, 16), (210, 22)]:
    for dx, r in [(-8, 5), (0, 7), (9, 5)]:
        d.ellipse([cx + dx - r, cy - r // 2 - 2, cx + dx + r, cy + r // 2 + 2], fill=4)
        d.ellipse([cx + dx - r + 1, cy - r // 2 - 3, cx + dx + r - 1, cy + r // 2], fill=5)
# texto del marcador con contorno
font = ImageFont.load_default_imagefont() if hasattr(ImageFont, "load_default_imagefont") else ImageFont.load_default()
d.fontmode = "1"
for ox in (-1, 0, 1):
    for oy in (-1, 0, 1):
        d.text((4 + ox, 1 + oy), "VUELTA 1/3", fill=6, font=font)
d.text((4, 1), "VUELTA 1/3", fill=7, font=font)
px = strip.load()
bg_tiles, bg_index, bg_map = [], {}, [0] * (32 * 32)
bg_tiles.append(bytes(32))  # tile 0 transparente
bg_index[bytes(32)] = 0
for ty in range(5):
    for tx in range(32):
        data = bytearray()
        for y in range(8):
            for x in range(0, 8, 2):
                a, b = px[tx * 8 + x, ty * 8 + y], px[tx * 8 + x + 1, ty * 8 + y]
                data.append(a | (b << 4))
        data = bytes(data)
        if data not in bg_index:
            bg_index[data] = len(bg_tiles)
            bg_tiles.append(data)
        bg_map[ty * 32 + tx] = bg_index[data] | (1 << 12)  # paleta 1
for i, c in enumerate(bg_pal):
    pal[16 + i] = rgb(*c)

# ---------- Sprite: kart 32x32 (4bpp, paleta de sprites 0) ----------
obj_pal = [(0, 0, 0), (24, 24, 32), (220, 40, 40), (160, 24, 32), (248, 248, 248), (60, 120, 220), (248, 208, 64), (90, 90, 100)]
kart = Image.new("P", (32, 32), 0)
kart.putpalette([c for col in obj_pal for c in col] + [0] * (256 - len(obj_pal)) * 3)
k = ImageDraw.Draw(kart)
k.rectangle([2, 18, 9, 29], fill=1); k.rectangle([22, 18, 29, 29], fill=1)          # llantas traseras
k.rectangle([4, 8, 9, 15], fill=1); k.rectangle([22, 8, 27, 15], fill=1)            # llantas delanteras
k.polygon([(8, 30), (8, 12), (12, 4), (19, 4), (23, 12), (23, 30)], fill=2)         # carrocería
k.polygon([(10, 30), (10, 14), (13, 8), (18, 8), (21, 14), (21, 30)], fill=3)
k.ellipse([11, 12, 20, 22], fill=5); k.ellipse([13, 13, 18, 17], fill=4)            # casco
k.rectangle([7, 26, 24, 29], fill=7); k.rectangle([12, 2, 19, 4], fill=6)           # alerón / nariz
kp = kart.load()
obj_tiles = bytearray()
for ty in range(4):
    for tx in range(4):
        for y in range(8):
            for x in range(0, 8, 2):
                obj_tiles.append(kp[tx * 8 + x, ty * 8 + y] | (kp[tx * 8 + x + 1, ty * 8 + y] << 4))
obj_pal16 = [rgb(*c) for c in obj_pal] + [0] * 8

flat_tiles = [p for t in tiles for p in t]
with open("assets.h", "w") as f:
    f.write("// Generado por gen_assets.py. Gráficos originales.\n#pragma once\n#include <stdint.h>\n")
    f.write(f"#define HORIZON {HZ}\n#define MAP_TILES {N}\n")
    f.write(arr("palette", "uint16_t", pal))
    f.write("__attribute__((aligned(4))) " + arr("tiles", "uint8_t", flat_tiles, 64))
    f.write("__attribute__((aligned(4))) " + arr("tilemap", "uint8_t", tmap, 128))
    f.write(arr("sin_lut", "int16_t", sin_lut))
    f.write(arr("zd8", "int16_t", zd8))
    f.write(arr("sky", "uint16_t", sky))
    f.write("__attribute__((aligned(4))) " + arr("bg0_tiles", "uint8_t", [b for t in bg_tiles for b in t], 32))
    f.write(arr("bg0_map", "uint16_t", bg_map, 32))
    f.write("__attribute__((aligned(4))) " + arr("kart_tiles", "uint8_t", list(obj_tiles), 32))
    f.write(arr("obj_palette", "uint16_t", obj_pal16))
print(len(tiles), "tiles")
