# GBA IA

Emulador de Game Boy Advance para Android (Poco F7) basado en **mGBA**, con mejora de imagen por IA por capas (en desarrollo).
Esta rama (`gba-ia`) es independiente del juego Turbo Terracería de la rama `main`.

## Descargar el APK

Cada cambio en esta rama se compila solo en GitHub Actions. Enlaces fijos a la última versión:

- APK: https://github.com/nasari1988/turbo-terraceria/releases/download/gba-ia-ultima/gba-ia.apk
- ROM de prueba Mode 7: https://github.com/nasari1988/turbo-terraceria/releases/download/gba-ia-ultima/mode7demo.gba

Se instala encima de la versión anterior sin borrar tus partidas.

## Fase 1: emulador base

- Núcleo mGBA 0.10.5 con BIOS integrada (no necesitas BIOS).
- Imagen a escala entera nítida (8x en el Poco F7).
- Sonido, partidas guardadas automáticas y estados de guardado.
- Controles táctiles multitáctiles y control Bluetooth/USB (los táctiles se esconden al usar el control).
- Abre `.gba` o `.zip`. Compatible con launchers (Daijishō, Pegasus) vía `ACTION_VIEW`.
- Menú: botón **Atrás**, **Select + Start** o el botón central del control.

## Fase 2: alta resolución por GPU

- Renderizador OpenGL ES 3 de mGBA: el juego se dibuja internamente a 2x, 4x, 6x u 8x.
  Las capas rotadas/escaladas (Mode 7) y los sprites que giran se ven lisos en lugar de escalonados.
- Menú del juego > **Resolución**. 8x (1920×1280) es el tamaño exacto de la pantalla del Poco F7.
  "Original" usa el renderizador por software (el más exacto).
- **ROM de prueba propia:** `mode7demo.gba` (se descarga junto al APK en Releases). Cruceta: girar y avanzar; A: más rápido.
  Código en `tools/mode7demo/` (se compila con `build.sh`).

## Fase 3 (actual): IA en tiempo real (v1)

- Red neuronal ligera entrenada para imitar a Real-ESRGAN en pixel art. Escala el cuadro 4x (960×640) en la GPU, cada cuadro.
- Está activada por defecto: menú del juego > **Resolución** > "IA 4x". "Mostrar FPS" en el menú indica si va fluido.
- Entrenada solo con escenas sintéticas originales (`tools/ai/synth.py`), sin material de juegos.
- `tools/ai/`: generador de escenas, red (`student.py`), entrenamiento (`train.py`), modelo (`student.pt`)
  y conversión a shaders GLES3 (`gen_shaders.py` → `app/src/main/cpp/ai_shaders.h`). Verificado: los shaders dan el mismo resultado que PyTorch (diferencia máx. 1/255).

## Siguientes pasos

- IA v2: red más grande y más entrenamiento (más nítida, más cerca de Real-ESRGAN).
- IA por capas (fondo y sprites por separado) y caché de tiles.
- NPU Hexagon.
- Launcher propio.

## Estructura

- `app/src/main/cpp/` — envoltura en C sobre mGBA (`gba_core.c`) y puente JNI.
- `app/src/main/java/com/nasari/gbaia/` — app en Kotlin.
- `scripts/get-mgba.sh` — descarga mGBA (no se guarda en el repo).
- `.github/workflows/gba-ia.yml` — compilación automática.

mGBA © endrift y colaboradores, licencia MPL 2.0.
