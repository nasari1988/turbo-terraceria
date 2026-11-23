"""Cuantiza el modelo LiteRT para la NPU del Poco F7 (el HTP del SM8735 no acepta fp16).
Genera dos variantes con calibración sobre pixel art:
  - a16w8: pesos int8, activaciones int16 (más precisa)
  - a8w8:  pesos int8, activaciones int8  (más rápida)
Uso: quantize.py modelo_float.tflite carpeta_calibracion salida_dir"""
import glob
import os
import random
import sys

import numpy as np
from PIL import Image
from ai_edge_quantizer import quantizer, recipe

src, calib_dir, out_dir = sys.argv[1], sys.argv[2], sys.argv[3]
random.seed(3)
files = sorted(glob.glob(f"{calib_dir}/*.png"))
random.shuffle(files)
samples = []
for f in files:
    im = Image.open(f).convert("RGB")
    if im.size != (240, 160):
        continue
    samples.append({"args_0": (np.asarray(im, dtype=np.float32) / 255)[None]})
    if len(samples) >= 160:
        break
print(len(samples), "imágenes de calibración")

for name, rec in [("a16w8", recipe.static_wi8_ai16()), ("a8w8", recipe.static_wi8_ai8())]:
    qt = quantizer.Quantizer(src)
    qt.load_quantization_recipe(rec)
    calib = qt.calibrate({"serving_default": samples})
    res = qt.quantize(calib)
    path = os.path.join(out_dir, f"student_v2_{name}.tflite")
    res.export_model(path, overwrite=True)
    print("guardado", path, os.path.getsize(path), "bytes")
