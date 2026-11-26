package tw.nekomimi.nekogram.nekomod.media;

import tw.nekomimi.nekogram.NekoModConfig;

/**
 * NekoMod: ayudantes para las reglas de descarga automatica por chat.
 * Bits de la mascara: 1 = fotos, 2 = videos, 4 = archivos. -1 = sin regla propia (usa la configuracion global).
 */
public final class AutoDownloadRules {

    public static final int PHOTO = 1;
    public static final int VIDEO = 2;
    public static final int FILE = 4;
    public static final int ALL = PHOTO | VIDEO | FILE;

    private AutoDownloadRules() {
    }

    /** Mascara del chat, o -1 si no tiene regla. */
    public static int maskFor(long dialogId) {
        return NekoModConfig.getAutoDownloadMask(dialogId);
    }

    public static void set(long dialogId, int mask) {
        NekoModConfig.setAutoDownloadMask(dialogId, mask < 0 ? -1 : (mask & ALL));
    }

    public static void clear(long dialogId) {
        NekoModConfig.setAutoDownloadMask(dialogId, -1);
    }

    /** Ej.: 3 -> "Fotos, Videos"; 0 -> "Nada"; -1 -> "Global". */
    public static String describe(int mask) {
        if (mask < 0) {
            return "Global";
        }
        StringBuilder sb = new StringBuilder();
        if ((mask & PHOTO) != 0) sb.append("Fotos");
        if ((mask & VIDEO) != 0) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("Videos");
        }
        if ((mask & FILE) != 0) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("Archivos");
        }
        return sb.length() == 0 ? "Nada" : sb.toString();
    }
}
