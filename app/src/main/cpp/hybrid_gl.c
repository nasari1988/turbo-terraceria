// Modo híbrido: el renderizador de GPU de mGBA dibuja a 8x y además marca (máscara) los píxeles
// que vienen de capas afines / Mode 7 (parche patches/mgba-mascara-afin.patch). La IA (NPU) mejora
// la versión 1x. Al final: 8x donde hay Mode 7, IA en el pixel art normal.
// Como la NPU va un cuadro atrás, se guardan 2 copias (slots) de la imagen 8x y de la máscara.
#include "hybrid_gl.h"

#include <GLES3/gl3.h>
#include <stdio.h>

#include "gba_core.h"

extern unsigned GBAVideoGLAffineMaskTex;  // definido en el parche de mGBA

static bool ready = false;
static int cur_scale = 0;
static GLuint mask_live;                  // donde escribe mGBA en cada cuadro
static GLuint hi[2], mk[2];               // copias por slot
static GLuint small_tex, small_fbo;       // cuadro 1x para la NPU
static GLuint comp_tex, comp_fbo;         // resultado combinado
static GLuint read_fbo, draw_fbo, vao;
static GLuint prog_down, prog_mix;

static const char* VS =
	"#version 300 es\n"
	"void main() {\n"
	"  vec2 p = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));\n"
	"  gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);\n"
	"}\n";

// 8x -> 1x tomando el centro de cada bloque (en pixel art normal es exacto)
static const char* FS_DOWN =
	"#version 300 es\n"
	"precision highp float;\n"
	"uniform highp sampler2D hi;\n"
	"uniform int scale;\n"
	"out vec4 color;\n"
	"void main() {\n"
	"  ivec2 p = ivec2(gl_FragCoord.xy);\n"
	"  color = vec4(texelFetch(hi, p * scale + scale / 2, 0).rgb, 1.0);\n"
	"}\n";

// Combinar: máscara 1 -> imagen 8x; 0 -> IA (960x640, filtro lineal)
static const char* FS_MIX =
	"#version 300 es\n"
	"precision highp float;\n"
	"uniform highp sampler2D hi;\n"
	"uniform highp sampler2D mk;\n"
	"uniform highp sampler2D ai;\n"
	"out vec4 color;\n"
	"void main() {\n"
	"  ivec2 p = ivec2(gl_FragCoord.xy);\n"
	"  vec2 uv = (gl_FragCoord.xy) / vec2(textureSize(hi, 0));\n"
	"  float m = texelFetch(mk, p, 0).r;\n"
	"  vec3 a = texture(ai, uv).rgb;\n"
	"  vec3 h = texelFetch(hi, p, 0).rgb;\n"
	"  color = vec4(mix(a, h, m), 1.0);\n"
	"}\n";

static GLuint program(const char* fs) {
	GLuint p = glCreateProgram();
	const char* srcs[2] = {VS, fs};
	GLenum types[2] = {GL_VERTEX_SHADER, GL_FRAGMENT_SHADER};
	for (int i = 0; i < 2; ++i) {
		GLuint s = glCreateShader(types[i]);
		glShaderSource(s, 1, &srcs[i], NULL);
		glCompileShader(s);
		GLint ok = 0;
		glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
		if (!ok) {
			char log[512];
			glGetShaderInfoLog(s, sizeof(log), NULL, log);
			fprintf(stderr, "híbrido shader: %s\n", log);
		}
		glAttachShader(p, s);
		glDeleteShader(s);
	}
	glLinkProgram(p);
	return p;
}

static GLuint tex(int w, int h, GLenum internal, GLenum format, GLenum type, GLenum filter) {
	GLuint t;
	glGenTextures(1, &t);
	glBindTexture(GL_TEXTURE_2D, t);
	glTexImage2D(GL_TEXTURE_2D, 0, internal, w, h, 0, format, type, NULL);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
	return t;
}

static GLuint fbo_for(GLuint t) {
	GLuint f;
	glGenFramebuffers(1, &f);
	glBindFramebuffer(GL_FRAMEBUFFER, f);
	glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, t, 0);
	glBindFramebuffer(GL_FRAMEBUFFER, 0);
	return f;
}

void hybrid_disable(void) {
	GBAVideoGLAffineMaskTex = 0;
	if (!ready) return;
	glDeleteTextures(1, &mask_live);
	glDeleteTextures(2, hi);
	glDeleteTextures(2, mk);
	glDeleteTextures(1, &small_tex);
	glDeleteTextures(1, &comp_tex);
	GLuint fbos[4] = {small_fbo, comp_fbo, read_fbo, draw_fbo};
	glDeleteFramebuffers(4, fbos);
	glDeleteVertexArrays(1, &vao);
	glDeleteProgram(prog_down);
	glDeleteProgram(prog_mix);
	ready = false;
}

bool hybrid_enable(int scale) {
	if (ready && scale == cur_scale) {
		GBAVideoGLAffineMaskTex = mask_live;
		return true;
	}
	hybrid_disable();
	if (scale < 1) return false;
	int w = GBA_W * scale, h = GBA_H * scale;
	mask_live = tex(w, h, GL_R8, GL_RED, GL_UNSIGNED_BYTE, GL_NEAREST);
	for (int i = 0; i < 2; ++i) {
		hi[i] = tex(w, h, GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE, GL_NEAREST);
		mk[i] = tex(w, h, GL_R8, GL_RED, GL_UNSIGNED_BYTE, GL_NEAREST);
	}
	small_tex = tex(GBA_W, GBA_H, GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE, GL_NEAREST);
	comp_tex = tex(w, h, GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE, GL_NEAREST);
	small_fbo = fbo_for(small_tex);
	comp_fbo = fbo_for(comp_tex);
	glGenFramebuffers(1, &read_fbo);
	glGenFramebuffers(1, &draw_fbo);
	glGenVertexArrays(1, &vao);
	prog_down = program(FS_DOWN);
	prog_mix = program(FS_MIX);
	cur_scale = scale;
	ready = true;
	GBAVideoGLAffineMaskTex = mask_live;
	return true;
}

static void blit(GLuint src, GLuint dst, int w, int h) {
	glBindFramebuffer(GL_READ_FRAMEBUFFER, read_fbo);
	glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, src, 0);
	glBindFramebuffer(GL_DRAW_FRAMEBUFFER, draw_fbo);
	glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, dst, 0);
	glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GL_COLOR_BUFFER_BIT, GL_NEAREST);
	glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
	glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
}

static void state(void) {
	glDisable(GL_SCISSOR_TEST);
	glDisable(GL_BLEND);
	glDisable(GL_DEPTH_TEST);
	glDisable(GL_STENCIL_TEST);
	glBindVertexArray(vao);
}

bool hybrid_capture(int slot, uint32_t* frame) {
	unsigned out;
	int w, h;
	bool smooth;
	gba_frame_texture(&out, &w, &h, &smooth);
	if (!ready || w != GBA_W * cur_scale) return false;
	blit(out, hi[slot], w, h);
	blit(mask_live, mk[slot], w, h);

	state();
	glBindFramebuffer(GL_FRAMEBUFFER, small_fbo);
	glViewport(0, 0, GBA_W, GBA_H);
	glUseProgram(prog_down);
	glActiveTexture(GL_TEXTURE0);
	glBindTexture(GL_TEXTURE_2D, out);
	glUniform1i(glGetUniformLocation(prog_down, "hi"), 0);
	glUniform1i(glGetUniformLocation(prog_down, "scale"), cur_scale);
	glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
	glPixelStorei(GL_PACK_ALIGNMENT, 4);
	glReadPixels(0, 0, GBA_W, GBA_H, GL_RGBA, GL_UNSIGNED_BYTE, frame);  // mismo orden RGBX que mGBA
	glBindFramebuffer(GL_FRAMEBUFFER, 0);
	glBindVertexArray(0);
	return true;
}

unsigned hybrid_mix(int slot, unsigned ai_tex) {
	if (!ready) return 0;
	state();
	glBindFramebuffer(GL_FRAMEBUFFER, comp_fbo);
	glViewport(0, 0, GBA_W * cur_scale, GBA_H * cur_scale);
	glUseProgram(prog_mix);
	glActiveTexture(GL_TEXTURE0);
	glBindTexture(GL_TEXTURE_2D, hi[slot]);
	glUniform1i(glGetUniformLocation(prog_mix, "hi"), 0);
	glActiveTexture(GL_TEXTURE1);
	glBindTexture(GL_TEXTURE_2D, mk[slot]);
	glUniform1i(glGetUniformLocation(prog_mix, "mk"), 1);
	glActiveTexture(GL_TEXTURE2);
	glBindTexture(GL_TEXTURE_2D, ai_tex);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
	glUniform1i(glGetUniformLocation(prog_mix, "ai"), 2);
	glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
	glActiveTexture(GL_TEXTURE0);
	glBindFramebuffer(GL_FRAMEBUFFER, 0);
	glBindVertexArray(0);
	return comp_tex;
}

int hybrid_scale(void) {
	return ready ? cur_scale : 0;
}
