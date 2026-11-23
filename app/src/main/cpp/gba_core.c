// Envoltura sobre el núcleo de mGBA.
// Fase 1: emulación, audio, guardado y estados.
// Fase 2: renderizador OpenGL de mGBA a alta resolución (1x-8x) en la GPU.
#include "gba_core.h"
#include "ai_gl.h"

#include <fcntl.h>
#include <stdarg.h>
#include <string.h>

#include <GLES3/gl3.h>
#include <mgba/core/blip_buf.h>
#include <mgba/core/core.h>
#include <mgba/core/log.h>
#include <mgba/core/serialize.h>
#include <mgba/gba/core.h>
#include <mgba-util/vfs.h>

static struct mCore* core = NULL;
static color_t video[GBA_W * GBA_H];
static bool have_gl = false;
static GLuint out_tex = 0;  // donde dibuja el renderizador OpenGL de mGBA
static GLuint sw_tex = 0;   // copia del renderizador por software
static int scale = 0;       // 0 = software (exacto), 1-8 = GPU
static int ai_on = 0;  // 0 = sin IA; GBA_SCALE_AI o GBA_SCALE_AI_LITE

// Sin registro: mGBA escribe mucho texto por cuadro y gasta CPU.
static void quiet_log(struct mLogger* l, int category, enum mLogLevel level, const char* format, va_list args) {
	(void) l;
	(void) category;
	(void) level;
	(void) format;
	(void) args;
}
static struct mLogger quiet = {.log = quiet_log, .filter = NULL};

void gba_use_gl(bool on) {
	have_gl = on;
}

bool gba_load(const void* rom, size_t size, const char* save_path, int video_scale) {
	gba_unload();
	mLogSetDefaultLogger(&quiet);
	core = GBACoreCreate();
	if (!core) return false;
	if (!core->init(core)) {
		core = NULL;
		return false;
	}
	mCoreInitConfig(core, NULL);
	core->setVideoBuffer(core, video, GBA_W);
	core->setAudioBufferSize(core, 4096);
	blip_set_rates(core->getAudioChannel(core, 0), core->frequency(core), GBA_AUDIO_RATE);
	blip_set_rates(core->getAudioChannel(core, 1), core->frequency(core), GBA_AUDIO_RATE);

	bool want_ai = video_scale == GBA_SCALE_AI || video_scale == GBA_SCALE_AI_LITE;
	ai_on = 0;
	scale = have_gl && !want_ai ? video_scale : 0;
	if (scale < 0) scale = 0;
	if (scale > 8) scale = 8;
	if (have_gl) {
		glGenTextures(1, &out_tex);
		core->setVideoGLTex(core, out_tex);
		mCoreConfigSetIntValue(&core->config, "hwaccelVideo", scale > 0);
		mCoreConfigSetIntValue(&core->config, "videoScale", scale > 0 ? scale : 1);
	}

	struct VFile* vf = VFileMemChunk(rom, size);  // copia la ROM
	if (!vf || !core->loadROM(core, vf)) {
		gba_unload();
		return false;
	}
	if (save_path && save_path[0]) {
		struct VFile* sav = VFileOpen(save_path, O_CREAT | O_RDWR);
		if (sav) core->loadSave(core, sav);
	}
	core->reset(core);
	if (want_ai) gba_set_scale(video_scale);
	return true;
}

void gba_unload(void) {
	if (!core) return;
	core->unloadROM(core);  // escribe la partida guardada
	mCoreConfigDeinit(&core->config);
	core->deinit(core);
	core = NULL;
	if (out_tex) glDeleteTextures(1, &out_tex);
	if (sw_tex) glDeleteTextures(1, &sw_tex);
	out_tex = sw_tex = 0;
}

void gba_set_scale(int s) {
	if (!core || !have_gl) return;
	if (s == GBA_SCALE_AI || s == GBA_SCALE_AI_LITE) {
		bool ok = s == GBA_SCALE_AI ? ai_init() : ai1_init();
		if (!ok) return;   // sin soporte: se queda como está
		ai_on = 0;
		gba_set_scale(0);  // la IA trabaja sobre el renderizador exacto
		ai_on = s;
		return;
	}
	ai_on = 0;
	if (s < 0) s = 0;
	if (s > 8) s = 8;
	if (s == scale) return;
	if (s == 0) {
		mCoreConfigSetIntValue(&core->config, "hwaccelVideo", 0);
		core->reloadConfigOption(core, "hwaccelVideo", &core->config);
	} else {
		mCoreConfigSetIntValue(&core->config, "videoScale", s);
		if (scale == 0) {
			mCoreConfigSetIntValue(&core->config, "hwaccelVideo", 1);
			core->reloadConfigOption(core, "hwaccelVideo", &core->config);
		} else {
			core->reloadConfigOption(core, "videoScale", &core->config);
		}
	}
	scale = s;
}

int gba_get_scale(void) {
	return ai_on ? ai_on : scale;
}

int gba_run_frame(uint32_t keys, int16_t* audio, int audio_cap) {
	if (!core) return 0;
	core->setKeys(core, keys);
	core->runFrame(core);

	blip_t* left = core->getAudioChannel(core, 0);
	blip_t* right = core->getAudioChannel(core, 1);
	int n = blip_samples_avail(left);
	if (n > audio_cap) n = audio_cap;
	blip_read_samples(left, audio, n, 1);
	blip_read_samples(right, audio + 1, n, 1);
	return n;  // cuadros estéreo
}

void gba_copy_pixels(uint32_t* pixels) {
	// color_t de mGBA ya es RGBX en memoria; solo forzamos alfa opaco.
	for (int i = 0; i < GBA_W * GBA_H; ++i) pixels[i] = video[i] | 0xFF000000u;
}

void gba_frame_texture(unsigned* tex, int* w, int* h, bool* smooth) {
	*smooth = false;
	if (scale > 0) {
		*tex = out_tex;
		*w = GBA_W * scale;
		*h = GBA_H * scale;
		return;
	}
	if (!sw_tex) {
		glGenTextures(1, &sw_tex);
		glBindTexture(GL_TEXTURE_2D, sw_tex);
		glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, GBA_W, GBA_H, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		// sin mipmaps: si no se fija el filtro, GL considera la textura incompleta
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
	}
	glBindTexture(GL_TEXTURE_2D, sw_tex);
	glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
	glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, GBA_W, GBA_H, GL_RGBA, GL_UNSIGNED_BYTE, video);
	*tex = sw_tex;
	*w = GBA_W;
	*h = GBA_H;
	if (ai_on) {
		unsigned out = ai_on == GBA_SCALE_AI ? ai_run(sw_tex) : ai1_run(sw_tex);
		if (out) {
			*tex = out;
			*w = GBA_W * 4;
			*h = GBA_H * 4;
			*smooth = true;
		}
	}
}

bool gba_save_state(const char* path) {
	if (!core) return false;
	struct VFile* vf = VFileOpen(path, O_CREAT | O_TRUNC | O_RDWR);
	if (!vf) return false;
	bool ok = mCoreSaveStateNamed(core, vf, SAVESTATE_SAVEDATA | SAVESTATE_RTC);
	vf->close(vf);
	return ok;
}

bool gba_load_state(const char* path) {
	if (!core) return false;
	struct VFile* vf = VFileOpen(path, O_RDONLY);
	if (!vf) return false;
	bool ok = mCoreLoadStateNamed(core, vf, SAVESTATE_RTC);
	vf->close(vf);
	return ok;
}
