// Prueba en PC del modo híbrido: corre la ROM con el renderizador de GPU a 8x y la máscara,
// y guarda la imagen 8x, la máscara (8x) y el cuadro 1x para la NPU.
//   gbahib rom.gba cuadros teclas salida_prefijo
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <stdio.h>
#include <stdlib.h>

#include "gba_core.h"
#include "hybrid_gl.h"
#include "video_gl.h"

static void dump(GLuint t, int w, int h, int ch, const char* path) {
	static unsigned char buf[1920 * 1280 * 4];
	GLuint f;
	glGenFramebuffers(1, &f);
	glBindFramebuffer(GL_FRAMEBUFFER, f);
	glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, t, 0);
	glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, buf);
	FILE* o = fopen(path, "wb");
	fprintf(o, "P%d %d %d 255\n", ch == 1 ? 5 : 6, w, h);
	for (int i = 0; i < w * h; ++i) fwrite(&buf[i * 4], 1, ch, o);
	fclose(o);
	glDeleteFramebuffers(1, &f);
}

int main(int argc, char** argv) {
	FILE* f = fopen(argv[1], "rb");
	fseek(f, 0, SEEK_END);
	long size = ftell(f);
	fseek(f, 0, SEEK_SET);
	void* rom = malloc(size);
	if (fread(rom, 1, size, f) != (size_t) size) return 1;
	fclose(f);
	PFNEGLGETPLATFORMDISPLAYEXTPROC gd = (PFNEGLGETPLATFORMDISPLAYEXTPROC) eglGetProcAddress("eglGetPlatformDisplayEXT");
	if (!video_init(gd(EGL_PLATFORM_SURFACELESS_MESA, EGL_DEFAULT_DISPLAY, NULL))) return 2;
	gba_use_gl(true);
	if (!gba_load(rom, size, NULL, 8)) return 3;
	if (!hybrid_enable(8)) return 4;
	static int16_t audio[8192];
	static uint32_t frame[240 * 160];
	int frames = atoi(argv[2]);
	unsigned keys = strtoul(argv[3], NULL, 0);
	for (int i = 0; i < frames; ++i) {
		gba_run_frame(i > 5 ? keys : 0, audio, 4096);
		// como en el celular: capturar cada cuadro (el híbrido automático decide la escala)
		if (getenv("CADA_CUADRO") && i < frames - 1 && !hybrid_capture(i & 1, frame)) return 6;
	}
	if (!hybrid_capture(0, frame)) return 5;
	int l, m, sc;
	hybrid_info(&l, &m, &sc);
	printf("líneas Mode 7 %d · modos 0x%x · escala ahora %d\n", l, m, sc);
	char path[512];
	unsigned out; int w, h; bool sm;
	gba_frame_texture(&out, &w, &h, &sm);
	snprintf(path, sizeof(path), "%s_8x.ppm", argv[4]);
	dump(out, w, h, 3, path);
	extern unsigned GBAVideoGLAffineMaskTex;
	snprintf(path, sizeof(path), "%s_mascara.pgm", argv[4]);
	dump(GBAVideoGLAffineMaskTex, w, h, 1, path);
	snprintf(path, sizeof(path), "%s_1x.ppm", argv[4]);
	FILE* o = fopen(path, "wb");
	fprintf(o, "P6 240 160 255\n");
	for (int i = 0; i < 240 * 160; ++i) fwrite(&frame[i], 1, 3, o);
	fclose(o);
	printf("listo %dx%d\n", w, h);
	hybrid_disable();
	gba_unload();
	video_deinit();
	return 0;
}
