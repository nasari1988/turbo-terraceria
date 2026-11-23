"""Modelos de prueba para diagnosticar la NPU:
- probe_conv.tflite: una sola convolución 3x3 (si esto falla, la NPU no está accesible).
- student_v2_zp.tflite: la v2 con relleno de ceros integrado en la convolución (sin MIRROR_PAD).
  Solo cambia el borde de 1 píxel del cuadro."""
import sys
import numpy as np
import torch
import litert_torch
from student import Student

out_dir = sys.argv[2]


class Probe(torch.nn.Module):
    def __init__(self):
        super().__init__()
        self.c = torch.nn.Conv2d(3, 4, 3, padding=1)

    def forward(self, x):
        return self.c(x.permute(0, 3, 1, 2)).permute(0, 2, 3, 1)


class ZeroPad(torch.nn.Module):
    def __init__(self, m):
        super().__init__()
        self.m = m

    def forward(self, x):
        h = x.permute(0, 3, 1, 2)
        for conv, act in zip(self.m.convs, self.m.acts):
            h = act(torch.nn.functional.conv2d(h, conv.weight, conv.bias, padding=1))
        y = torch.nn.functional.conv2d(h, self.m.last.weight, self.m.last.bias, padding=1)
        return y.permute(0, 2, 3, 1)


sample = (torch.rand(1, 160, 240, 3),)
litert_torch.convert(Probe().eval(), sample).export(f"{out_dir}/probe_conv.tflite")
m = Student()
m.load_state_dict(torch.load(sys.argv[1], map_location="cpu"))
m.eval()
litert_torch.convert(ZeroPad(m).eval(), sample).export(f"{out_dir}/student_v2_zp.tflite")
print("listo")
