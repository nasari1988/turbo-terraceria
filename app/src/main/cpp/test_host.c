// Prueba en PC (no se incluye en el APK): carga una ROM, corre N cuadros
// y guarda el último en PPM para revisar que el núcleo funciona.
#include <stdio.h>
#include <stdlib.h>

#include "gba_core.h"

int main(int argc, char** argv) {
	if (argc < 4) {
		fprintf(stderr, "uso: %s rom.gba cuadros salida.ppm\n", argv[0]);
		return 1;
	}
	FILE* f = fopen(argv[1], "rb");
	if (!f) return 1;
	fseek(f, 0, SEEK_END);
	long size = ftell(f);
	fseek(f, 0, SEEK_SET);
	void* rom = malloc(size);
	fread(rom, 1, size, f);
	fclose(f);

	if (!gba_load(rom, size, NULL)) {
		fprintf(stderr, "no se pudo cargar\n");
		return 1;
	}
	static uint32_t px[GBA_W * GBA_H];
	static int16_t audio[4096 * 2];
	int frames = atoi(argv[2]);
	long samples = 0;
	for (int i = 0; i < frames; ++i) samples += gba_run_frame(0, px, audio, 4096);
	printf("cuadros=%d audio=%ld (%.1f por cuadro)\n", frames, samples, (double) samples / frames);

	FILE* o = fopen(argv[3], "wb");
	fprintf(o, "P6 %d %d 255\n", GBA_W, GBA_H);
	for (int i = 0; i < GBA_W * GBA_H; ++i) {
		unsigned char rgb[3] = {px[i] & 0xFF, (px[i] >> 8) & 0xFF, (px[i] >> 16) & 0xFF};
		fwrite(rgb, 1, 3, o);
	}
	fclose(o);
	gba_unload();
	return 0;
}
