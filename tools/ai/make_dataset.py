"""Dataset v2: recortes de escenas de pixel art de Kenney (CC0, dominio público)
más composiciones con sprites de sus hojas. Recortes de 120x80 (el entrenamiento usa parches).
Uso: make_dataset.py carpeta_kenney salida N"""
import glob
import os
import random
import sys

import numpy as np
from PIL import Image

src, out, n = sys.argv[1], sys.argv[2], int(sys.argv[3])
random.seed(1234)
os.makedirs(f"{out}/lr", exist_ok=True)
CW, CH = 120, 80


def native(im):
    a = np.asarray(im)
    for k in (4, 3, 2):
        h, w = a.shape[0] // k * k, a.shape[1] // k * k
        b = a[:h, :w]
        if (b == b[::k, ::k].repeat(k, 0).repeat(k, 1)).mean() > 0.995:
            return im.crop((0, 0, w, h)).resize((w // k, h // k), Image.NEAREST)
    return im


# Estas muestras están estiradas a escala no entera (no son pixel art nativo): se excluyen
EXCLUDE = ("kenney_rpgurbanpack", "kenney_microroguelike")
scenes = [native(Image.open(f).convert("RGB")) for f in sorted(glob.glob(f"{src}/*/Sample*.png"))
          if not any(e in f for e in EXCLUDE)]
sheets = []
for f in sorted(glob.glob(f"{src}/*/*/*transparent*.png") + glob.glob(f"{src}/*/Tilemap/*packed*.png")):
    if "monochrome" in f:
        continue
    sheets.append(Image.open(f).convert("RGBA"))
print(len(scenes), "escenas,", len(sheets), "hojas de sprites")


def gba_colors(im):
    a = np.asarray(im).astype(np.uint8) & 0xF8  # 15 bits de color, como el GBA
    return Image.fromarray(a)


def color_shift(im):
    a = np.asarray(im).astype(np.float32)
    if random.random() < 0.4:
        a = a[..., np.random.permutation(3)]
    if random.random() < 0.4:
        a = a * random.uniform(0.7, 1.2) + random.uniform(-20, 20)
    return Image.fromarray(np.clip(a, 0, 255).astype(np.uint8))


def random_sprite():
    sh = random.choice(sheets)
    for _ in range(20):
        w, h = random.choice([16, 16, 24, 32, 48]), random.choice([16, 16, 24, 32, 48])
        x, y = random.randrange(0, sh.width - w), random.randrange(0, sh.height - h)
        s = sh.crop((x, y, x + w, y + h))
        alpha = np.asarray(s)[..., 3]
        if 0.2 < (alpha > 0).mean() < 0.95:
            return s
    return None


i = 0
while i < n:
    sc = random.choice(scenes)
    if sc.width < CW or sc.height < CH:
        continue
    x, y = random.randrange(0, sc.width - CW + 1), random.randrange(0, sc.height - CH + 1)
    im = sc.crop((x, y, x + CW, y + CH))
    if random.random() < 0.6:  # sprites encima, como personajes y objetos
        for _ in range(random.randint(1, 4)):
            s = random_sprite()
            if s is not None:
                im.paste(s, (random.randrange(-8, CW - 8), random.randrange(-8, CH - 8)), s)
    if random.random() < 0.15:  # sprite grande agrandado 2x (jefes, retratos)
        s = random_sprite()
        if s is not None:
            s = s.resize((s.width * 2, s.height * 2), Image.NEAREST)
            im.paste(s, (random.randrange(-8, CW - 16), random.randrange(-8, CH - 16)), s)
    im = gba_colors(color_shift(im))
    im.save(f"{out}/lr/k{i:04d}.png")
    i += 1
print("listo", n)
