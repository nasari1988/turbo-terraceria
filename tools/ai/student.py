"""Red ligera (alumno) para escalar pixel art 4x en tiempo real en la GPU del celular.
Imita a Real-ESRGAN (maestro). Diseñada para pasar 1:1 a shaders GLES3:
16 canales = 4 texturas RGBA, convoluciones 3x3 con borde replicado, PReLU,
pixel shuffle 4x y suma con la entrada agrandada (residuo)."""
import torch
import torch.nn as nn
import torch.nn.functional as F

CH = 16
BODY = 6
SCALE = 4


class Student(nn.Module):
    def __init__(self, ch=CH, body=BODY):
        super().__init__()
        self.convs = nn.ModuleList([nn.Conv2d(3, ch, 3)] + [nn.Conv2d(ch, ch, 3) for _ in range(body)])
        self.acts = nn.ModuleList([nn.PReLU(ch, init=0.1) for _ in range(body + 1)])
        self.last = nn.Conv2d(ch, 3 * SCALE * SCALE, 3)

    @staticmethod
    def pad(x):
        return F.pad(x, (1, 1, 1, 1), mode="replicate")

    def forward(self, x):
        h = x
        for conv, act in zip(self.convs, self.acts):
            h = act(conv(self.pad(h)))
        out = F.pixel_shuffle(self.last(self.pad(h)), SCALE)
        base = F.interpolate(x, scale_factor=SCALE, mode="nearest")
        return out + base
