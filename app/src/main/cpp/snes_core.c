// Frontend mínimo de libretro para bsnes-hd: opciones, video, audio, entrada, SRAM y estados.
#include "snes_core.h"

#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "bsnes-hd/bsnes/target-libretro/libretro.h"

#ifdef __ANDROID__
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "snes", __VA_ARGS__)
#else
#define LOGI(...) (fprintf(stderr, __VA_ARGS__), fputc('\n', stderr))
#endif

#define MAX_OPTS 96
static struct { char key[64]; char value[64]; char def[64]; } opts[MAX_OPTS];
static int n_opts;
static bool opts_dirty;

static char system_dir[512] = ".";
static char save_dir[512] = ".";
static char sram_file[512];

static bool loaded;
static uint16_t cur_keys;
static struct retro_system_av_info av;

static uint32_t* frame_buf;
static int frame_cap, frame_w, frame_h;

#define AUDIO_CAP (48000)
static int16_t audio_buf[AUDIO_CAP * 2];
static int audio_len;

static int find_opt(const char* key) {
	for (int i = 0; i < n_opts; ++i) {
		if (!strcmp(opts[i].key, key)) return i;
	}
	return -1;
}

void snes_set_option(const char* key, const char* value) {
	int i = find_opt(key);
	if (i < 0) {
		if (n_opts >= MAX_OPTS) return;
		i = n_opts++;
		snprintf(opts[i].key, sizeof(opts[i].key), "%s", key);
		opts[i].def[0] = 0;
	}
	snprintf(opts[i].value, sizeof(opts[i].value), "%s", value);
	opts_dirty = true;
}

const char* snes_get_option(const char* key) {
	int i = find_opt(key);
	if (i < 0) return NULL;
	return opts[i].value[0] ? opts[i].value : opts[i].def;
}

void snes_set_dirs(const char* sys, const char* save) {
	snprintf(system_dir, sizeof(system_dir), "%s", sys);
	snprintf(save_dir, sizeof(save_dir), "%s", save);
}

static void log_cb(enum retro_log_level level, const char* fmt, ...) {
	(void) level;
	char buf[512];
	va_list ap;
	va_start(ap, fmt);
	vsnprintf(buf, sizeof(buf), fmt, ap);
	va_end(ap);
	size_t n = strlen(buf);
	if (n && buf[n - 1] == '\n') buf[n - 1] = 0;
	LOGI("%s", buf);
}

static bool env_cb(unsigned cmd, void* data) {
	switch (cmd) {
	case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
		*(const char**) data = system_dir;
		return true;
	case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
		*(const char**) data = save_dir;
		return true;
	case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:
		return *(const enum retro_pixel_format*) data == RETRO_PIXEL_FORMAT_XRGB8888;
	case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
		((struct retro_log_callback*) data)->log = log_cb;
		return true;
	case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION:
		*(unsigned*) data = 0;  // opciones con SET_VARIABLES ("Desc; a|b|c", la primera es la predeterminada)
		return true;
	case RETRO_ENVIRONMENT_SET_VARIABLES: {
		const struct retro_variable* v = data;
		for (; v && v->key; ++v) {
			int i = find_opt(v->key);
			if (i < 0) {
				if (n_opts >= MAX_OPTS) break;
				i = n_opts++;
				snprintf(opts[i].key, sizeof(opts[i].key), "%s", v->key);
				opts[i].value[0] = 0;
			}
			const char* p = v->value ? strchr(v->value, ';') : NULL;
			if (p) {
				++p;
				while (*p == ' ') ++p;
				size_t n = strcspn(p, "|");
				if (n >= sizeof(opts[i].def)) n = sizeof(opts[i].def) - 1;
				memcpy(opts[i].def, p, n);
				opts[i].def[n] = 0;
			}
		}
		return true;
	}
	case RETRO_ENVIRONMENT_GET_VARIABLE: {
		struct retro_variable* v = data;
		v->value = snes_get_option(v->key);
		return v->value != NULL;
	}
	case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
		*(bool*) data = opts_dirty;
		opts_dirty = false;
		return true;
	case RETRO_ENVIRONMENT_GET_LANGUAGE:
		*(unsigned*) data = RETRO_LANGUAGE_SPANISH;
		return true;
	case RETRO_ENVIRONMENT_GET_FASTFORWARDING:
		*(bool*) data = false;
		return true;
	case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO:
		av = *(const struct retro_system_av_info*) data;
		return true;
	case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
	case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
	case RETRO_ENVIRONMENT_SET_SUBSYSTEM_INFO:
		return true;
	default:
		return false;  // incluye SET_CORE_OPTIONS(_INTL): así el núcleo usa SET_VARIABLES
	}
}

static void video_cb(const void* data, unsigned width, unsigned height, size_t pitch) {
	if (!data) return;  // cuadro repetido
	int need = (int) (width * height);
	if (need > frame_cap) {
		free(frame_buf);
		frame_buf = malloc((size_t) need * 4);
		frame_cap = frame_buf ? need : 0;
		if (!frame_buf) return;
	}
	for (unsigned y = 0; y < height; ++y) {
		const uint32_t* src = (const uint32_t*) ((const uint8_t*) data + y * pitch);
		uint32_t* dst = frame_buf + y * width;
		// XRGB8888 → RGBA en memoria (R en el byte bajo) para subirlo tal cual a GL_RGBA
		for (unsigned x = 0; x < width; ++x) {
			uint32_t c = src[x];
			dst[x] = 0xFF000000u | (c & 0x00FF00u) | ((c >> 16) & 0xFFu) | ((c & 0xFFu) << 16);
		}
	}
	frame_w = (int) width;
	frame_h = (int) height;
}

static size_t audio_batch_cb(const int16_t* data, size_t frames) {
	size_t room = AUDIO_CAP - (size_t) audio_len;
	size_t n = frames < room ? frames : room;
	memcpy(audio_buf + audio_len * 2, data, n * 4);
	audio_len += (int) n;
	return frames;
}

static void audio_cb(int16_t l, int16_t r) {
	int16_t s[2] = { l, r };
	audio_batch_cb(s, 1);
}

static void input_poll_cb(void) {}

static int16_t input_state_cb(unsigned port, unsigned device, unsigned index, unsigned id) {
	(void) index;
	if (port != 0 || (device != RETRO_DEVICE_JOYPAD)) return 0;
	if (id == RETRO_DEVICE_ID_JOYPAD_MASK) return (int16_t) cur_keys;
	return id < 12 ? (cur_keys >> id) & 1 : 0;
}

static bool load_file(const char* path, void* dst, size_t size) {
	FILE* f = fopen(path, "rb");
	if (!f) return false;
	size_t n = fread(dst, 1, size, f);
	fclose(f);
	return n == size;
}

bool snes_load(const char* rom_path, const char* sram_path) {
	if (loaded) snes_unload();
	retro_set_environment(env_cb);
	retro_set_video_refresh(video_cb);
	retro_set_audio_sample(audio_cb);
	retro_set_audio_sample_batch(audio_batch_cb);
	retro_set_input_poll(input_poll_cb);
	retro_set_input_state(input_state_cb);
	retro_init();
	struct retro_game_info info = { .path = rom_path };
	if (!retro_load_game(&info)) {
		retro_deinit();
		return false;
	}
	retro_set_controller_port_device(0, RETRO_DEVICE_JOYPAD);
	retro_get_system_av_info(&av);
	snprintf(sram_file, sizeof(sram_file), "%s", sram_path ? sram_path : "");
	size_t sz = retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
	void* ram = retro_get_memory_data(RETRO_MEMORY_SAVE_RAM);
	if (sram_file[0] && sz && ram) load_file(sram_file, ram, sz);
	audio_len = 0;
	loaded = true;
	LOGI("SNES cargado: %ux%u, %.3f fps, %.0f Hz, SRAM %zu", av.geometry.base_width, av.geometry.base_height,
	     av.timing.fps, av.timing.sample_rate, sz);
	return true;
}

bool snes_save_sram(void) {
	if (!loaded || !sram_file[0]) return false;
	size_t sz = retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
	void* ram = retro_get_memory_data(RETRO_MEMORY_SAVE_RAM);
	if (!sz || !ram) return false;
	FILE* f = fopen(sram_file, "wb");
	if (!f) return false;
	bool ok = fwrite(ram, 1, sz, f) == sz;
	fclose(f);
	return ok;
}

void snes_unload(void) {
	if (!loaded) return;
	snes_save_sram();
	retro_unload_game();
	retro_deinit();
	loaded = false;
}

bool snes_loaded(void) { return loaded; }

void snes_run_frame(uint16_t keys) {
	if (!loaded) return;
	cur_keys = keys;
	retro_run();
}

const uint32_t* snes_frame(int* w, int* h) {
	*w = frame_w;
	*h = frame_h;
	return frame_buf;
}

void snes_base_size(int* w, int* h) {
	*w = 256;
	*h = 224;
}

int snes_audio(int16_t* out, int max_frames) {
	int n = audio_len < max_frames ? audio_len : max_frames;
	memcpy(out, audio_buf, (size_t) n * 4);
	memmove(audio_buf, audio_buf + n * 2, (size_t) (audio_len - n) * 4);
	audio_len -= n;
	return n;
}

double snes_sample_rate(void) { return av.timing.sample_rate; }
double snes_fps(void) { return av.timing.fps; }

bool snes_save_state(const char* path) {
	if (!loaded) return false;
	size_t sz = retro_serialize_size();
	void* buf = malloc(sz);
	if (!buf) return false;
	bool ok = retro_serialize(buf, sz);
	if (ok) {
		FILE* f = fopen(path, "wb");
		ok = f && fwrite(buf, 1, sz, f) == sz;
		if (f) fclose(f);
	}
	free(buf);
	return ok;
}

bool snes_load_state(const char* path) {
	if (!loaded) return false;
	FILE* f = fopen(path, "rb");
	if (!f) return false;
	fseek(f, 0, SEEK_END);
	long sz = ftell(f);
	fseek(f, 0, SEEK_SET);
	void* buf = malloc((size_t) sz);
	bool ok = buf && fread(buf, 1, (size_t) sz, f) == (size_t) sz;
	fclose(f);
	if (ok) ok = retro_unserialize(buf, (size_t) sz);
	free(buf);
	return ok;
}
