# IA de escalado

- `synth.py`, `make_dataset.py`: datos (escenas propias + pixel art de Kenney, CC0).
- `student.py`, `train.py`: la red y su entrenamiento (imita a Real-ESRGAN anime_6B).
- `gen_shaders.py`: modelo -> shaders GLES3 (`app/src/main/cpp/ai_shaders*.h`). Máx. 144 matrices por shader (límite del Adreno).
- `sim_fp16.py`: simula la red en 16 bits para medir el error de fp16.
- `to_tflite.py`: modelo -> LiteRT para la NPU (`app/src/main/assets/student_v2.tflite`).
  Solo el cuerpo de la red (salida de 48 canales); el pixel shuffle lo hace la GPU (`npu_gl.c`).
