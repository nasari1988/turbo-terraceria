#include <jni.h>
#include <stdlib.h>

#include "gba_core.h"

#define FN(name) Java_com_nasari_gbaia_Native_##name

JNIEXPORT jboolean JNICALL FN(load)(JNIEnv* env, jclass cls, jbyteArray rom, jstring savePath) {
	(void) cls;
	jsize size = (*env)->GetArrayLength(env, rom);
	jbyte* data = (*env)->GetByteArrayElements(env, rom, NULL);
	const char* path = savePath ? (*env)->GetStringUTFChars(env, savePath, NULL) : NULL;
	bool ok = gba_load(data, (size_t) size, path);
	if (path) (*env)->ReleaseStringUTFChars(env, savePath, path);
	(*env)->ReleaseByteArrayElements(env, rom, data, JNI_ABORT);
	return ok;
}

JNIEXPORT void JNICALL FN(unload)(JNIEnv* env, jclass cls) {
	(void) env;
	(void) cls;
	gba_unload();
}

JNIEXPORT jint JNICALL FN(runFrame)(JNIEnv* env, jclass cls, jint keys, jintArray pixels, jshortArray audio) {
	(void) cls;
	jint* px = (*env)->GetPrimitiveArrayCritical(env, pixels, NULL);
	jsize cap = (*env)->GetArrayLength(env, audio) / 2;
	jshort* au = (*env)->GetPrimitiveArrayCritical(env, audio, NULL);
	int n = gba_run_frame((uint32_t) keys, (uint32_t*) px, au, cap);
	(*env)->ReleasePrimitiveArrayCritical(env, audio, au, 0);
	(*env)->ReleasePrimitiveArrayCritical(env, pixels, px, 0);
	return n;
}

static jboolean with_path(JNIEnv* env, jstring jpath, bool (*fn)(const char*)) {
	const char* path = (*env)->GetStringUTFChars(env, jpath, NULL);
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
