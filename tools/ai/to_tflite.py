"""Convierte el cuerpo del alumno a LiteRT (.tflite) para la NPU.
Entrada [1,160,240,3] -> salida [1,160,240,48] (48 canales antes del pixel shuffle).
El pixel shuffle 4x + residuo los hace la GPU (shader), porque en LiteRT se vuelven
reshapes de 6 dimensiones que la NPU no acepta. El delegado QNN corre en fp16 en la NPU (HTP)."""
import argparse
import sys
import numpy as np
import torch
import torch.nn.functional as F
import litert_torch as ai_edge_torch
from student import Student, SCALE


class NHWC(torch.nn.Module):
    def __init__(self, m):
        super().__init__()
        self.m = m

    def forward(self, x):  # x: [1,H,W,3]
        h = x.permute(0, 3, 1, 2)
        for conv, act in zip(self.m.convs, self.m.acts):
            h = act(conv(self.m.pad(h)))
        y = self.m.last(self.m.pad(h))  # [1,48,H,W]
        return y.permute(0, 2, 3, 1)


# Uso: to_tflite.py pesos.pt salida.tflite [--height 160] [--width 240]  (por defecto GBA 240x160)
ap = argparse.ArgumentParser()
ap.add_argument("pesos")
ap.add_argument("salida")
ap.add_argument("--height", type=int, default=160)
ap.add_argument("--width", type=int, default=240)
args = ap.parse_args()
H, W = args.height, args.width
sys.argv[1:3] = [args.pesos, args.salida]

m = Student()
m.load_state_dict(torch.load(sys.argv[1], map_location="cpu"))
m.eval()
w = NHWC(m).eval()
sample = (torch.rand(1, H, W, 3),)
edge = ai_edge_torch.convert(w, sample)
edge.export(sys.argv[2])
# Verificación: cuerpo .tflite + shuffle/residuo == red completa en PyTorch
x = torch.rand(1, H, W, 3)
b = torch.from_numpy(np.asarray(edge(x))).permute(0, 3, 1, 2)
full = (F.pixel_shuffle(b, SCALE) + F.interpolate(x.permute(0, 3, 1, 2), scale_factor=SCALE, mode="nearest")).clamp(0, 1)
ref = m(x.permute(0, 3, 1, 2)).clamp(0, 1)
print(sys.argv[2], "diferencia máx vs PyTorch:", float((full - ref).abs().max()))
