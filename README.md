# Turbo Terracería

Juego de carreras arcade para Android hecho con **Godot 4.7.2**.
"Turbo Terracería" es un nombre provisional: cámbialo cuando quieras en `project.godot` y `export_presets.cfg`.

## Fase 0: subir el proyecto y obtener el primer APK

1. **Crea el repositorio** en GitHub: botón **New**, nombre `turbo-terraceria`, sin README.
2. **Sube estos archivos** con una de estas dos opciones:
   - **Desde la web:** en el repositorio vacío, toca *uploading an existing file* y arrastra todo el contenido de la carpeta. La carpeta `.github` es oculta: asegúrate de que se suba.
   - **Desde la terminal:**
     ```bash
     cd turbo-terraceria
     git init
     git add .
     git commit -m "Fase 0: proyecto base"
     git branch -M main
     git remote add origin https://github.com/TU_USUARIO/turbo-terraceria.git
     git push -u origin main
     ```
3. **Espera la compilación:** entra a la pestaña **Actions**. El flujo *Compilar APK Android* arranca solo. La primera vez tarda unos 5–8 minutos porque descarga Godot; después usa la caché.
4. **Descarga el APK:** cada versión nueva queda en este enlace fijo, sin zip:
   https://github.com/nasari1988/turbo-terraceria/releases/download/ultima-version/turbo-terraceria-debug.apk
   (también está como `turbo-terraceria-apk` en la sección **Artifacts** de cada ejecución).
5. **Instálalo en tu teléfono:** pasa el `.apk` al teléfono y ábrelo. Android pedirá permiso para "instalar apps desconocidas".

Si ves un auto rojo girando sobre un piso café y al tocar la pantalla cambia de color, la Fase 0 está completa.

## Fase 4: autos, pistas y torneo (actual)

- **5 pistas:** Óvalo de Tierra, Cañón Seco (desierto), Pico Helado (nieve, poco agarre), Sendero del Bosque y Calles del Pueblo. Cada una con su decoración, colores, cielo y polvo.
- **4 autos con forma de stock car** y estadísticas distintas: Coyote (equilibrado), Liebre (aceleración), Halcón (manejo) y Toro (pesado y veloz).
- **Torneo:** 4 carreras (desierto, nieve, bosque, pueblo) con puntos 10-8-6-4-2-1.
- **Desbloqueables:** sube al podio en una pista para abrir la siguiente; gana una carrera para el Halcón y el torneo para el Toro.
- **Progreso guardado:** desbloqueos y mejor vuelta por pista.

## Fase 3: el toque "Rumble"

- **Cajas de power-ups** (cubos amarillos con ?): antes de la rampa, al salir de la curva derecha, en la recta de arriba y en el atajo. El botón naranja muestra el objeto; tócalo para usarlo (E / Shift en la computadora).
  - **TURBO:** empujón de velocidad.
  - **ESCUDO:** burbuja que bloquea el siguiente ataque.
  - **ACEITE:** mancha detrás de ti; quien la pisa da un trompo.
  - **ONDA:** empuja y hace girar a los autos cercanos.
  - Los de atrás reciben más turbos y ondas; los de adelante, más defensas. Los rivales también los usan.
- **Choques:** chispas, golpe y sacudida de cámara. **Polvo** en las llantas al acelerar y derrapar.
- **Sonido:** motor según la velocidad, derrape, golpes, power-ups, cuenta regresiva, meta y música original. Todo se genera por código (`scripts/sfx.gd`), sin archivos de audio. Se puede apagar desde el menú.

## Fase 2: carrera completa

- **Menú principal:** CARRERA (elige 1, 3, 5 o 10 vueltas) o PRÁCTICA LIBRE.
- **Carrera:** 5 rivales con IA (algunos toman el atajo), cuenta regresiva, vueltas, posición en vivo, cronómetro, última y mejor vuelta, aviso de última vuelta y de sentido contrario.
- **Resultados:** tabla final con tiempos, botones REVANCHA y MENÚ.
- **Pausa:** botón II (o Esc / P en la computadora) con CONTINUAR, REINICIAR y MENÚ PRINCIPAL.
- **R** te regresa al centro de la pista, donde ibas.

## Fase 1: auto manejable

- **Controles táctiles:** `<` `>` para girar (izquierda de la pantalla), **GAS** y **FRENO** a la derecha, **R** para volver a la salida.
- **Derrape:** a buena velocidad, mantén **FRENO** mientras giras. El auto abre la cola sin perder velocidad.
- **Reversa:** mantén FRENO estando detenido.
- **Pista de prueba:** óvalo de terracería con una rampa en la recta y un atajo por el centro con su propio salto. El pasto frena.
- **En la computadora:** W/A/S/D o flechas, Espacio = freno, R = reiniciar.

Los valores de manejo (aceleración, agarre, derrape, giro) están al inicio de `scripts/car.gd`, para afinarlos fácilmente.

## Para editar en tu computadora (opcional)

Instala Godot **4.7.2** (versión estándar, no .NET) desde godotengine.org, abre Godot, elige **Importar** y selecciona `project.godot`.

## Estructura

```
project.godot                     Configuración del proyecto (renderizador móvil)
export_presets.cfg                Preset de exportación "Android" (solo arm64)
scenes/main.tscn                  Escena inicial
scripts/main.gd                   Arma el juego: pista, auto, cámara, controles
scripts/car.gd                    Física y modelo del auto
scripts/track.gd                  Pista de prueba
scripts/follow_camera.gd          Cámara que sigue al auto
scripts/ui.gd                     Botones táctiles y HUD de carrera
scripts/race.gd                   Reglas de la carrera: parrilla, vueltas, posiciones
scripts/ai_driver.gd              Pilotos con IA
scripts/menu.gd                   Menú principal, pausa y resultados
scripts/powerups.gd               Cajas de objetos y sus efectos
scripts/sfx.gd                    Sonidos y música generados por código
scripts/spline_track.gd           Pistas temáticas (desierto, nieve, bosque, pueblo)
scripts/game_state.gd             Autos, pistas, torneo y progreso guardado
.github/workflows/android.yml     Compilación automática del APK
```

## Notas

- El APK que se genera es de **prueba (debug)**, firmado con la llave de prueba `ci/debug.keystore` (siempre la misma, para que cada versión se instale encima de la anterior). Sirve para instalarlo en tu teléfono, pero no para Google Play. La firma de *release* se configura en la Fase 5.
- Antes de publicar, cambia `package/unique_name` (`com.ejemplo.turboterraceria`) por uno tuyo. Después de publicar ya no se puede cambiar.
- Nunca subas archivos `.keystore` al repositorio: el `.gitignore` ya los excluye.
