# GBA IA

Emulador de Game Boy Advance para Android (Poco F7) basado en **mGBA**, con mejora de imagen por IA por capas (en desarrollo).
Esta rama (`gba-ia`) es independiente del juego Turbo Terracería de la rama `main`.

## Descargar el APK

Cada cambio en esta rama se compila solo en GitHub Actions. Enlace fijo a la última versión:

https://github.com/nasari1988/turbo-terraceria/releases/download/gba-ia-ultima/gba-ia.apk

Se instala encima de la versión anterior sin borrar tus partidas.

## Fase 1 (actual): emulador base

- Núcleo mGBA 0.10.5 con BIOS integrada (no necesitas BIOS).
- Imagen a escala entera nítida (8x en el Poco F7).
- Sonido, partidas guardadas automáticas y estados de guardado.
- Controles táctiles multitáctiles y control Bluetooth/USB (los táctiles se esconden al usar el control).
- Abre `.gba` o `.zip`. Compatible con launchers (Daijishō, Pegasus) vía `ACTION_VIEW`.
- Menú: botón **Atrás**, **Select + Start** o el botón central del control.

## Siguientes fases

2. Renderizado a alta resolución de capas rotadas/escaladas (Mode 7).
3. IA por capas con caché de tiles (GPU y luego NPU Hexagon).
4. Launcher propio.

## Estructura

- `app/src/main/cpp/` — envoltura en C sobre mGBA (`gba_core.c`) y puente JNI.
- `app/src/main/java/com/nasari/gbaia/` — app en Kotlin.
- `scripts/get-mgba.sh` — descarga mGBA (no se guarda en el repo).
- `.github/workflows/gba-ia.yml` — compilación automática.

mGBA © endrift y colaboradores, licencia MPL 2.0.
