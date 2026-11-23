#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define GBA_W 240
#define GBA_H 160
#define GBA_AUDIO_RATE 48000
#define GBA_SCALE_AI 100  // modo IA 4x

// Botones del GBA (bits)
enum {
	KEY_A = 1 << 0,
	KEY_B = 1 << 1,
	KEY_SELECT = 1 << 2,
	KEY_START = 1 << 3,
	KEY_RIGHT = 1 << 4,
	KEY_LEFT = 1 << 5,
	KEY_UP = 1 << 6,
	KEY_DOWN = 1 << 7,
	KEY_R = 1 << 8,
	KEY_L = 1 << 9,
};

// Llamar con true si hay un contexto GLES3 activo en este hilo (antes de gba_load).
void gba_use_gl(bool on);
// video_scale: 0 = software (exacto), 1-8 = renderizador GPU a esa escala, GBA_SCALE_AI = IA 4x.
bool gba_load(const void* rom, size_t size, const char* save_path, int video_scale);
void gba_unload(void);
void gba_set_scale(int video_scale);
int gba_get_scale(void);
// Ejecuta un cuadro. Devuelve cuadros de audio estéreo escritos en audio.
int gba_run_frame(uint32_t keys, int16_t* audio, int audio_cap);
// Último cuadro: como textura GL (requiere GL) o como píxeles (solo modo software).
void gba_frame_texture(unsigned* tex, int* w, int* h, bool* smooth);
void gba_copy_pixels(uint32_t* pixels);
bool gba_save_state(const char* path);
bool gba_load_state(const char* path);
