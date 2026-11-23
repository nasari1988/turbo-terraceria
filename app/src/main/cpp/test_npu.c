// Prueba en PC del modo NPU: toma un cuadro RGBA y la salida del .tflite (float [160,240,48]),
// hace el pixel shuffle en GPU (npu_compose) y guarda 960x640.
//   gbanpu cuadro.rgba salida_npu.f32 resultado.rgba entrada_npu.f32
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <stdio.h>
#include <stdlib.h>

#include "npu_gl.h"
#include "video_gl.h"

int main(int argc, char** argv) {
	if (argc < 5) return 1;
	static uint32_t frame[240 * 160];
	static float feat[160 * 240 * 48];
	static float in[160 * 240 * 3];
	static unsigned char out[960 * 640 * 4];
	FILE* f = fopen(argv[1], "rb");
	if (!f || fread(frame, 4, 240 * 160, f) != 240 * 160) return 1;
	fclose(f);
	f = fopen(argv[2], "rb");
	if (!f || fread(feat, 4, 160 * 240 * 48, f) != 160 * 240 * 48) return 1;
	fclose(f);

	npu_fill_input(frame, in);  // lo que recibiría la NPU
	f = fopen(argv[4], "wb");
	fwrite(in, 4, 160 * 240 * 3, f);
	fclose(f);

	PFNEGLGETPLATFORMDISPLAYEXTPROC getDisplay =
		(PFNEGLGETPLATFORMDISPLAYEXTPROC) eglGetProcAddress("eglGetPlatformDisplayEXT");
	if (!video_init(getDisplay(EGL_PLATFORM_SURFACELESS_MESA, EGL_DEFAULT_DISPLAY, NULL))) return 2;
	printf("GPU: %s\n", video_info());
	GLuint src;
	glGenTextures(1, &src);
	glBindTexture(GL_TEXTURE_2D, src);
	glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 240, 160, 0, GL_RGBA, GL_UNSIGNED_BYTE, frame);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
	unsigned t = npu_compose(feat, src);
	if (!t) return 3;
	GLuint fbo;
	glGenFramebuffers(1, &fbo);
	glBindFramebuffer(GL_FRAMEBUFFER, fbo);
	glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, t, 0);
	glReadPixels(0, 0, 960, 640, GL_RGBA, GL_UNSIGNED_BYTE, out);
	f = fopen(argv[3], "wb");
	fwrite(out, 1, sizeof(out), f);
	fclose(f);
	npu_gl_deinit();
	video_deinit();
	return 0;
}
