#pragma once
#include <stdbool.h>
#include <stdint.h>

// Frontend mínimo de libretro para el núcleo de SNES (bsnes-hd), enlazado en la misma biblioteca.

// Botones (bits), en el orden de libretro: B Y Select Start Arriba Abajo Izq Der A X L R
enum {
	SNES_B = 1 << 0, SNES_Y = 1 << 1, SNES_SELECT = 1 << 2, SNES_START = 1 << 3,
	SNES_UP = 1 << 4, SNES_DOWN = 1 << 5, SNES_LEFT = 1 << 6, SNES_RIGHT = 1 << 7,
	SNES_A = 1 << 8, SNES_X = 1 << 9, SNES_L = 1 << 10, SNES_R = 1 << 11,
};

// Carpetas para BIOS de chips especiales y guardados del núcleo (antes de snes_load).
void snes_set_dirs(const char* system_dir, const char* save_dir);
// Opción del núcleo, p. ej. ("bsnes_mode7_scale", "4x"). Se puede cambiar en marcha.
void snes_set_option(const char* key, const char* value);
// Valor actual de una opción (o el predeterminado del núcleo).
const char* snes_get_option(const char* key);

bool snes_load(const char* rom_path, const char* sram_path);
void snes_unload(void);
bool snes_loaded(void);

void snes_run_frame(uint16_t keys);

// Último cuadro en RGBA (bytes R,G,B,A), ancho y alto reales de ese cuadro.
const uint32_t* snes_frame(int* w, int* h);
// Ancho y alto "normales" (256x224): con Mode 7 HD el cuadro puede salir más grande.
void snes_base_size(int* w, int* h);

// Audio intercalado estéreo de 16 bits generado desde la última llamada; devuelve cuántos cuadros.
int snes_audio(int16_t* out, int max_frames);
double snes_sample_rate(void);
double snes_fps(void);

bool snes_save_sram(void);
bool snes_save_state(const char* path);
bool snes_load_state(const char* path);
