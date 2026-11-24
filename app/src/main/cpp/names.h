#pragma once
#include <stdbool.h>
#include <stddef.h>

// ¿El nombre es UTF-8 válido?
bool names_valid_utf8(const char* s);
// Convierte bytes sueltos (CP850 para 0x80–0xA5, Latin-1 para el resto) a UTF-8.
size_t names_fix(const char* in, char* out, size_t outSize);
// Cuenta (apply=false) o renombra (apply=true) las entradas de dir con nombre dañado.
int names_fix_dir(const char* dir, bool apply);
