#pragma once

#include <stdbool.h>

// Requieren contexto GL activo. Si la GPU no soporta la medición, no hacen nada.
void gpu_timer_begin(void);
void gpu_timer_end(void);
float gpu_timer_ms(void);  // promedio en milisegundos; -1 si aún no hay datos
void gpu_timer_reset(void);  // llamar antes de destruir el contexto
