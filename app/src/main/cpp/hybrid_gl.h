#pragma once

#include <stdbool.h>
#include <stdint.h>

// Todo en el hilo del emulador, con el renderizador de GPU de mGBA activo a [scale].
bool hybrid_enable(int scale);
void hybrid_disable(void);
// Tras correr un cuadro: guarda la imagen 8x y la máscara en [slot] (0/1) y deja el cuadro 1x
// (240x160 RGBX) en [frame] para la NPU.
bool hybrid_capture(int slot, uint32_t* frame);
// Combina la imagen 8x del [slot] con la salida de la IA (960x640). Devuelve la textura final.
unsigned hybrid_mix(int slot, unsigned ai_tex);
int hybrid_scale(void);
// show: tiñe de rosa lo que va a 8x. automatic: el renderizador baja a 1x cuando no hay Mode 7.
void hybrid_set_options(bool show, bool automatic);
// Último cuadro: líneas con Mode 7, modos de video vistos (bits) y escala actual del renderizador.
void hybrid_info(int* lines, int* modes, int* scale_now);
