#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define GBA_W 240
#define GBA_H 160
#define GBA_AUDIO_RATE 48000

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

bool gba_load(const void* rom, size_t size, const char* save_path);
void gba_unload(void);
// Ejecuta un cuadro. pixels: 240*160 ARGB. Devuelve cuadros de audio estéreo escritos.
int gba_run_frame(uint32_t keys, uint32_t* pixels, int16_t* audio, int audio_cap);
bool gba_save_state(const char* path);
bool gba_load_state(const char* path);
