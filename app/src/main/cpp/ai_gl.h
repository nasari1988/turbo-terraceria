#pragma once

#include <stdbool.h>

// Requiere contexto GLES3 activo. Devuelve false si la GPU no soporta lo necesario.
bool ai_init(void);
// Escala 4x la textura de 240x160 con la red de IA. Devuelve la textura de 960x640.
unsigned ai_run(unsigned in_tex);
// Libera todo (llamar antes de destruir el contexto).
void ai_deinit(void);

// Modelo ligero (v1)
bool ai1_init(void);
unsigned ai1_run(unsigned in_tex);
void ai1_deinit(void);
