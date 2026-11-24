// Prueba en PC: corre una ROM de SNES y guarda cuadros. Uso: snestest rom.sfc cuadros salida [escala_mode7]
#include <stdio.h>
#include <stdlib.h>
#include "snes_core.h"

static void ppm(const char* path, const uint32_t* px, int w, int h) {
	FILE* f = fopen(path, "wb");
	fprintf(f, "P6\n%d %d\n255\n", w, h);
	for (int i = 0; i < w * h; ++i) {
		unsigned char c[3] = { px[i] & 255, (px[i] >> 8) & 255, (px[i] >> 16) & 255 };
		fwrite(c, 1, 3, f);
	}
	fclose(f);
}

int main(int argc, char** argv) {
	if (argc < 4) return 1;
	int frames = atoi(argv[2]);
	if (argc > 4) snes_set_option("bsnes_mode7_scale", argv[4]);
	snes_set_dirs("/tmp", "/tmp");
	if (!snes_load(argv[1], NULL)) { fprintf(stderr, "no carga\n"); return 2; }
	int16_t audio[4096 * 2];
	long samples = 0;
	for (int i = 0; i < frames; ++i) {
		snes_run_frame(i > 30 && (i / 20) % 2 ? SNES_START : 0);
		int n;
		while ((n = snes_audio(audio, 4096)) > 0) samples += n;
	}
	int w, h;
	const uint32_t* px = snes_frame(&w, &h);
	char out[512];
	snprintf(out, sizeof(out), "%s.ppm", argv[3]);
	ppm(out, px, w, h);
	printf("cuadro %dx%d · audio %ld muestras (%.0f Hz, %.2f fps)\n", w, h, samples, snes_sample_rate(), snes_fps());
	snes_unload();
	return 0;
}
