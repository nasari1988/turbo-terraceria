#include <android/native_window_jni.h>
#include <jni.h>
#include <pthread.h>
#include <time.h>

#include "ai_gl.h"
#include "gba_core.h"
#include "video_gl.h"

#define FN(name) Java_com_nasari_gbaia_Native_##name

// La ventana llega desde el hilo de la interfaz; el hilo del emulador la aplica en sync().
static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t changed = PTHREAD_COND_INITIALIZER;
static ANativeWindow* pending = NULL;
static ANativeWindow* current = NULL;
static bool has_pending = false;
static bool emu_alive = false;

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
		// Esperar a que el emulador suelte la ventana anterior (máx. 2 s)
		struct timespec t;
		clock_gettime(CLOCK_REALTIME, &t);
		t.tv_sec += 2;
		while (has_pending && emu_alive) {
			if (pthread_cond_timedwait(&changed, &lock, &t) != 0) break;
		}
	}
	pthread_mutex_unlock(&lock);
}

// ---------- Todo lo siguiente corre en el hilo del emulador ----------

JNIEXPORT jboolean JNICALL FN(start)(JNIEnv* env, jclass cls) {
	(void) env;
	(void) cls;
	bool gl = video_init(NULL);
	gba_use_gl(gl);
	pthread_mutex_lock(&lock);
	emu_alive = true;
	pthread_mutex_unlock(&lock);
	sync_window();
	return gl;
}

JNIEXPORT void JNICALL FN(stop)(JNIEnv* env, jclass cls) {
	(void) env;
	(void) cls;
	gba_unload();
	pthread_mutex_lock(&lock);
	emu_alive = false;
	ai_deinit();
	video_set_window(NULL);
	if (current) ANativeWindow_release(current);
	current = NULL;
	pthread_cond_broadcast(&changed);
	pthread_mutex_unlock(&lock);
	video_deinit();
}

JNIEXPORT jboolean JNICALL FN(load)(JNIEnv* env, jclass cls, jbyteArray rom, jstring savePath, jint scale) {
	(void) cls;
	jsize size = (*env)->GetArrayLength(env, rom);
	jbyte* data = (*env)->GetByteArrayElements(env, rom, NULL);
	const char* path = savePath ? (*env)->GetStringUTFChars(env, savePath, NULL) : NULL;
	video_make_current();
	bool ok = gba_load(data, (size_t) size, path, scale);
	if (path) (*env)->ReleaseStringUTFChars(env, savePath, path);
	(*env)->ReleaseByteArrayElements(env, rom, data, JNI_ABORT);
	return ok;
}

JNIEXPORT void JNICALL FN(idle)(JNIEnv* env, jclass cls) {
	(void) env;
	(void) cls;
	sync_window();
}

JNIEXPORT jint JNICALL FN(runFrame)(JNIEnv* env, jclass cls, jint keys, jshortArray audio, jboolean present) {
	(void) cls;
	sync_window();
	video_make_current();
	jsize cap = (*env)->GetArrayLength(env, audio) / 2;
	jshort* au = (*env)->GetPrimitiveArrayCritical(env, audio, NULL);
	int n = gba_run_frame((uint32_t) keys, au, cap);
	(*env)->ReleasePrimitiveArrayCritical(env, audio, au, 0);
	if (present) {
		unsigned tex;
		int w, h;
		bool smooth;
		gba_frame_texture(&tex, &w, &h, &smooth);
		video_present(tex, w, h, smooth);
	}
	return n;
}

JNIEXPORT void JNICALL FN(setScale)(JNIEnv* env, jclass cls, jint scale) {
	(void) env;
	(void) cls;
	video_make_current();
	gba_set_scale(scale);
}

JNIEXPORT jint JNICALL FN(getScale)(JNIEnv* env, jclass cls) {
	(void) env;
	(void) cls;
	return gba_get_scale();
}

static jboolean with_path(JNIEnv* env, jstring jpath, bool (*fn)(const char*)) {
	const char* path = (*env)->GetStringUTFChars(env, jpath, NULL);
	video_make_current();
	bool ok = fn(path);
	(*env)->ReleaseStringUTFChars(env, jpath, path);
	return ok;
}

JNIEXPORT jboolean JNICALL FN(saveState)(JNIEnv* env, jclass cls, jstring path) {
	(void) cls;
	return with_path(env, path, gba_save_state);
}

JNIEXPORT jboolean JNICALL FN(loadState)(JNIEnv* env, jclass cls, jstring path) {
	(void) cls;
	return with_path(env, path, gba_load_state);
}

// Respaldo sin GPU: copia los píxeles para dibujarlos con Canvas.
JNIEXPORT void JNICALL FN(copyPixels)(JNIEnv* env, jclass cls, jintArray pixels) {
	(void) cls;
	jint* px = (*env)->GetPrimitiveArrayCritical(env, pixels, NULL);
	gba_copy_pixels((uint32_t*) px);
	(*env)->ReleasePrimitiveArrayCritical(env, pixels, px, 0);
}
