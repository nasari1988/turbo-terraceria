// Prueba en PC de la ruta int8 de la NPU (npu_compose_frame / npu_compose_frame_h).
//   gbanpuq gba salida.rgba   -> 240x160 con salida int8 pseudoaleatoria (ruta del GBA; imprime hash)
//   gbanpuq snes              -> 256x224 con salida en cero: debe dar el cuadro ampliado con nearest
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "npu_gl.h"
#include "video_gl.h"

static unsigned long hash(const unsigned char* p, size_t n) {
	unsigned long h = 5381;
	for (size_t i = 0; i < n; ++i) h = h * 33 + p[i];
	return h;
}

static void leer(unsigned tex, int w, int h, unsigned char* out) {
	GLuint fbo;
	glGenFramebuffers(1, &fbo);
	glBindFramebuffer(GL_FRAMEBUFFER, fbo);
	glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
	glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, out);
	glBindFramebuffer(GL_FRAMEBUFFER, 0);
	glDeleteFramebuffers(1, &fbo);
}

int main(int argc, char** argv) {
	if (argc < 2) return 1;
	PFNEGLGETPLATFORMDISPLAYEXTPROC getDisplay =
		(PFNEGLGETPLATFORMDISPLAYEXTPROC) eglGetProcAddress("eglGetPlatformDisplayEXT");
	if (!video_init(getDisplay(EGL_PLATFORM_SURFACELESS_MESA, EGL_DEFAULT_DISPLAY, NULL))) return 2;

	if (!strcmp(argv[1], "gba")) {
		int w = 240, h = 160;
		static uint32_t frame[240 * 160];
		static int8_t raw[240 * 160 * 48];
		static unsigned char out[960 * 640 * 4];
		srand(7);
		for (int i = 0; i < w * h; ++i) frame[i] = 0xFF000000u | (rand() & 0xFFFFFF);
		for (int i = 0; i < w * h * 48; ++i) raw[i] = (int8_t) (rand() % 61 - 30);
		unsigned t = npu_compose_frame(raw, NPU_I8, 0.01f, 3, frame);
		if (!t) return 3;
		leer(t, w * 4, h * 4, out);
		printf("gba hash %lu\n", hash(out, sizeof(out)));
		if (argc > 2) {
			FILE* f = fopen(argv[2], "wb");
			fwrite(out, 1, sizeof(out), f);
			fclose(f);
		}
	}
#ifndef SOLO_GBA
	else {
		int w = 256, h = 224, fh = argc > 2 ? atoi(argv[2]) : 224;
		static uint32_t frame[256 * 224];
		static int8_t raw[256 * 224 * 48];
		static unsigned char out[1024 * 896 * 4];
		for (int y = 0; y < fh; ++y)
			for (int x = 0; x < w; ++x)
				frame[y * w + x] = 0xFF000000u | ((x * 3) & 0xFF) | (((y * 5) & 0xFF) << 8) | (((x ^ y) & 0xFF) << 16);
		npu_set_size(w, h);
		memset(raw, 0, sizeof(raw));
		// con zp = 0 y salida cero el residuo es nulo
		unsigned t = npu_compose_frame_h(raw, NPU_I8, 0.01f, 0, frame, fh);
		if (!t) return 3;
		leer(t, w * 4, fh * 4, out);
		int bad = 0;
		for (int y = 0; y < fh * 4; ++y)
			for (int x = 0; x < w * 4; ++x) {
				uint32_t e = frame[(y / 4) * w + x / 4];
				const unsigned char* p = out + (y * w * 4 + x) * 4;
				if (p[0] != (e & 0xFF) || p[1] != ((e >> 8) & 0xFF) || p[2] != ((e >> 16) & 0xFF)) bad++;
			}
		printf("snes %dx%d: %d pixeles distintos de %d\n", w * 4, fh * 4, bad, w * 4 * fh * 4);
		// la entrada: filas repetidas
		static int8_t in[256 * 224 * 3];
		npu_fill_input_q_h(frame, in, NPU_I8, 1.0f / 255, -128, fh);
		int bi = 0;
		for (int y = 0; y < h; ++y) {
			int sy = y < fh ? y : fh - 1;
			for (int x = 0; x < w; ++x) {
				uint32_t c = frame[sy * w + x];
				if (in[(y * w + x) * 3] != (int8_t) ((c & 0xFF) - 128)) bi++;
			}
		}
		printf("entrada: %d diferencias\n", bi);
		if (bad || bi) return 4;
	}
#endif
	npu_gl_deinit();
	video_deinit();
	return 0;
}
