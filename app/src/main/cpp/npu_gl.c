// Parte de GPU del modo NPU: la NPU calcula los 48 canales (antes del pixel shuffle) y aquí
// se suben a una textura y se hace el pixel shuffle 4x + residuo, igual que en ai_gl.c.
#include "npu_gl.h"

#include <GLES3/gl3.h>
#include <stdio.h>

#define W 240
#define H 160
#define CH 48

static bool ready = false;
static GLuint prog, feat_tex, out_tex, fbo, vao;
static GLint loc_feat, loc_src;
// Ruta int8: la salida de la NPU se sube tal cual (1.8 MB) y se pasa a float en el shader
static GLuint prog_q, featq_tex, src_tex;
static GLint locq_feat, locq_src, locq_scale, locq_zp;

static const char* VS =
	"#version 300 es\n"
	"void main() {\n"
	"  vec2 p = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));\n"
	"  gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);\n"
	"}\n";

// feat: textura RGBA32F de (240*12) x 160; el vec4 k del píxel (x,y) está en (x*12+k, y),
// que es exactamente como viene la salida NHWC [160,240,48] de la NPU.
// Canal de pixel_shuffle para subpíxel (fila i, columna j) y color c: c*16 + i*4 + j.
static const char* FS =
	"#version 300 es\n"
	"precision highp float;\n"
	"precision highp int;\n"
	"uniform highp sampler2D feat;\n"
	"uniform highp sampler2D src;\n"
	"out vec4 color;\n"
	"float ch(ivec2 p, int c) {\n"
	"  vec4 v = texelFetch(feat, ivec2(p.x * 12 + c / 4, p.y), 0);\n"
	"  int k = c - (c / 4) * 4;\n"
	"  return k == 0 ? v.x : (k == 1 ? v.y : (k == 2 ? v.z : v.w));\n"
	"}\n"
	"void main() {\n"
	"  ivec2 q = ivec2(gl_FragCoord.xy);\n"
	"  ivec2 p = q / 4;\n"
	"  int s = (q.y - p.y * 4) * 4 + (q.x - p.x * 4);\n"
	"  vec3 base = texelFetch(src, p, 0).rgb;\n"
	"  vec3 d = vec3(ch(p, s), ch(p, 16 + s), ch(p, 32 + s));\n"
	"  color = vec4(clamp(base + d, 0.0, 1.0), 1.0);\n"
	"}\n";

static const char* FS_Q =
	"#version 300 es\n"
	"precision highp float;\n"
	"precision highp int;\n"
	"uniform highp isampler2D featq;\n"
	"uniform highp sampler2D src;\n"
	"uniform float scale;\n"
	"uniform float zp;\n"
	"out vec4 color;\n"
	"float ch(ivec2 p, int c) {\n"
	"  ivec4 v = texelFetch(featq, ivec2(p.x * 12 + c / 4, p.y), 0);\n"
	"  int k = c - (c / 4) * 4;\n"
	"  int q = k == 0 ? v.x : (k == 1 ? v.y : (k == 2 ? v.z : v.w));\n"
	"  return (float(q) - zp) * scale;\n"
	"}\n"
	"void main() {\n"
	"  ivec2 q = ivec2(gl_FragCoord.xy);\n"
	"  ivec2 p = q / 4;\n"
	"  int s = (q.y - p.y * 4) * 4 + (q.x - p.x * 4);\n"
	"  vec3 base = texelFetch(src, p, 0).rgb;\n"
	"  vec3 d = vec3(ch(p, s), ch(p, 16 + s), ch(p, 32 + s));\n"
	"  color = vec4(clamp(base + d, 0.0, 1.0), 1.0);\n"
	"}\n";

static GLuint compile(GLenum type, const char* src) {
	GLuint s = glCreateShader(type);
	glShaderSource(s, 1, &src, NULL);
	glCompileShader(s);
	GLint ok = 0;
	glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
	if (!ok) {
		char log[512];
		glGetShaderInfoLog(s, sizeof(log), NULL, log);
		fprintf(stderr, "NPU shader: %s\n", log);
	}
	return s;
}

static GLuint tex(int w, int h, GLenum internal, GLenum format, GLenum type) {
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

static bool init(void) {
	if (ready) return true;
	prog = glCreateProgram();
	glAttachShader(prog, compile(GL_VERTEX_SHADER, VS));
	glAttachShader(prog, compile(GL_FRAGMENT_SHADER, FS));
	glLinkProgram(prog);
	GLint ok = 0;
	glGetProgramiv(prog, GL_LINK_STATUS, &ok);
	if (!ok) return false;
	loc_feat = glGetUniformLocation(prog, "feat");
	loc_src = glGetUniformLocation(prog, "src");
	feat_tex = tex(W * CH / 4, H, GL_RGBA32F, GL_RGBA, GL_FLOAT);
	out_tex = tex(W * 4, H * 4, GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE);
	glGenFramebuffers(1, &fbo);
	glBindFramebuffer(GL_FRAMEBUFFER, fbo);
	glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, out_tex, 0);
	ok = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
	glBindFramebuffer(GL_FRAMEBUFFER, 0);
	if (!ok) return false;
	glGenVertexArrays(1, &vao);

	prog_q = glCreateProgram();
	glAttachShader(prog_q, compile(GL_VERTEX_SHADER, VS));
	glAttachShader(prog_q, compile(GL_FRAGMENT_SHADER, FS_Q));
	glLinkProgram(prog_q);
	glGetProgramiv(prog_q, GL_LINK_STATUS, &ok);
	if (!ok) return false;
	locq_feat = glGetUniformLocation(prog_q, "featq");
	locq_src = glGetUniformLocation(prog_q, "src");
	locq_scale = glGetUniformLocation(prog_q, "scale");
	locq_zp = glGetUniformLocation(prog_q, "zp");
	featq_tex = tex(W * CH / 4, H, GL_RGBA8I, GL_RGBA_INTEGER, GL_BYTE);
	src_tex = tex(W, H, GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE);
	ready = true;
	return true;
}

void npu_fill_input(const uint32_t* video, float* in) {
	// color_t de mGBA: bytes R,G,B,X en memoria
	for (int i = 0; i < W * H; ++i) {
		uint32_t c = video[i];
		in[i * 3 + 0] = (c & 0xFF) / 255.0f;
		in[i * 3 + 1] = ((c >> 8) & 0xFF) / 255.0f;
		in[i * 3 + 2] = ((c >> 16) & 0xFF) / 255.0f;
	}
}

void npu_fill_input_q(const uint32_t* video, void* in, int type, float scale, int zp) {
	if (type == NPU_F32) {
		npu_fill_input(video, in);
		return;
	}
	// Tabla: valor cuantizado para cada nivel de 0..255
	int lut[256];
	int lo = type == NPU_I8 ? -128 : -32768, hi = type == NPU_I8 ? 127 : 32767;
	for (int v = 0; v < 256; ++v) {
		float q = (v / 255.0f) / scale + zp;
		int qi = (int) (q < 0 ? q - 0.5f : q + 0.5f);
		lut[v] = qi < lo ? lo : (qi > hi ? hi : qi);
	}
	for (int i = 0; i < W * H; ++i) {
		uint32_t c = video[i];
		int r = lut[c & 0xFF], g = lut[(c >> 8) & 0xFF], b = lut[(c >> 16) & 0xFF];
		if (type == NPU_I8) {
			int8_t* o = (int8_t*) in + i * 3;
			o[0] = (int8_t) r, o[1] = (int8_t) g, o[2] = (int8_t) b;
		} else {
			int16_t* o = (int16_t*) in + i * 3;
			o[0] = (int16_t) r, o[1] = (int16_t) g, o[2] = (int16_t) b;
		}
	}
}

const float* npu_dequant(const void* out, int type, float scale, int zp) {
	static float buf[W * H * CH];
	if (type == NPU_F32) return out;
	int n = W * H * CH;
	if (type == NPU_I8) {
		const int8_t* q = out;
		for (int i = 0; i < n; ++i) buf[i] = (q[i] - zp) * scale;
	} else {
		const int16_t* q = out;
		for (int i = 0; i < n; ++i) buf[i] = (q[i] - zp) * scale;
	}
	return buf;
}

unsigned npu_compose(const float* features, unsigned src_tex) {
	if (!init()) return 0;
	glBindTexture(GL_TEXTURE_2D, feat_tex);
	glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
	glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, W * CH / 4, H, GL_RGBA, GL_FLOAT, features);

	glDisable(GL_SCISSOR_TEST);
	glDisable(GL_BLEND);
	glDisable(GL_DEPTH_TEST);
	glDisable(GL_STENCIL_TEST);
	glBindFramebuffer(GL_FRAMEBUFFER, fbo);
	glViewport(0, 0, W * 4, H * 4);
	glUseProgram(prog);
	glBindVertexArray(vao);
	glActiveTexture(GL_TEXTURE0);
	glBindTexture(GL_TEXTURE_2D, feat_tex);
	glUniform1i(loc_feat, 0);
	glActiveTexture(GL_TEXTURE1);
	glBindTexture(GL_TEXTURE_2D, src_tex);
	glUniform1i(loc_src, 1);
	glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
	glBindVertexArray(0);
	glActiveTexture(GL_TEXTURE0);
	glBindFramebuffer(GL_FRAMEBUFFER, 0);
	return out_tex;
}

unsigned npu_compose_frame(const void* raw, int type, float scale, int zp, const uint32_t* frame) {
	if (!init()) return 0;
	// El cuadro original que corresponde a esta salida (con la NPU en paralelo va un cuadro atrás)
	glBindTexture(GL_TEXTURE_2D, src_tex);
	glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
	glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, W, H, GL_RGBA, GL_UNSIGNED_BYTE, frame);
	if (type != NPU_I8) return npu_compose(npu_dequant(raw, type, scale, zp), src_tex);

	glBindTexture(GL_TEXTURE_2D, featq_tex);
	glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
	glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, W * CH / 4, H, GL_RGBA_INTEGER, GL_BYTE, raw);
	glPixelStorei(GL_UNPACK_ALIGNMENT, 4);

	glDisable(GL_SCISSOR_TEST);
	glDisable(GL_BLEND);
	glDisable(GL_DEPTH_TEST);
	glDisable(GL_STENCIL_TEST);
	glBindFramebuffer(GL_FRAMEBUFFER, fbo);
	glViewport(0, 0, W * 4, H * 4);
	glUseProgram(prog_q);
	glBindVertexArray(vao);
	glActiveTexture(GL_TEXTURE0);
	glBindTexture(GL_TEXTURE_2D, featq_tex);
	glUniform1i(locq_feat, 0);
	glActiveTexture(GL_TEXTURE1);
	glBindTexture(GL_TEXTURE_2D, src_tex);
	glUniform1i(locq_src, 1);
	glUniform1f(locq_scale, scale);
	glUniform1f(locq_zp, (float) zp);
	glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
	glBindVertexArray(0);
	glActiveTexture(GL_TEXTURE0);
	glBindFramebuffer(GL_FRAMEBUFFER, 0);
	return out_tex;
}

void npu_gl_deinit(void) {
	if (!ready) return;
	glDeleteProgram(prog_q);
	glDeleteTextures(1, &featq_tex);
	glDeleteTextures(1, &src_tex);
	glDeleteProgram(prog);
	glDeleteTextures(1, &feat_tex);
	glDeleteTextures(1, &out_tex);
	glDeleteFramebuffers(1, &fbo);
	glDeleteVertexArrays(1, &vao);
	ready = false;
}
