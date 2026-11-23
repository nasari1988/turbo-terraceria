// Envoltura mínima sobre el núcleo de mGBA.
// Fase 1: emulación, video, audio, guardado y estados.
// Más adelante aquí entra el renderizado por capas para la IA.
#include "gba_core.h"

#include <fcntl.h>
#include <string.h>

#include <mgba/core/blip_buf.h>
#include <mgba/core/core.h>
#include <mgba/core/serialize.h>
#include <mgba/gba/core.h>
#include <mgba-util/vfs.h>

static struct mCore* core = NULL;
static color_t video[GBA_W * GBA_H];

bool gba_load(const void* rom, size_t size, const char* save_path) {
	gba_unload();
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
	return true;
}

void gba_unload(void) {
	if (!core) return;
	core->unloadROM(core);  // escribe la partida guardada
	mCoreConfigDeinit(&core->config);
	core->deinit(core);
	core = NULL;
}

int gba_run_frame(uint32_t keys, uint32_t* pixels, int16_t* audio, int audio_cap) {
	if (!core) return 0;
	core->setKeys(core, keys);
	core->runFrame(core);

	// color_t de mGBA ya es RGBX en memoria; solo forzamos alfa opaco.
	for (int i = 0; i < GBA_W * GBA_H; ++i) {
		pixels[i] = video[i] | 0xFF000000u;
	}

	blip_t* left = core->getAudioChannel(core, 0);
	blip_t* right = core->getAudioChannel(core, 1);
	int n = blip_samples_avail(left);
	if (n > audio_cap) n = audio_cap;
	blip_read_samples(left, audio, n, 1);
	blip_read_samples(right, audio + 1, n, 1);
	return n;  // cuadros estéreo
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
