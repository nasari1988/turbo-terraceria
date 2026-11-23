// Demo Mode 7 original para probar el emulador. Cruceta: girar y avanzar. A: más rápido.
#include <stdint.h>

#include "assets.h"

#define REG16(a) (*(volatile uint16_t*) (a))
#define REG32(a) (*(volatile uint32_t*) (a))
#define DISPCNT REG16(0x4000000)
#define VCOUNT REG16(0x4000006)
#define BG0CNT REG16(0x4000008)
#define BG2CNT REG16(0x400000C)
#define KEYS REG16(0x4000130)
#define DMA0SAD REG32(0x40000B0)
#define DMA0DAD REG32(0x40000B4)
#define DMA0CNT REG32(0x40000B8)
#define DMA1SAD REG32(0x40000BC)
#define DMA1DAD REG32(0x40000C0)
#define DMA1CNT REG32(0x40000C4)
#define BG2_AFF ((volatile struct Affine*) 0x4000020)

#define VRAM16 ((volatile uint16_t*) 0x6000000)
#define MAP16 ((volatile uint16_t*) 0x6008000)
#define PAL16 ((volatile uint16_t*) 0x5000000)
#define OBJPAL16 ((volatile uint16_t*) 0x5000200)
#define BG0TILES16 ((volatile uint16_t*) 0x6004000)
#define BG0MAP16 ((volatile uint16_t*) 0x600E000)
#define OBJTILES16 ((volatile uint16_t*) 0x6010000)
#define OAM16 ((volatile uint16_t*) 0x7000000)

#define FOCAL 160

struct Affine {
	int16_t pa, pb, pc, pd;
	int32_t x, y;
};

static struct Affine table[2][160];

static void copy16(volatile uint16_t* dst, const void* src, int bytes) {
	const uint16_t* s = src;
	for (int i = 0; i < bytes / 2; ++i) dst[i] = s[i];
}

static void build(struct Affine* t, int32_t cx8, int32_t cy8, int angle) {
	int32_t s = sin_lut[angle & 255];
	int32_t c = sin_lut[(angle + 64) & 255];
	for (int y = 0; y < 160; ++y) {
		if (y <= HORIZON) {
			// cielo: apunta fuera del mapa (sin repetición = transparente)
			t[y].pa = t[y].pb = t[y].pc = t[y].pd = 0;
			t[y].x = t[y].y = -(1 << 20);
			continue;
		}
		int32_t zd = zd8[y];
		int32_t pa = (zd * c) >> 12;  // dU/dx en 8.8
		int32_t pc = (zd * s) >> 12;  // dV/dx en 8.8
		t[y].pa = pa;
		t[y].pc = pc;
		t[y].pb = t[y].pd = 0;
		t[y].x = cx8 + pc * FOCAL - 120 * pa;
		t[y].y = cy8 - pa * FOCAL - 120 * pc;
	}
}

static void start_dma(const struct Affine* t) {
	DMA0CNT = 0;
	DMA1CNT = 0;
	*BG2_AFF = t[0];
	PAL16[0] = sky[0];
	// cada HBlank copia los 16 bytes de la siguiente línea a BG2PA..BG2Y
	DMA0SAD = (uint32_t) &t[1];
	DMA0DAD = 0x4000020;
	DMA0CNT = 4 | (3u << 21) | (1u << 25) | (1u << 26) | (2u << 28) | (1u << 31);
	// y el degradado del cielo en el color de fondo
	DMA1SAD = (uint32_t) &sky[1];
	DMA1DAD = 0x5000000;
	DMA1CNT = 1 | (2u << 21) | (1u << 25) | (2u << 28) | (1u << 31);
}

int main(void) {
	copy16(PAL16, palette, sizeof(palette));
	copy16(VRAM16, tiles, sizeof(tiles));
	copy16(MAP16, tilemap, sizeof(tilemap));
	copy16(BG0TILES16, bg0_tiles, sizeof(bg0_tiles));
	copy16(BG0MAP16, bg0_map, sizeof(bg0_map));
	copy16(OBJTILES16, kart_tiles, sizeof(kart_tiles));
	copy16(OBJPAL16, obj_palette, sizeof(obj_palette));
	// Sprites: el kart (32x32, 4bpp) y los demás ocultos
	for (int i = 0; i < 128; ++i) OAM16[i * 4] = 0x200;
	OAM16[0] = 112;               // y, cuadrado, 4bpp
	OAM16[1] = 104 | (2 << 14);   // x, tamaño 32x32
	OAM16[2] = 0;                 // tile 0, prioridad 0, paleta 0
	// BG0 (2D, 4bpp): tiles en bloque 1, mapa en bloque de pantalla 28, encima de todo
	BG0CNT = (0 << 0) | (1 << 2) | (28 << 8);
	// BG2 afín: bloque de tiles 0, mapa en bloque 16 (0x6008000), 1024x1024, sin repetición
	BG2CNT = (1 << 0) | (0 << 2) | (16 << 8) | (3 << 14);
	// Modo 1: BG0 normal + BG2 afín, sprites con mapeo 1D
	DISPCNT = 1 | (1 << 6) | (1 << 8) | (1 << 10) | (1 << 12);

	int32_t cx8 = (512 - 90) << 8;
	int32_t cy8 = (512 + 26 * 8 + 12) << 8;  // en la recta, antes de la meta
	int angle = 64;                          // mirando a la derecha por la recta
	int cur = 0;
	build(table[cur], cx8, cy8, angle);

	for (;;) {
		while (VCOUNT >= 160) {}
		while (VCOUNT < 160) {}
		start_dma(table[cur]);

		uint16_t k = ~KEYS;
		int speed = (k & 1) ? 6 : 3;
		if (k & (1 << 4)) angle += 1;  // derecha
		if (k & (1 << 5)) angle -= 1;  // izquierda
		int32_t s = sin_lut[angle & 255];
		int32_t c = sin_lut[(angle + 64) & 255];
		if (k & (1 << 6)) { cx8 += (s * speed) >> 4; cy8 -= (c * speed) >> 4; }
		if (k & (1 << 7)) { cx8 -= (s * speed) >> 4; cy8 += (c * speed) >> 4; }

		cur ^= 1;
		build(table[cur], cx8, cy8, angle);
	}
}
