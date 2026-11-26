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
