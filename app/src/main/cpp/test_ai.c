// Prueba en PC: pasa una imagen RGBA cruda de 240x160 por la IA en shaders y guarda 960x640.
//   gbaai entrada.rgba salida.rgba [repeticiones]
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <stdio.h>
#include <stdlib.h>
#include <time.h>

#include "ai_gl.h"
#include "video_gl.h"

int main(int argc, char** argv) {
	if (argc < 3) return 1;
	int reps = argc > 3 ? atoi(argv[3]) : 1;
	static unsigned char in[240 * 160 * 4];
	static unsigned char out[960 * 640 * 4];
	FILE* f = fopen(argv[1], "rb");
	if (!f || fread(in, 1, sizeof(in), f) != sizeof(in)) return 1;
	fclose(f);

	PFNEGLGETPLATFORMDISPLAYEXTPROC getDisplay =
		(PFNEGLGETPLATFORMDISPLAYEXTPROC) eglGetProcAddress("eglGetPlatformDisplayEXT");
	if (!video_init(getDisplay(EGL_PLATFORM_SURFACELESS_MESA, EGL_DEFAULT_DISPLAY, NULL))) return 2;
	if (!ai_init()) {
		fprintf(stderr, "ai_init falló\n");
		return 3;
	}
	GLuint tex;
	glGenTextures(1, &tex);
	glBindTexture(GL_TEXTURE_2D, tex);
	glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 240, 160, 0, GL_RGBA, GL_UNSIGNED_BYTE, in);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);

	struct timespec a, b;
	clock_gettime(CLOCK_MONOTONIC, &a);
	unsigned o = 0;
	for (int i = 0; i < reps; ++i) o = ai_run(tex);
	glFinish();
	clock_gettime(CLOCK_MONOTONIC, &b);
	printf("%.1f ms por cuadro (GPU simulada por CPU)\n", ((b.tv_sec - a.tv_sec) * 1e3 + (b.tv_nsec - a.tv_nsec) / 1e6) / reps);

	GLuint fbo;
	glGenFramebuffers(1, &fbo);
	glBindFramebuffer(GL_FRAMEBUFFER, fbo);
	glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, o, 0);
	glReadPixels(0, 0, 960, 640, GL_RGBA, GL_UNSIGNED_BYTE, out);
	f = fopen(argv[2], "wb");
	fwrite(out, 1, sizeof(out), f);
	fclose(f);
	ai_deinit();
	video_deinit();
	return 0;
}
