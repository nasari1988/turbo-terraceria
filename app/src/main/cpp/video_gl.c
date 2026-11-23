// Contexto EGL/GLES3 propio del hilo del emulador y presentación en pantalla.
// El renderizador OpenGL de mGBA dibuja en una textura (a 1x-8x) y aquí se pinta
// en la ventana con escala entera y píxeles nítidos.
#include "video_gl.h"

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <stdio.h>
#include <string.h>

#ifndef EGL_OPENGL_ES3_BIT_KHR
#define EGL_OPENGL_ES3_BIT_KHR 0x40
#endif

static EGLDisplay dpy = EGL_NO_DISPLAY;
static EGLContext ctx = EGL_NO_CONTEXT;
static EGLConfig cfg;
static EGLSurface pbuf = EGL_NO_SURFACE;
static EGLSurface win = EGL_NO_SURFACE;
static GLuint prog, vao, loc_tex;
static char info[1024] = "sin GPU";

static const char* VS =
	"#version 300 es\n"
	"out vec2 uv;\n"
	"void main() {\n"
	"  vec2 p = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));\n"
	"  uv = vec2(p.x, 1.0 - p.y);\n"
	"  gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);\n"
	"}\n";

static const char* FS =
	"#version 300 es\n"
	"precision mediump float;\n"
	"uniform sampler2D tex;\n"
	"in vec2 uv;\n"
	"out vec4 color;\n"
	"void main() { color = vec4(texture(tex, uv).rgb, 1.0); }\n";

static GLuint compile(GLenum type, const char* src) {
	GLuint s = glCreateShader(type);
	glShaderSource(s, 1, &src, NULL);
	glCompileShader(s);
	GLint ok = 0;
	glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
	if (!ok) {
		char log[512];
		glGetShaderInfoLog(s, sizeof(log), NULL, log);
		fprintf(stderr, "shader: %s\n", log);
	}
	return s;
}

bool video_init(void* native_display) {
	if (ctx != EGL_NO_CONTEXT) return true;
	dpy = native_display ? (EGLDisplay) native_display : eglGetDisplay(EGL_DEFAULT_DISPLAY);
	if (dpy == EGL_NO_DISPLAY || !eglInitialize(dpy, NULL, NULL)) return false;
	eglBindAPI(EGL_OPENGL_ES_API);

	const EGLint attrs[] = {
		EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
		EGL_SURFACE_TYPE, EGL_PBUFFER_BIT | EGL_WINDOW_BIT,
		EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
		EGL_NONE,
	};
	EGLint n = 0;
	if (!eglChooseConfig(dpy, attrs, &cfg, 1, &n) || n < 1) {
		// algunos entornos (pruebas en PC) no ofrecen ventanas
		const EGLint attrs2[] = {
			EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
			EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_NONE,
		};
		if (!eglChooseConfig(dpy, attrs2, &cfg, 1, &n) || n < 1) return false;
	}
	const EGLint cattrs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
	ctx = eglCreateContext(dpy, cfg, EGL_NO_CONTEXT, cattrs);
	if (ctx == EGL_NO_CONTEXT) return false;
	const EGLint pattrs[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
	pbuf = eglCreatePbufferSurface(dpy, cfg, pattrs);
	if (!eglMakeCurrent(dpy, pbuf, pbuf, ctx)) return false;

	prog = glCreateProgram();
	glAttachShader(prog, compile(GL_VERTEX_SHADER, VS));
	glAttachShader(prog, compile(GL_FRAGMENT_SHADER, FS));
	glLinkProgram(prog);
	loc_tex = glGetUniformLocation(prog, "tex");

	// Datos para el reporte de diagnóstico
	GLint bufs = 0, ext_n = 0;
	glGetIntegerv(GL_MAX_DRAW_BUFFERS, &bufs);
	glGetIntegerv(GL_NUM_EXTENSIONS, &ext_n);
	bool timer = false, cbf = false;
	for (GLint i = 0; i < ext_n; ++i) {
		const char* e = (const char*) glGetStringi(GL_EXTENSIONS, i);
		if (!e) continue;
		if (!strcmp(e, "GL_EXT_disjoint_timer_query")) timer = true;
		if (!strcmp(e, "GL_EXT_color_buffer_float") || !strcmp(e, "GL_EXT_color_buffer_half_float")) cbf = true;
	}
	snprintf(info, sizeof(info), "%s | %s | %s | MRT %d | timer %s | float FBO %s",
		(const char*) glGetString(GL_VENDOR), (const char*) glGetString(GL_RENDERER),
		(const char*) glGetString(GL_VERSION), bufs, timer ? "sí" : "no", cbf ? "sí" : "no");
	glGenVertexArrays(1, &vao);
	return true;
}

void video_deinit(void) {
	if (ctx == EGL_NO_CONTEXT) return;
	eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
	if (win != EGL_NO_SURFACE) eglDestroySurface(dpy, win);
	if (pbuf != EGL_NO_SURFACE) eglDestroySurface(dpy, pbuf);
	eglDestroyContext(dpy, ctx);
	win = pbuf = EGL_NO_SURFACE;
	ctx = EGL_NO_CONTEXT;
}

bool video_set_window(void* native_window) {
	if (ctx == EGL_NO_CONTEXT) return false;
	eglMakeCurrent(dpy, pbuf, pbuf, ctx);
	if (win != EGL_NO_SURFACE) {
		eglDestroySurface(dpy, win);
		win = EGL_NO_SURFACE;
	}
	if (!native_window) return true;
	win = eglCreateWindowSurface(dpy, cfg, (EGLNativeWindowType) native_window, NULL);
	if (win == EGL_NO_SURFACE) return false;
	eglMakeCurrent(dpy, win, win, ctx);
	eglSwapInterval(dpy, 0);  // el ritmo lo marca el audio
	return true;
}

void video_present(unsigned tex, int tex_w, int tex_h, bool smooth) {
	if (win == EGL_NO_SURFACE) return;
	eglMakeCurrent(dpy, win, win, ctx);
	EGLint ww = 0, wh = 0;
	eglQuerySurface(dpy, win, EGL_WIDTH, &ww);
	eglQuerySurface(dpy, win, EGL_HEIGHT, &wh);

	glBindFramebuffer(GL_FRAMEBUFFER, 0);
	glDisable(GL_SCISSOR_TEST);
	glDisable(GL_BLEND);
	glDisable(GL_DEPTH_TEST);
	glDisable(GL_STENCIL_TEST);
	glViewport(0, 0, ww, wh);
	glClearColor(0, 0, 0, 1);
	glClear(GL_COLOR_BUFFER_BIT);

	// escala entera según la pantalla (no según la textura): 8x en el Poco F7
	int s = ww / VIDEO_W < wh / VIDEO_H ? ww / VIDEO_W : wh / VIDEO_H;
	if (s < 1) s = 1;
	int w = VIDEO_W * s, h = VIDEO_H * s;
	glViewport((ww - w) / 2, (wh - h) / 2, w, h);

	glUseProgram(prog);
	glBindVertexArray(vao);
	glActiveTexture(GL_TEXTURE0);
	glBindTexture(GL_TEXTURE_2D, tex);
	// nítido si la textura cabe entera; suave si hay que reducirla
	GLint filter = (!smooth && tex_w <= w && tex_h <= h) ? GL_NEAREST : GL_LINEAR;
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
	glUniform1i(loc_tex, 0);
	glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
	glBindVertexArray(0);
	eglSwapBuffers(dpy, win);
}

void video_make_current(void) {
	if (ctx == EGL_NO_CONTEXT) return;
	EGLSurface s = win != EGL_NO_SURFACE ? win : pbuf;
	eglMakeCurrent(dpy, s, s, ctx);
}

const char* video_info(void) {
	return info;
}
