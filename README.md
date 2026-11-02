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
4. **Descarga el APK:** abre la ejecución en verde y, en la sección **Artifacts**, descarga `turbo-terraceria-apk` (viene en un .zip).
5. **Instálalo en tu teléfono:** pasa el `.apk` al teléfono y ábrelo. Android pedirá permiso para "instalar apps desconocidas".

Si ves un auto rojo girando sobre un piso café y al tocar la pantalla cambia de color, la Fase 0 está completa.

## Para editar en tu computadora (opcional)

Instala Godot **4.7.2** (versión estándar, no .NET) desde godotengine.org, abre Godot, elige **Importar** y selecciona `project.godot`.

## Estructura

```
project.godot                     Configuración del proyecto (renderizador móvil)
export_presets.cfg                Preset de exportación "Android" (solo arm64)
scenes/main.tscn                  Escena inicial
scripts/main.gd                   Lógica de la escena de prueba
.github/workflows/android.yml     Compilación automática del APK
```

## Notas

- El APK que se genera es de **prueba (debug)**, firmado con una llave temporal. Sirve para instalarlo en tu teléfono, pero no para Google Play. La firma de *release* se configura en la Fase 5.
- Antes de publicar, cambia `package/unique_name` (`com.ejemplo.turboterraceria`) por uno tuyo. Después de publicar ya no se puede cambiar.
- Nunca subas archivos `.keystore` al repositorio: el `.gitignore` ya los excluye.
