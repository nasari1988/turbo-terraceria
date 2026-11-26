# Neko Mod

Fork personal de [Nekogram](https://github.com/Nekogram/Nekogram) (GPL-2.0).

- Paquete: `io.nasari.nekomod` (se instala junto a Telegram/Nekogram sin chocar).
- Solo se compila `arm64-v8a`.
- La APK se compila con GitHub Actions (`.github/workflows/build-nekomod.yml`) en cada push a la rama `nekogram`.

## Secrets necesarios (Settings → Secrets and variables → Actions)

| Secret | Obligatorio | Qué es |
|---|---|---|
| `API_ID` | Sí | De https://my.telegram.org → API development tools |
| `API_HASH` | Sí | Igual que arriba |
| `KEYSTORE_PASSWORD` | Recomendado | Contraseña de `keystore.p12` (firma estable para poder actualizar) |
| `GOOGLE_SERVICES_JSON` | No | `google-services.json` de Firebase en base64, para notificaciones push |
