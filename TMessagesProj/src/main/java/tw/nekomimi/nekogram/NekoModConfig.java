package tw.nekomimi.nekogram;

import android.app.Activity;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;

/**
 * Ajustes propios de Neko Mod. Se guardan aparte de los de Nekogram
 * (SharedPreferences "nekomodconfig") para no chocar al actualizar desde upstream.
 */
public class NekoModConfig {

    // ---- Apariencia ----
    public static final int BUBBLE_ROUNDED = 0;
    public static final int BUBBLE_SQUARE = 1;
    public static final int BUBBLE_IOS = 2;

    public static final int FONT_DEFAULT = 0;
    public static final int FONT_INTER = 1;
    public static final int FONT_SYSTEM = 2;

    public static final int ANIM_FAST = 0;
    public static final int ANIM_NORMAL = 1;
    public static final int ANIM_SMOOTH = 2;
    public static final int ANIM_OFF = 3;

    public static boolean amoledTheme = false;      // fondo negro puro en temas oscuros
    public static int bubbleStyle = BUBBLE_ROUNDED; // forma de las burbujas
    public static int fontFamily = FONT_DEFAULT;    // tipografía de la app
    public static boolean compactChatList = false;  // filas más bajas en la lista de chats
    public static boolean squareAvatars = false;    // avatares cuadrados redondeados
    public static boolean glassBars = false;        // barras translúcidas con desenfoque
    public static int animationSpeed = ANIM_NORMAL; // velocidad de animaciones

    // ---- Videos ----
    public static boolean videoAutoScroll = false;  // pasar al siguiente video al terminar
    public static int autoScrollDelay = 3;          // segundos de cuenta regresiva (0-10)
    public static boolean autoScrollGifs = false;   // incluir GIFs
    public static boolean autoScrollLoop = false;   // volver al primero al llegar al final

    // ---- Privacidad: mensajes ----
    public static boolean saveDeletedMessages = true; // conservar mensajes borrados marcados
    public static boolean saveEditHistory = true;     // guardar versiones de mensajes editados

    // ---- Privacidad: modo fantasma ----
    public static boolean ghostMode = false;          // interruptor general
    public static boolean ghostNoRead = true;         // no enviar "leído"
    public static boolean ghostNoTyping = true;       // no enviar "escribiendo…"
    public static boolean ghostNoOnline = true;       // no aparecer "en línea"
    public static boolean ghostNoStories = true;      // ver historias sin marcar vistas
    public static boolean ghostReadOnReply = false;   // al responder sí marcar como leído

    // ---- Privacidad: candado y chats ocultos ----
    public static final int RELOCK_ON_EXIT = 0;       // al salir de la app
    public static final int RELOCK_ALWAYS = 1;        // cada vez que se abre el chat
    public static String lockedDialogs = "";          // ids separados por coma
    public static String lockedFolders = "";          // ids de carpeta separados por coma
    public static int relockMode = RELOCK_ON_EXIT;
    public static boolean lockHidePreview = true;     // ocultar vista previa en notificaciones de chats bloqueados
    public static String hiddenDialogs = "";          // ids separados por coma
    public static String hiddenPinHash = "";          // SHA-256 del PIN para ver chats ocultos ("" = sin configurar)

    // ---- Privacidad: código falso y disfraz ----
    public static String fakePasscodeHash = "";       // SHA-256 del código falso ("" = desactivado)
    public static boolean fakeWipeHidden = false;     // al usar el código falso, borrar chats ocultos
    public static boolean calculatorDisguise = false; // ícono y pantalla de calculadora

    // ---- Contenido ----
    public static boolean allowSaveProtected = true;   // copiar/guardar/reenviar en chats con restricción
    public static boolean saveViewOnce = false;        // guardar fotos/videos de un solo uso al abrirlos

    // ---- Multimedia ----
    public static final int QUALITY_AUTO = 0;
    public static final int QUALITY_HIGH = 1;
    public static final int QUALITY_MEDIUM = 2;
    public static final int QUALITY_LOW = 3;
    public static int videoPlaybackQuality = QUALITY_AUTO; // calidad de reproducción preferida
    public static boolean saveBestQuality = true;      // guardar en galería siempre la mejor calidad
    public static boolean askQualityOnSave = false;    // mostrar el menú de calidad al guardar
    public static boolean backgroundPlayback = true;   // seguir con pantalla apagada / en segundo plano
    public static boolean sendOriginalVideo = false;   // enviar video sin comprimir (no como archivo)
    public static float defaultVoiceSpeed = 1.0f;      // velocidad por defecto de audios
    public static String autoDownloadRules = "";       // reglas por chat: "dialogId:mask;..." (mask bits 1=foto 2=video 4=archivo)

    // ---- Descarga masiva ----
    public static String syncedDialogs = "";           // chats sincronizados (ids separados por coma)
    public static boolean nightlySync = false;         // sincronizar cada noche con Wi-Fi
    public static boolean redownloadDeleted = false;   // volver a bajar archivos que borraste de la carpeta

    // Estado en memoria (no se guarda): true tras entrar con el código falso
    public static volatile boolean fakeModeActive = false;
    // Estado en memoria: true cuando el usuario desbloqueó los chats ocultos en esta sesión
    public static volatile boolean hiddenUnlocked = false;

    private static SharedPreferences preferences;

    static {
        load();
    }

    private static SharedPreferences prefs() {
        if (preferences == null && ApplicationLoader.applicationContext != null) {
            preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekomodconfig", Activity.MODE_PRIVATE);
        }
        return preferences;
    }

    public static void load() {
        SharedPreferences preferences = prefs();
        if (preferences == null) {
            return; // muy temprano en el arranque: se usan valores por defecto
        }
        amoledTheme = preferences.getBoolean("amoledTheme", false);
        bubbleStyle = preferences.getInt("bubbleStyle", BUBBLE_ROUNDED);
        fontFamily = preferences.getInt("fontFamily", FONT_DEFAULT);
        compactChatList = preferences.getBoolean("compactChatList", false);
        squareAvatars = preferences.getBoolean("squareAvatars", false);
        glassBars = preferences.getBoolean("glassBars", false);
        animationSpeed = preferences.getInt("animationSpeed", ANIM_NORMAL);

        videoAutoScroll = preferences.getBoolean("videoAutoScroll", false);
        autoScrollDelay = preferences.getInt("autoScrollDelay", 3);
        autoScrollGifs = preferences.getBoolean("autoScrollGifs", false);
        autoScrollLoop = preferences.getBoolean("autoScrollLoop", false);

        saveDeletedMessages = preferences.getBoolean("saveDeletedMessages", true);
        saveEditHistory = preferences.getBoolean("saveEditHistory", true);
        ghostMode = preferences.getBoolean("ghostMode", false);
        ghostNoRead = preferences.getBoolean("ghostNoRead", true);
        ghostNoTyping = preferences.getBoolean("ghostNoTyping", true);
        ghostNoOnline = preferences.getBoolean("ghostNoOnline", true);
        ghostNoStories = preferences.getBoolean("ghostNoStories", true);
        ghostReadOnReply = preferences.getBoolean("ghostReadOnReply", false);
        lockedDialogs = preferences.getString("lockedDialogs", "");
        lockedFolders = preferences.getString("lockedFolders", "");
        relockMode = preferences.getInt("relockMode", RELOCK_ON_EXIT);
        lockHidePreview = preferences.getBoolean("lockHidePreview", true);
        hiddenDialogs = preferences.getString("hiddenDialogs", "");
        hiddenPinHash = preferences.getString("hiddenPinHash", "");
        fakePasscodeHash = preferences.getString("fakePasscodeHash", "");
        fakeWipeHidden = preferences.getBoolean("fakeWipeHidden", false);
        calculatorDisguise = preferences.getBoolean("calculatorDisguise", false);

        allowSaveProtected = preferences.getBoolean("allowSaveProtected", true);
        saveViewOnce = preferences.getBoolean("saveViewOnce", false);
        videoPlaybackQuality = preferences.getInt("videoPlaybackQuality", QUALITY_AUTO);
        saveBestQuality = preferences.getBoolean("saveBestQuality", true);
        askQualityOnSave = preferences.getBoolean("askQualityOnSave", false);
        backgroundPlayback = preferences.getBoolean("backgroundPlayback", true);
        sendOriginalVideo = preferences.getBoolean("sendOriginalVideo", false);
        defaultVoiceSpeed = preferences.getFloat("defaultVoiceSpeed", 1.0f);
        autoDownloadRules = preferences.getString("autoDownloadRules", "");
        syncedDialogs = preferences.getString("syncedDialogs", "");
        nightlySync = preferences.getBoolean("nightlySync", false);
        redownloadDeleted = preferences.getBoolean("redownloadDeleted", false);
    }

    public static void setBoolean(String key, boolean value) {
        SharedPreferences preferences = prefs();
        if (preferences == null) return;
        preferences.edit().putBoolean(key, value).apply();
        load();
    }

    public static void setInt(String key, int value) {
        SharedPreferences preferences = prefs();
        if (preferences == null) return;
        preferences.edit().putInt(key, value).apply();
        load();
    }

    public static void setString(String key, String value) {
        SharedPreferences preferences = prefs();
        if (preferences == null) return;
        preferences.edit().putString(key, value).apply();
        load();
    }

    public static void setFloat(String key, float value) {
        SharedPreferences preferences = prefs();
        if (preferences == null) return;
        preferences.edit().putFloat(key, value).apply();
        load();
    }

    /** Máscara de descarga automática para un chat, o -1 si no tiene regla propia. */
    public static int getAutoDownloadMask(long dialogId) {
        if (autoDownloadRules == null || autoDownloadRules.isEmpty()) return -1;
        for (String rule : autoDownloadRules.split(";")) {
            int i = rule.indexOf(':');
            if (i <= 0) continue;
            try {
                if (Long.parseLong(rule.substring(0, i)) == dialogId) return Integer.parseInt(rule.substring(i + 1));
            } catch (NumberFormatException ignore) { }
        }
        return -1;
    }

    public static void setAutoDownloadMask(long dialogId, int mask) {
        StringBuilder sb = new StringBuilder();
        if (autoDownloadRules != null) {
            for (String rule : autoDownloadRules.split(";")) {
                if (rule.isEmpty() || rule.startsWith(dialogId + ":")) continue;
                if (sb.length() > 0) sb.append(';');
                sb.append(rule);
            }
        }
        if (mask >= 0) {
            if (sb.length() > 0) sb.append(';');
            sb.append(dialogId).append(':').append(mask);
        }
        setString("autoDownloadRules", sb.toString());
    }

    // ---- utilidades para listas de ids guardadas como "1,2,3" ----
    public static java.util.Set<Long> parseIds(String csv) {
        java.util.HashSet<Long> set = new java.util.HashSet<>();
        if (csv == null || csv.isEmpty()) return set;
        for (String p : csv.split(",")) {
            try { set.add(Long.parseLong(p.trim())); } catch (NumberFormatException ignore) { }
        }
        return set;
    }

    public static String joinIds(java.util.Set<Long> ids) {
        StringBuilder sb = new StringBuilder();
        for (Long id : ids) {
            if (sb.length() > 0) sb.append(',');
            sb.append(id);
        }
        return sb.toString();
    }

    public static boolean isGhost() { return ghostMode && !fakeModeActive; }

    public static boolean isDialogLocked(long dialogId) { return parseIds(lockedDialogs).contains(dialogId); }

    public static boolean isFolderLocked(int folderId) { return parseIds(lockedFolders).contains((long) folderId); }

    public static boolean isDialogHidden(long dialogId) { return parseIds(hiddenDialogs).contains(dialogId); }

    /** SHA-256 en hex, para PIN y códigos. */
    public static String sha256(String text) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(("nekomod:" + text).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** Multiplicador para duraciones de animación según animationSpeed. 0 = sin animación. */
    public static float getAnimationScale() {
        switch (animationSpeed) {
            case ANIM_FAST: return 0.6f;
            case ANIM_SMOOTH: return 1.4f;
            case ANIM_OFF: return 0f;
            default: return 1f;
        }
    }
}
