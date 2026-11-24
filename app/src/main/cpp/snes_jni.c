// JNI del emulador de SNES (clase com.nasari.gbaia.SnesNative). Mismo esquema que el de GBA:
// la ventana la entrega el hilo de la interfaz y la usa solo el hilo del emulador.
#include <GLES3/gl3.h>
#include <android/native_window_jni.h>
#include <jni.h>
#include <pthread.h>
#include <string.h>
#include <time.h>

#include "gpu_timer.h"
#include "snes_core.h"
#include "video_gl.h"

#define FN(name) Java_com_nasari_gbaia_SnesNative_##name

static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t changed = PTHREAD_COND_INITIALIZER;
static ANativeWindow* pending;
static ANativeWindow* current;
static bool has_pending;
static bool emu_alive;
static bool gl_ok;

static GLuint tex;
static int tex_w, tex_h;
static float emu_ms;

static void sync_window(void) {
	pthread_mutex_lock(&lock);
	if (has_pending) {
		video_set_window(pending);
		if (current) ANativeWindow_release(current);
		current = pending;
		pending = NULL;
		has_pending = false;
		pthread_cond_broadcast(&changed);
	}
	pthread_mutex_unlock(&lock);
}

JNIEXPORT void JNICALL FN(setSurface)(JNIEnv* env, jclass cls, jobject surface) {
	(void) cls;
	ANativeWindow* win = surface ? ANativeWindow_fromSurface(env, surface) : NULL;
	pthread_mutex_lock(&lock);
	if (has_pending && pending) ANativeWindow_release(pending);
	pending = win;
	has_pending = true;
	if (emu_alive) {
		struct timespec t;
		clock_gettime(CLOCK_REALTIME, &t);
		t.tv_sec += 2;
		while (has_pending && emu_alive) {
			if (pthread_cond_timedwait(&changed, &lock, &t) != 0) break;
		}
	}
	pthread_mutex_unlock(&lock);
}

JNIEXPORT jboolean JNICALL FN(start)(JNIEnv* env, jclass cls, jstring sysDir, jstring saveDir) {
	(void) cls;
	const char* a = (*env)->GetStringUTFChars(env, sysDir, NULL);
	const char* b = (*env)->GetStringUTFChars(env, saveDir, NULL);
	snes_set_dirs(a, b);
	(*env)->ReleaseStringUTFChars(env, sysDir, a);
	(*env)->ReleaseStringUTFChars(env, saveDir, b);
	gl_ok = video_init(NULL);
	pthread_mutex_lock(&lock);
	emu_alive = true;
	pthread_mutex_unlock(&lock);
	sync_window();
	return gl_ok;
}

JNIEXPORT void JNICALL FN(stop)(JNIEnv* env, jclass cls) {
	(void) env;
	(void) cls;
	snes_unload();
	pthread_mutex_lock(&lock);
	emu_alive = false;
	if (tex) {
		video_make_current();
		glDeleteTextures(1, &tex);
		tex = 0;
		tex_w = tex_h = 0;
	}
	gpu_timer_reset();
	video_set_window(NULL);
	if (current) ANativeWindow_release(current);
	current = NULL;
	pthread_cond_broadcast(&changed);
	pthread_mutex_unlock(&lock);
	video_deinit();
}

JNIEXPORT void JNICALL FN(setOption)(JNIEnv* env, jclass cls, jstring key, jstring value) {
	(void) cls;
	const char* k = (*env)->GetStringUTFChars(env, key, NULL);
	const char* v = (*env)->GetStringUTFChars(env, value, NULL);
	snes_set_option(k, v);
	(*env)->ReleaseStringUTFChars(env, key, k);
	(*env)->ReleaseStringUTFChars(env, value, v);
}

JNIEXPORT jboolean JNICALL FN(load)(JNIEnv* env, jclass cls, jstring romPath, jstring sramPath) {
	(void) cls;
	const char* r = (*env)->GetStringUTFChars(env, romPath, NULL);
	const char* s = (*env)->GetStringUTFChars(env, sramPath, NULL);
	bool ok = snes_load(r, s);
	(*env)->ReleaseStringUTFChars(env, romPath, r);
	(*env)->ReleaseStringUTFChars(env, sramPath, s);
	return ok;
}

JNIEXPORT void JNICALL FN(idle)(JNIEnv* env, jclass cls) {
	(void) env;
	(void) cls;
	sync_window();
}

static void present(void) {
	int w, h;
	const uint32_t* px = snes_frame(&w, &h);
	if (!px || w <= 0 || h <= 0 || !gl_ok) return;
	video_make_current();
	if (!tex) glGenTextures(1, &tex);
	glBindTexture(GL_TEXTURE_2D, tex);
	glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
	if (w != tex_w || h != tex_h) {
		glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, px);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		tex_w = w;
		tex_h = h;
	} else {
		glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, px);
	}
	// Tamaño lógico: con Mode 7 HD el cuadro viene multiplicado (alto base 216 o 224 líneas)
	int hd = h % 224 == 0 ? h / 224 : (h % 216 == 0 ? h / 216 : 1);
	if (hd < 1) hd = 1;
	int bw = w / hd, bh = h / hd;
	if (bw >= 512) bw /= 2;  // alta resolución horizontal (pseudo hi-res): mismo ancho en pantalla
	if (bh >= 400) bh /= 2;  // entrelazado
	video_set_base(bw, bh);
	video_present(tex, w, h, false);
}

// Corre un cuadro; devuelve cuántos cuadros de audio (estéreo) quedaron en audio.
JNIEXPORT jint JNICALL FN(runFrame)(JNIEnv* env, jclass cls, jint keys, jshortArray audio, jboolean draw) {
	(void) cls;
	sync_window();
	struct timespec t0, t1;
	clock_gettime(CLOCK_MONOTONIC, &t0);
	snes_run_frame((uint16_t) keys);
	clock_gettime(CLOCK_MONOTONIC, &t1);
	float ms = (t1.tv_sec - t0.tv_sec) * 1e3f + (t1.tv_nsec - t0.tv_nsec) / 1e6f;
	emu_ms = emu_ms * 0.9f + ms * 0.1f;
	if (draw) present();
	jsize cap = (*env)->GetArrayLength(env, audio) / 2;
	jshort* buf = (*env)->GetShortArrayElements(env, audio, NULL);
	int n = snes_audio(buf, cap);
	(*env)->ReleaseShortArrayElements(env, audio, buf, 0);
	return n;
}

JNIEXPORT jintArray JNICALL FN(frameSize)(JNIEnv* env, jclass cls) {
	(void) cls;
	int w, h;
	snes_frame(&w, &h);
	jint v[2] = { w, h };
	jintArray r = (*env)->NewIntArray(env, 2);
	(*env)->SetIntArrayRegion(env, r, 0, 2, v);
	return r;
}

// RGBA del último cuadro reducido al tamaño base (para usar como fondo en el launcher)
JNIEXPORT jintArray JNICALL FN(copyFrame)(JNIEnv* env, jclass cls) {
	(void) cls;
	int w, h;
	const uint32_t* px = snes_frame(&w, &h);
	if (!px || w <= 0) return NULL;
	jintArray r = (*env)->NewIntArray(env, w * h + 2);
	jint wh[2] = { w, h };
	(*env)->SetIntArrayRegion(env, r, 0, 2, wh);
	// RGBA (R en el byte bajo) → ARGB de Android
	jint* out = (*env)->GetIntArrayElements(env, r, NULL);
	for (int i = 0; i < w * h; ++i) {
		uint32_t c = px[i];
		out[i + 2] = (jint) (0xFF000000u | ((c & 0xFFu) << 16) | (c & 0xFF00u) | ((c >> 16) & 0xFFu));
	}
	(*env)->ReleaseIntArrayElements(env, r, out, 0);
	return r;
}

JNIEXPORT jfloat JNICALL FN(emuMs)(JNIEnv* env, jclass cls) {
	(void) env;
	(void) cls;
	return emu_ms;
}

JNIEXPORT jdouble JNICALL FN(sampleRate)(JNIEnv* env, jclass cls) {
	(void) env;
	(void) cls;
	return snes_sample_rate();
}

JNIEXPORT jboolean JNICALL FN(saveState)(JNIEnv* env, jclass cls, jstring path) {
	(void) cls;
	const char* p = (*env)->GetStringUTFChars(env, path, NULL);
	bool ok = snes_save_state(p);
	(*env)->ReleaseStringUTFChars(env, path, p);
	snes_save_sram();
	return ok;
}

JNIEXPORT jboolean JNICALL FN(loadState)(JNIEnv* env, jclass cls, jstring path) {
	(void) cls;
	const char* p = (*env)->GetStringUTFChars(env, path, NULL);
	bool ok = snes_load_state(p);
	(*env)->ReleaseStringUTFChars(env, path, p);
	return ok;
}

JNIEXPORT jboolean JNICALL FN(saveSram)(JNIEnv* env, jclass cls) {
	(void) env;
	(void) cls;
	return snes_save_sram();
}

JNIEXPORT jstring JNICALL FN(glInfo)(JNIEnv* env, jclass cls) {
	(void) cls;
	return (*env)->NewStringUTF(env, video_info());
}
