"""Convierte student.pt en shaders GLES3 (app/src/main/cpp/ai_shaders.h).
Cada convolución 3x3 es un pase de fragmentos con 4 salidas (MRT) de 4 canales."""
import sys

import torch

from student import CH, Student

assert CH % 16 == 0
GROUPS = CH // 16
sd = torch.load(sys.argv[1], map_location="cpu")
out_path = sys.argv[2]
model = Student()
model.load_state_dict(sd)

TAPS = [(dx, dy) for dy in (-1, 0, 1) for dx in (-1, 0, 1)]  # kernel[ky][kx], ky=dy+1


def f(v):
    return f"{float(v):.7g}" if "e" not in f"{float(v):.7g}" else f"{float(v):.7e}"


def vec4(vals):
    return "vec4(" + ",".join(f(v) for v in vals) + ")"


def header(n_in):
    s = "#version 300 es\nprecision highp float;\nprecision highp int;\n"
    for i in range(n_in):
        s += f"uniform highp sampler2D t{i};\n"
    return s


def conv_shader(weight, bias, prelu, in_channels, out_group):
    """weight: [out, in, 3, 3]. Genera las salidas out_group*16 .. +16 (4 MRT)."""
    n_in_tex = (in_channels + 3) // 4
    s = header(n_in_tex)
    for o in range(4):
        s += f"layout(location={o}) out vec4 o{o};\n"
    s += "void main() {\n  ivec2 p = ivec2(gl_FragCoord.xy);\n  ivec2 m = textureSize(t0, 0) - 1;\n"
    for o in range(4):
        base = out_group * 16 + o * 4
        s += f"  vec4 a{o} = {vec4(bias[base:base + 4])};\n"
    for ti, (dx, dy) in enumerate(TAPS):
        for it in range(n_in_tex):
            s += f"  {{ vec4 v = texelFetch(t{it}, clamp(p + ivec2({dx},{dy}), ivec2(0), m), 0);\n"
            for o in range(4):
                cols = []
                for c in range(4):
                    ic = it * 4 + c
                    col = [weight[out_group * 16 + o * 4 + r, ic, dy + 1, dx + 1] if ic < in_channels else 0.0 for r in range(4)]
                    cols.append(col)
                s += f"    a{o} += mat4({','.join(f(v) for col in cols for v in col)}) * v;\n"
            s += "  }\n"
    for o in range(4):
        if prelu is not None:
            sl = prelu[o * 4:o * 4 + 4]  # prelu ya viene recortado al grupo
            s += f"  o{o} = max(a{o}, 0.0) + {vec4(sl)} * min(a{o}, 0.0);\n"
        else:
            s += f"  o{o} = a{o};\n"
    s += "}\n"
    return s


SHUFFLE = """#version 300 es
precision highp float;
precision highp int;
uniform highp sampler2D src;
""" + "".join(f"uniform highp sampler2D c{i};\n" for i in range(12)) + """out vec4 color;
float pick(vec4 v, int k) { return k == 0 ? v.x : (k == 1 ? v.y : (k == 2 ? v.z : v.w)); }
void main() {
  ivec2 q = ivec2(gl_FragCoord.xy);
  ivec2 p = q / 4;
  int row = q.y - p.y * 4;
  int col = q.x - p.x * 4;
  vec4 r, g, b;
  if (row == 0) { r = texelFetch(c0, p, 0); g = texelFetch(c4, p, 0); b = texelFetch(c8, p, 0); }
  else if (row == 1) { r = texelFetch(c1, p, 0); g = texelFetch(c5, p, 0); b = texelFetch(c9, p, 0); }
  else if (row == 2) { r = texelFetch(c2, p, 0); g = texelFetch(c6, p, 0); b = texelFetch(c10, p, 0); }
  else { r = texelFetch(c3, p, 0); g = texelFetch(c7, p, 0); b = texelFetch(c11, p, 0); }
  vec3 base = texelFetch(src, p, 0).rgb;
  color = vec4(clamp(base + vec3(pick(r, col), pick(g, col), pick(b, col)), 0.0, 1.0), 1.0);
}
"""

shaders = []
convs = list(model.convs)
for i, conv in enumerate(convs):
    w = conv.weight.detach().numpy()
    b = conv.bias.detach().numpy()
    a = model.acts[i].weight.detach().numpy()
    for g in range(GROUPS):
        shaders.append(conv_shader(w, b, a[g * 16:(g + 1) * 16], w.shape[1], g))
w = model.last.weight.detach().numpy()
b = model.last.bias.detach().numpy()
for g in range(3):
    shaders.append(conv_shader(w, b, None, w.shape[1], g))


def c_string(src):
    return "\n".join('"' + line.replace("\\", "\\\\").replace('"', '\\"') + '\\n"' for line in src.splitlines())


with open(out_path, "w") as fo:
    fo.write("// Generado por tools/ai/gen_shaders.py a partir del modelo entrenado. No editar a mano.\n#pragma once\n")
    fo.write(f"#define AI_CH {CH}\n#define AI_LAYERS {len(convs)}\n#define AI_GROUPS {GROUPS}\n#define AI_LAST_PASSES 3\n#define AI_SCALE 4\n")
    fo.write("static const char* const AI_CONV_SHADERS[] = {\n")
    for sh in shaders:
        fo.write(c_string(sh) + ",\n")
    fo.write("};\n")
    fo.write("static const char* const AI_SHUFFLE_SHADER =\n" + c_string(SHUFFLE) + ";\n")
print(out_path, len(shaders) + 1, "shaders")
