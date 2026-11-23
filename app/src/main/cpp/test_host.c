// Prueba en PC (no se incluye en el APK): carga una ROM, corre N cuadros
// y guarda el último en PPM. Con escala >= 0 usa GL sin pantalla (Mesa).
//   gbatest rom.gba cuadros salida.ppm [escala] [teclas]
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <stdio.h>
#include <stdlib.h>

#include "ai_gl.h"
#include "gba_core.h"
#include "video_gl.h"

int main(int argc, char** argv) {
	if (argc < 4) {
		fprintf(stderr, "uso: %s rom.gba cuadros salida.ppm [escala] [teclas]\n", argv[0]);
		return 1;
	}
	int scale = argc > 4 ? atoi(argv[4]) : -1;
	uint32_t keys = argc > 5 ? strtoul(argv[5], NULL, 0) : 0;

	FILE* f = fopen(argv[1], "rb");
	if (!f) return 1;
	fseek(f, 0, SEEK_END);
	long size = ftell(f);
	fseek(f, 0, SEEK_SET);
	void* rom = malloc(size);
	if (fread(rom, 1, size, f) != (size_t) size) return 1;
	fclose(f);

	if (scale >= 0) {
		PFNEGLGETPLATFORMDISPLAYEXTPROC getDisplay =
			(PFNEGLGETPLATFORMDISPLAYEXTPROC) eglGetProcAddress("eglGetPlatformDisplayEXT");
		EGLDisplay d = getDisplay(EGL_PLATFORM_SURFACELESS_MESA, EGL_DEFAULT_DISPLAY, NULL);
		if (!video_init(d)) {
			fprintf(stderr, "sin GL\n");
			return 1;
		}
		printf("GL: %s | %s\n", glGetString(GL_RENDERER), glGetString(GL_VERSION));
		gba_use_gl(true);
	}
	if (!gba_load(rom, size, NULL, scale < 0 ? 0 : scale)) {
		fprintf(stderr, "no se pudo cargar\n");
		return 1;
	}
	static int16_t audio[4096 * 2];
	int frames = atoi(argv[2]);
	for (int i = 0; i < frames; ++i) gba_run_frame(i > 5 ? keys : 0, audio, 4096);

	int w = GBA_W, h = GBA_H;
	uint32_t* px;
	if (scale >= 0) {
		unsigned tex;
		bool smooth;
		gba_frame_texture(&tex, &w, &h, &smooth);
		px = malloc(w * h * 4);
		GLuint fbo;
		glGenFramebuffers(1, &fbo);
		glBindFramebuffer(GL_FRAMEBUFFER, fbo);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
		glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, px);
		printf("textura %ux%d err=%x\n", w, h, glGetError());
	} else {
		px = malloc(w * h * 4);
		gba_copy_pixels(px);
	}

	// Fila 0 de la textura = primera fila guardada (se verifica orientación al ver la imagen)
	FILE* o = fopen(argv[3], "wb");
	fprintf(o, "P6 %d %d 255\n", w, h);
	for (int i = 0; i < w * h; ++i) {
		unsigned char rgb[3] = {px[i] & 0xFF, (px[i] >> 8) & 0xFF, (px[i] >> 16) & 0xFF};
		fwrite(rgb, 1, 3, o);
	}
	fclose(o);
	gba_unload();
	ai_deinit();
	ai1_deinit();
	video_deinit();
	return 0;
}
