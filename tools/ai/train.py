"""Entrena el alumno con pares (escena original, salida de Real-ESRGAN 4x)."""
import glob
import math
import random
import sys
import time

import numpy as np
import torch
import torch.nn.functional as F
from PIL import Image

from student import Student

torch.set_num_threads(2)
data_dir = sys.argv[1]
steps = int(sys.argv[2]) if len(sys.argv) > 2 else 4000
out = sys.argv[3] if len(sys.argv) > 3 else "student.pt"

pairs = []
for f in sorted(glob.glob(f"{data_dir}/hr/*.png")):
    lr = np.asarray(Image.open(f.replace("/hr/", "/lr/")).convert("RGB"), dtype=np.float32) / 255
    hr = np.asarray(Image.open(f).convert("RGB"), dtype=np.float32) / 255
    pairs.append((torch.from_numpy(lr).permute(2, 0, 1), torch.from_numpy(hr).permute(2, 0, 1)))
random.seed(0)
random.shuffle(pairs)
val = pairs[:40]
train = pairs[40:]
print(len(train), "entrenamiento,", len(val), "validación")

P, B = 40, 16
model = Student()
import os
LR = float(os.environ.get("AI_LR", 2e-3))  # redes más anchas necesitan menos (la v3 de 48 canales divergió con 2e-3)
opt = torch.optim.Adam(model.parameters(), lr=LR)


def batch():
    xs, ys = [], []
    for _ in range(B):
        lr, hr = random.choice(train)
        y0, x0 = random.randrange(0, lr.shape[1] - P + 1), random.randrange(0, lr.shape[2] - P + 1)
        x = lr[:, y0:y0 + P, x0:x0 + P]
        y = hr[:, y0 * 4:(y0 + P) * 4, x0 * 4:(x0 + P) * 4]
        if random.random() < 0.5:
            x, y = x.flip(2), y.flip(2)
        if random.random() < 0.3:  # cambios de color: las paletas del GBA varían mucho
            perm = torch.randperm(3)
            x, y = x[perm], y[perm]
        xs.append(x)
        ys.append(y)
    return torch.stack(xs), torch.stack(ys)


def evaluate():
    model.eval()
    with torch.no_grad():
        l = sum(F.l1_loss(model(lr[None]), hr[None]).item() for lr, hr in val) / len(val)
        base = sum(F.l1_loss(F.interpolate(lr[None], scale_factor=4, mode="nearest"), hr[None]).item() for lr, hr in val) / len(val)
    model.train()
    return l, base


t0 = time.time()
for step in range(1, steps + 1):
    warm = min(1.0, step / 500)  # arranque suave
    lr_now = LR * warm * 0.5 * (1 + math.cos(math.pi * step / steps))
    for g in opt.param_groups:
        g["lr"] = lr_now
    x, y = batch()
    loss = F.l1_loss(model(x), y)
    opt.zero_grad()
    loss.backward()
    torch.nn.utils.clip_grad_norm_(model.parameters(), 0.5)  # evita saltos que desestabilizan
    opt.step()
    if step % 250 == 0 or step == steps:
        v, base = evaluate()
        print(f"paso {step} pérdida {loss.item():.4f} val {v:.4f} (agrandar sin IA: {base:.4f}) {time.time() - t0:.0f}s", flush=True)
        torch.save(model.state_dict(), out)
