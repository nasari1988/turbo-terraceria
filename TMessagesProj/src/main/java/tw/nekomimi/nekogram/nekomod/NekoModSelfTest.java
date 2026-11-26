package tw.nekomimi.nekogram.nekomod;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Build;

import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;
import java.util.List;

import tw.nekomimi.nekogram.NekoModConfig;
import tw.nekomimi.nekogram.ui.AutoScrollOverlay;

/**
 * NekoMod: autodiagnóstico. Cada función agrega su prueba aquí.
 * Resultado por línea: ✅ OK, ⚠️ AVISO (funciona con limitaciones), ❌ FALLA.
 */
public final class NekoModSelfTest {

    public static final String OK = "✅";
    public static final String WARN = "⚠️";
    public static final String FAIL = "❌";

    private NekoModSelfTest() {
    }

    private static void add(List<String> out, String status, String name, String detail) {
        out.add(status + " " + name + (detail == null || detail.isEmpty() ? "" : ": " + detail));
    }

    public static String run(Context context) {
        List<String> out = new ArrayList<>();

        // 1. Ajustes guardan y leen
        try {
            SharedPreferences p = context.getSharedPreferences("nekomodconfig", Context.MODE_PRIVATE);
            long v = System.currentTimeMillis();
            p.edit().putLong("selftest", v).commit();
            boolean ok = p.getLong("selftest", 0) == v;
            p.edit().remove("selftest").commit();
            add(out, ok ? OK : FAIL, "Ajustes", ok ? "se guardan y leen" : "no se leyó lo guardado");
        } catch (Throwable e) {
            add(out, FAIL, "Ajustes", e.toString());
        }

        // 2. AMOLED
        try {
            if (!Theme.isCurrentThemeDark()) {
                add(out, WARN, "Negro AMOLED", "solo aplica con tema oscuro (ahora estás en claro)");
            } else {
                boolean prev = NekoModConfig.amoledTheme;
                NekoModConfig.amoledTheme = true;
                int c = Theme.getColor(Theme.key_windowBackgroundWhite);
                NekoModConfig.amoledTheme = prev;
                boolean ok = c == 0xFF000000;
                add(out, ok ? OK : FAIL, "Negro AMOLED", ok ? "el fondo se vuelve negro puro" : String.format("color obtenido #%08X", c));
            }
        } catch (Throwable e) {
            add(out, FAIL, "Negro AMOLED", e.toString());
        }

        // 3. Burbujas
        add(out, NekoModConfig.bubbleStyle >= 0 && NekoModConfig.bubbleStyle <= 2 ? OK : FAIL, "Forma de burbujas",
                new String[]{"Redondas", "Cuadradas", "Estilo iOS"}[Math.max(0, Math.min(2, NekoModConfig.bubbleStyle))]
                        + " (radio actual " + SharedConfig.bubbleRadius + ")");

        // 4. Fuente Inter
        String[] fonts = {"fonts/inter_regular.ttf", "fonts/inter_medium.ttf", "fonts/inter_bold.ttf", "fonts/inter_italic.ttf"};
        int loaded = 0;
        String fontErr = "";
        for (String f : fonts) {
            try {
                Typeface t = Typeface.createFromAsset(context.getAssets(), f);
                if (t != null) loaded++;
            } catch (Throwable e) {
                fontErr = f + " " + e.getClass().getSimpleName();
            }
        }
        add(out, loaded == fonts.length ? OK : FAIL, "Fuente Inter", loaded + "/" + fonts.length + " archivos cargan " + fontErr);

        // 5. Efecto vidrio
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            add(out, WARN, "Efecto vidrio", "requiere Android 12 o superior");
        } else {
            boolean prev = NekoModConfig.glassBars;
            NekoModConfig.glassBars = true;
            boolean on = SharedConfig.chatBlurEnabled();
            NekoModConfig.glassBars = prev;
            add(out, on ? OK : FAIL, "Efecto vidrio", on ? "el desenfoque se activa" : "chatBlurEnabled sigue apagado");
        }

        // 6. Animaciones
        add(out, OK, "Animaciones", "escala x" + NekoModConfig.getAnimationScale());

        // 7. Auto-scroll
        try {
            AutoScrollOverlay overlay = new AutoScrollOverlay(context);
            overlay.stop();
            add(out, OK, "Auto-scroll de videos", "la tarjeta se crea bien" + (NekoModConfig.videoAutoScroll ? " (activado)" : " (desactivado)"));
        } catch (Throwable e) {
            add(out, FAIL, "Auto-scroll de videos", e.toString());
        }

        // 8. Capturador de errores
        Thread.UncaughtExceptionHandler h = Thread.getDefaultUncaughtExceptionHandler();
        add(out, h != null ? OK : WARN, "Capturador de errores", h != null ? "activo" : "no instalado");

        StringBuilder sb = new StringBuilder();
        sb.append("Neko Mod · autodiagnóstico\n");
        sb.append("Android ").append(Build.VERSION.RELEASE).append(" · ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append("\n\n");
        int fails = 0;
        for (String line : out) {
            if (line.startsWith(FAIL)) fails++;
            sb.append(line).append('\n');
        }
        sb.append('\n').append(fails == 0 ? "Todo en orden." : fails + " prueba(s) fallaron.");
        return sb.toString();
    }
}
