#pragma once

#include <stdbool.h>
#include <stdint.h>

// Tipos de tensor de LiteRT que usamos
enum { NPU_F32 = 0, NPU_I8 = 1, NPU_I16 = 2 };

// Convierte el cuadro (240x160, RGBX) a la entrada de la NPU: float [160,240,3] en 0..1.
void npu_fill_input(const uint32_t* video, float* in);
// Igual, pero cuantizado (q = round(v / scale) + zp) si el modelo es int8/int16.
void npu_fill_input_q(const uint32_t* video, void* in, int type, float scale, int zp);
// Pasa la salida cuantizada a float: v = (q - zp) * scale. Devuelve el búfer float (interno si hace falta).
const float* npu_dequant(const void* out, int type, float scale, int zp);
// Sube la salida de la NPU (float [160,240,48]) y hace pixel shuffle 4x + residuo con src_tex.
// Devuelve la textura de 960x640 (0 si falla). Requiere contexto GL.
unsigned npu_compose(const float* features, unsigned src_tex);
// Igual que npu_compose pero con la salida cruda de la NPU (int8 se convierte en el shader)
// y con el cuadro original que le corresponde ([frame], 240x160 RGBX).
unsigned npu_compose_frame(const void* raw, int type, float scale, int zp, const uint32_t* frame);
void npu_gl_deinit(void);
