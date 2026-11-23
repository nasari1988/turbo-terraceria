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
print(len(tiles), "tiles")
