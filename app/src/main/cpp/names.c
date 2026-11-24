// Arreglo de nombres de archivo dañados (ñ y acentos guardados en CP850 o Latin-1, no en UTF-8).
// Java/Kotlin no puede abrir esos archivos: al leer el nombre convierte el byte malo en "�" y la
// ruta ya no coincide. Aquí se trabaja con los bytes crudos del sistema de archivos.
#include "names.h"

#include <dirent.h>
#include <limits.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

// CP850 (Windows en español al crear ZIP): 0x80–0xA5 son letras acentuadas
static const unsigned short CP850[38] = {
	0xC7, 0xFC, 0xE9, 0xE2, 0xE4, 0xE0, 0xE5, 0xE7, 0xEA, 0xEB, 0xE8, 0xEF, 0xEE, 0xEC, 0xC4, 0xC5,
	0xC9, 0xE6, 0xC6, 0xF4, 0xF6, 0xF2, 0xFB, 0xF9, 0xFF, 0xD6, 0xDC, 0xF8, 0xA3, 0xD8, 0xD7, 0x192,
	0xE1, 0xED, 0xF3, 0xFA, 0xF1, 0xD1,
};

bool names_valid_utf8(const char* s) {
	const unsigned char* p = (const unsigned char*) s;
	while (*p) {
		int n;
		if (*p < 0x80) n = 0;
		else if ((*p & 0xE0) == 0xC0 && *p >= 0xC2) n = 1;
		else if ((*p & 0xF0) == 0xE0) n = 2;
		else if ((*p & 0xF8) == 0xF0 && *p <= 0xF4) n = 3;
		else return false;
		++p;
		for (int i = 0; i < n; ++i, ++p) {
			if ((*p & 0xC0) != 0x80) return false;
		}
	}
	return true;
}

static size_t put_utf8(char* out, unsigned cp) {
	if (cp < 0x80) { out[0] = (char) cp; return 1; }
	if (cp < 0x800) { out[0] = (char) (0xC0 | (cp >> 6)); out[1] = (char) (0x80 | (cp & 0x3F)); return 2; }
	out[0] = (char) (0xE0 | (cp >> 12)); out[1] = (char) (0x80 | ((cp >> 6) & 0x3F)); out[2] = (char) (0x80 | (cp & 0x3F));
	return 3;
}

size_t names_fix(const char* in, char* out, size_t outSize) {
	const unsigned char* p = (const unsigned char*) in;
	size_t o = 0;
	while (*p && o + 4 < outSize) {
		// Secuencias UTF-8 válidas se copian tal cual
		int n = 0;
		if (*p >= 0xC2 && *p < 0xE0 && (p[1] & 0xC0) == 0x80) n = 2;
		else if ((*p & 0xF0) == 0xE0 && (p[1] & 0xC0) == 0x80 && (p[2] & 0xC0) == 0x80) n = 3;
		else if (*p >= 0xF0 && *p <= 0xF4 && (p[1] & 0xC0) == 0x80 && (p[2] & 0xC0) == 0x80 && (p[3] & 0xC0) == 0x80) n = 4;
		if (n) {
			memcpy(out + o, p, n);
			o += n;
			p += n;
			continue;
		}
		unsigned cp = *p;
		if (cp >= 0x80 && cp <= 0xA5) cp = CP850[cp - 0x80];  // ñ = 0xA4, á = 0xA0, é = 0x82…
		// 0xA6–0xFF: Latin-1 (ñ = 0xF1, é = 0xE9…), mismo número en Unicode
		o += put_utf8(out + o, cp);
		++p;
	}
	out[o] = 0;
	return o;
}

int names_fix_dir(const char* dir, bool apply) {
	DIR* d = opendir(dir);
	if (!d) return 0;
	int count = 0;
	struct dirent* e;
	while ((e = readdir(d))) {
		if (names_valid_utf8(e->d_name)) continue;
		char fixed[NAME_MAX * 3 + 1];
		names_fix(e->d_name, fixed, sizeof(fixed));
		char from[PATH_MAX], to[PATH_MAX];
		snprintf(from, sizeof(from), "%s/%s", dir, e->d_name);
		snprintf(to, sizeof(to), "%s/%s", dir, fixed);
		if (!apply) {
			++count;
			continue;
		}
		struct stat st;
		if (stat(to, &st) == 0) continue;  // ya existe uno con el nombre correcto: no se toca
		if (rename(from, to) == 0) ++count;
	}
	closedir(d);
	return count;
}
