// IA de escalado 4x en la GPU: la red ligera entrenada (tools/ai) convertida a shaders.
// Entrada: textura RGBA8 de 240x160. Salida: textura RGBA8 de 960x640.
#include "ai_gl.h"

#include <GLES3/gl3.h>
#include <stdio.h>

#include "ai_shaders.h"

#define W 240
#define H 160
#define FT (AI_CH / 4)  // texturas RGBA por capa intermedia
#define NPASS (AI_LAYERS * AI_GROUPS + AI_LAST_PASSES)

static bool ready = false;
static GLuint progs[NPASS];
static GLuint shuffle_prog;
static GLuint feat[2][FT];  // capas intermedias (ida y vuelta)
static GLuint last[12];     // 48 canales de la última convolución
static GLuint fbo_feat[2][AI_GROUPS], fbo_last[3], fbo_out;
static GLuint out_tex;
static GLuint vao;

static const char* VS =
	"#version 300 es\n"
	"void main() {\n"
	"  vec2 p = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));\n"
	"  gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);\n"
	"}\n";

static GLuint build(const char* fs_src) {
	GLuint vs = glCreateShader(GL_VERTEX_SHADER);
	glShaderSource(vs, 1, &VS, NULL);
	glCompileShader(vs);
	GLuint fs = glCreateShader(GL_FRAGMENT_SHADER);
	glShaderSource(fs, 1, &fs_src, NULL);
	glCompileShader(fs);
	GLint ok = 0;
	glGetShaderiv(fs, GL_COMPILE_STATUS, &ok);
	if (!ok) {
		char log[1024];
		glGetShaderInfoLog(fs, sizeof(log), NULL, log);
		fprintf(stderr, "IA shader: %s\n", log);
		return 0;
	}
	GLuint p = glCreateProgram();
	glAttachShader(p, vs);
	glAttachShader(p, fs);
	glLinkProgram(p);
	glDeleteShader(vs);
	glDeleteShader(fs);
	glGetProgramiv(p, GL_LINK_STATUS, &ok);
	return ok ? p : 0;
}

static GLuint make_tex(int w, int h, GLenum internal, GLenum format, GLenum type) {
	GLuint t;
	glGenTextures(1, &t);
	glBindTexture(GL_TEXTURE_2D, t);
	glTexImage2D(GL_TEXTURE_2D, 0, internal, w, h, 0, format, type, NULL);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
	return t;
}

static GLuint make_fbo(const GLuint* texs, int n) {
	static const GLenum bufs[4] = {GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1, GL_COLOR_ATTACHMENT2, GL_COLOR_ATTACHMENT3};
	GLuint f;
	glGenFramebuffers(1, &f);
	glBindFramebuffer(GL_FRAMEBUFFER, f);
	for (int i = 0; i < n; ++i) glFramebufferTexture2D(GL_FRAMEBUFFER, bufs[i], GL_TEXTURE_2D, texs[i], 0);
	glDrawBuffers(n, bufs);
	if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
		fprintf(stderr, "IA: framebuffer incompleto\n");
		f = 0;
	}
	glBindFramebuffer(GL_FRAMEBUFFER, 0);
	return f;
}

bool ai_init(void) {
	if (ready) return true;
	GLint max_bufs = 0;
	glGetIntegerv(GL_MAX_DRAW_BUFFERS, &max_bufs);
	if (max_bufs < 4) return false;

	for (int i = 0; i < NPASS; ++i) {
		progs[i] = build(AI_CONV_SHADERS[i]);
		if (!progs[i]) return false;
	}
	shuffle_prog = build(AI_SHUFFLE_SHADER);
	if (!shuffle_prog) return false;

	// RGBA16F para las capas intermedias (Adreno lo soporta como destino de dibujo)
	for (int s = 0; s < 2; ++s) {
		for (int i = 0; i < FT; ++i) feat[s][i] = make_tex(W, H, GL_RGBA16F, GL_RGBA, GL_HALF_FLOAT);
		for (int g = 0; g < AI_GROUPS; ++g) {
			fbo_feat[s][g] = make_fbo(&feat[s][g * 4], 4);
			if (!fbo_feat[s][g]) return false;
		}
	}
	for (int i = 0; i < 12; ++i) last[i] = make_tex(W, H, GL_RGBA16F, GL_RGBA, GL_HALF_FLOAT);
	for (int g = 0; g < 3; ++g) {
		fbo_last[g] = make_fbo(&last[g * 4], 4);
		if (!fbo_last[g]) return false;
	}
	out_tex = make_tex(W * AI_SCALE, H * AI_SCALE, GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE);
	fbo_out = make_fbo(&out_tex, 1);
	if (!fbo_out) return false;
	glGenVertexArrays(1, &vao);
	ready = true;
	return true;
}

static void bind_inputs(GLuint prog, const GLuint* texs, int n) {
	char name[8];
	for (int i = 0; i < n; ++i) {
		glActiveTexture(GL_TEXTURE0 + i);
		glBindTexture(GL_TEXTURE_2D, texs[i]);
		snprintf(name, sizeof(name), "t%d", i);
		glUniform1i(glGetUniformLocation(prog, name), i);
	}
}

unsigned ai_run(unsigned in_tex) {
	if (!ready) return 0;
	glDisable(GL_SCISSOR_TEST);
	glDisable(GL_BLEND);
	glDisable(GL_DEPTH_TEST);
	glDisable(GL_STENCIL_TEST);
	glBindVertexArray(vao);
	glViewport(0, 0, W, H);

	GLuint input = in_tex;
	int cur = 0;
	for (int i = 0; i < AI_LAYERS; ++i) {
		for (int g = 0; g < AI_GROUPS; ++g) {
			GLuint p = progs[i * AI_GROUPS + g];
			glBindFramebuffer(GL_FRAMEBUFFER, fbo_feat[cur][g]);
			glUseProgram(p);
			if (i == 0) bind_inputs(p, &input, 1);
			else bind_inputs(p, feat[cur ^ 1], FT);
			glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
		}
		cur ^= 1;
	}
	// Última convolución: 48 canales en 3 pases
	for (int g = 0; g < AI_LAST_PASSES; ++g) {
		GLuint p = progs[AI_LAYERS * AI_GROUPS + g];
		glBindFramebuffer(GL_FRAMEBUFFER, fbo_last[g]);
		glUseProgram(p);
		bind_inputs(p, feat[cur ^ 1], FT);
		glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
	}
	// Pixel shuffle 4x + entrada agrandada
	glBindFramebuffer(GL_FRAMEBUFFER, fbo_out);
	glViewport(0, 0, W * AI_SCALE, H * AI_SCALE);
	glUseProgram(shuffle_prog);
	char name[8];
	for (int i = 0; i < 12; ++i) {
		glActiveTexture(GL_TEXTURE0 + i);
		glBindTexture(GL_TEXTURE_2D, last[i]);
		snprintf(name, sizeof(name), "c%d", i);
		glUniform1i(glGetUniformLocation(shuffle_prog, name), i);
	}
	glActiveTexture(GL_TEXTURE12);
	glBindTexture(GL_TEXTURE_2D, input);
	glUniform1i(glGetUniformLocation(shuffle_prog, "src"), 12);
	glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

	glBindFramebuffer(GL_FRAMEBUFFER, 0);
	glBindVertexArray(0);
	glActiveTexture(GL_TEXTURE0);
	return out_tex;
}

void ai_deinit(void) {
	if (!ready) return;
	for (int i = 0; i < NPASS; ++i) glDeleteProgram(progs[i]);
	glDeleteProgram(shuffle_prog);
	glDeleteTextures(2 * FT, &feat[0][0]);
	glDeleteTextures(12, last);
	glDeleteTextures(1, &out_tex);
	glDeleteFramebuffers(2 * AI_GROUPS, &fbo_feat[0][0]);
	glDeleteFramebuffers(3, fbo_last);
	glDeleteFramebuffers(1, &fbo_out);
	glDeleteVertexArrays(1, &vao);
	ready = false;
}
