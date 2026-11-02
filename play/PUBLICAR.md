# Cómo publicar en Google Play

1. **Cuenta de desarrollador:** regístrate en https://play.google.com/console (pago único de 25 USD). Las cuentas personales nuevas deben hacer una **prueba cerrada con al menos 12 personas durante 14 días** antes de publicar en producción.
2. **Nombre de paquete definitivo:** cámbialo en `export_presets.cfg` (`package/unique_name`) antes de la primera subida. Después ya no se puede cambiar.
3. **Llave de publicación (upload key):** se crea una sola vez y se guarda en *Settings → Secrets and variables → Actions* del repositorio como:
   - `RELEASE_KEYSTORE_BASE64` – el archivo .keystore en base64
   - `RELEASE_KEYSTORE_USER` – el alias de la llave
   - `RELEASE_KEYSTORE_PASSWORD` – la contraseña
   **Nunca la subas al repositorio** y guárdala también fuera de GitHub.
4. **Compilar el AAB:** en *Actions → Compilar AAB para Google Play → Run workflow*. El archivo sale en *Artifacts* como `turbo-terraceria-aab-publicacion`. (Si no hay llave guardada, sale como `PRUEBA-NO-SUBIR`, solo para comprobar que compila.)
5. **Crear la app en Play Console:** sube el AAB a *Prueba cerrada*, usa los textos de `FICHA_PLAY.md`, las imágenes de esta carpeta y el enlace de `PRIVACIDAD.md`. Activa *Firma de apps de Google Play* (viene activada por defecto).
6. Cada nueva versión: vuelve a correr el flujo (el número de versión sube solo) y sube el AAB nuevo.
