#pragma once

#include <stdbool.h>

#define VIDEO_W 240
#define VIDEO_H 160

// Todo esto se llama solo desde el hilo del emulador.
bool video_init(void* native_display);  // NULL = pantalla por defecto
void video_deinit(void);
bool video_set_window(void* native_window);  // NULL = sin ventana
// Tamaño lógico del cuadro para la escala entera (240x160 en GBA; 256x224 en SNES).
void video_set_base(int w, int h);
void video_present(unsigned tex, int tex_w, int tex_h, bool smooth);  // smooth: filtro lineal
void video_make_current(void);
const char* video_info(void);  // GPU, versión y extensiones (para el reporte)
