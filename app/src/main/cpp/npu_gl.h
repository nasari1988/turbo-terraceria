#pragma once

#include <stdbool.h>
#include <stdint.h>

// Convierte el cuadro (240x160, RGBX) a la entrada de la NPU: float [160,240,3] en 0..1.
void npu_fill_input(const uint32_t* video, float* in);
// Sube la salida de la NPU (float [160,240,48]) y hace pixel shuffle 4x + residuo con src_tex.
// Devuelve la textura de 960x640 (0 si falla). Requiere contexto GL.
unsigned npu_compose(const float* features, unsigned src_tex);
void npu_gl_deinit(void);
