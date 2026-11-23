// Mide cuánto tarda la GPU en un tramo de trabajo (p. ej. la IA) con GL_EXT_disjoint_timer_query.
// Usa varias consultas en rueda para no frenar el dibujo esperando resultados.
#include "gpu_timer.h"

#include <GLES3/gl3.h>
#include <string.h>

#define GL_TIME_ELAPSED_EXT 0x88BF
#define GL_GPU_DISJOINT_EXT 0x8FBB
#define N 4

static bool supported = false, checked = false;
static GLuint queries[N];
static bool pending[N];
static int head = 0;
static bool active = false;
static float avg_ms = -1.0f;

static void check(void) {
	if (checked) return;
	checked = true;
	GLint n = 0;
	glGetIntegerv(GL_NUM_EXTENSIONS, &n);
	for (GLint i = 0; i < n; ++i) {
		const char* e = (const char*) glGetStringi(GL_EXTENSIONS, i);
		if (e && strcmp(e, "GL_EXT_disjoint_timer_query") == 0) supported = true;
	}
	if (supported) glGenQueries(N, queries);
}

static void collect(void) {
	for (int i = 0; i < N; ++i) {
		if (!pending[i]) continue;
		GLuint ready = 0;
		glGetQueryObjectuiv(queries[i], GL_QUERY_RESULT_AVAILABLE, &ready);
		if (!ready) continue;
		GLuint ns = 0;
		glGetQueryObjectuiv(queries[i], GL_QUERY_RESULT, &ns);
		pending[i] = false;
		GLint disjoint = 0;
		glGetIntegerv(GL_GPU_DISJOINT_EXT, &disjoint);
		if (disjoint) continue;  // la medición no es confiable (cambio de frecuencia, etc.)
		float ms = ns / 1e6f;
		avg_ms = avg_ms < 0 ? ms : avg_ms * 0.9f + ms * 0.1f;
	}
}

void gpu_timer_begin(void) {
	check();
	if (!supported) return;
	collect();
	if (pending[head]) return;  // rueda llena: se salta esta medición
	glBeginQuery(GL_TIME_ELAPSED_EXT, queries[head]);
	active = true;
}

void gpu_timer_end(void) {
	if (!active) return;
	glEndQuery(GL_TIME_ELAPSED_EXT);
	pending[head] = true;
	head = (head + 1) % N;
	active = false;
}

float gpu_timer_ms(void) {
	return avg_ms;
}

void gpu_timer_reset(void) {
	if (supported && checked) glDeleteQueries(N, queries);
	checked = supported = active = false;
	memset(pending, 0, sizeof(pending));
	head = 0;
	avg_ms = -1.0f;
}
