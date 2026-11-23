"""Simula la red como la ejecuta la GPU en fp16 (mediump): pesos, entradas y acumulación en 16 bits,
en el mismo orden que los shaders (por desplazamiento y por textura de 4 canales)."""
import os, sys, numpy as np, torch
from PIL import Image
from student import Student, SCALE

def conv_fp16(x, w, b, acc_dtype):
    # x: [C,H,W] ; w: [O,C,3,3]
    C, H, W = x.shape
    xp = np.pad(x, ((0, 0), (1, 1), (1, 1)), mode="edge").astype(acc_dtype)
    w = w.astype(acc_dtype)
    acc = np.broadcast_to(b.astype(acc_dtype)[:, None, None], (w.shape[0], H, W)).copy()
    for dy in range(3):
        for dx in range(3):
            patch = xp[:, dy:dy + H, dx:dx + W]
            for c0 in range(0, C, 4):  # una textura RGBA por vez
                contrib = np.einsum("oc,chw->ohw", w[:, c0:c0 + 4, dy, dx].astype(np.float32),
                                    patch[c0:c0 + 4].astype(np.float32)).astype(acc_dtype)
                acc = (acc + contrib).astype(acc_dtype)
    return acc

def run(model, lr, dt):
    x = lr.transpose(2, 0, 1).astype(dt)
    h = x
    for conv, act in zip(model.convs, model.acts):
        h = conv_fp16(h, conv.weight.detach().numpy(), conv.bias.detach().numpy(), dt)
        a = act.weight.detach().numpy().astype(dt)[:, None, None]
        h = (np.maximum(h, 0) + a * np.minimum(h, 0)).astype(np.float16)  # se guarda en RGBA16F
    out = conv_fp16(h, model.last.weight.detach().numpy(), model.last.bias.detach().numpy(), dt).astype(np.float32)
    out = torch.nn.functional.pixel_shuffle(torch.from_numpy(out)[None], SCALE)[0].numpy()
    base = x.astype(np.float32).repeat(SCALE, 1).repeat(SCALE, 2)
    return np.clip(out + base, 0, 1).transpose(1, 2, 0)

m = Student(); m.load_state_dict(torch.load(sys.argv[1])); m.eval()
for f in sys.argv[2:]:
    lr = np.asarray(Image.open(f).convert("RGB"), dtype=np.float32) / 255
    a = (run(m, lr, np.float32) * 255).round()
    b = (run(m, lr, np.float16) * 255).round()
    d = np.abs(a - b)
    print(f"{os.path.basename(f)}: fp16 vs fp32  máx {d.max():.0f}/255  media {d.mean():.3f}  píxeles con error >3: {(d.max(2) > 3).mean() * 100:.3f}%")
    Image.fromarray(b.astype(np.uint8)).save("/tmp/fp16_" + os.path.basename(f))
