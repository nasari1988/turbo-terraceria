package tw.nekomimi.nekogram.nekomod.crash;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Process;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** NekoMod: muestra el último error para copiarlo. Corre en el proceso ":nekocrash". */
public class CrashReportActivity extends Activity {

    private String collectDiagnostics() {
        StringBuilder sb = new StringBuilder();
        sb.append("Neko Mod - diagnóstico de cierres\n");
        sb.append("Android: ").append(android.os.Build.VERSION.RELEASE).append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n");
        sb.append("Equipo: ").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL).append("\n");
        try {
            sb.append("Version: ").append(getPackageManager().getPackageInfo(getPackageName(), 0).versionName).append("\n");
        } catch (Throwable ignore) {
        }
        sb.append("\n=== Último error Java guardado ===\n");
        try {
            java.io.File f = new java.io.File(getFilesDir(), CrashCatcher.CRASH_FILE);
            if (f.exists()) {
                byte[] data = new byte[(int) Math.min(f.length(), 200_000)];
                try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                    int n = in.read(data);
                    sb.append(new String(data, 0, Math.max(n, 0), java.nio.charset.StandardCharsets.UTF_8));
                }
            } else {
                sb.append("(ninguno)\n");
            }
        } catch (Throwable e) {
            sb.append("No se pudo leer: ").append(e).append("\n");
        }
        sb.append("\n=== Cierres registrados por Android ===\n");
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            try {
                android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                java.util.List<android.app.ApplicationExitInfo> list = am.getHistoricalProcessExitReasons(getPackageName(), 0, 6);
                if (list.isEmpty()) {
                    sb.append("(ninguno)\n");
                }
                for (android.app.ApplicationExitInfo info : list) {
                    sb.append("\n--- ").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(new java.util.Date(info.getTimestamp())))
                            .append(" proceso=").append(info.getProcessName())
                            .append(" motivo=").append(reasonName(info.getReason()))
                            .append(" estado=").append(info.getStatus())
                            .append("\n");
                    if (info.getDescription() != null) {
                        sb.append("descripción: ").append(info.getDescription()).append("\n");
                    }
                    int r = info.getReason();
                    if (r == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE || r == android.app.ApplicationExitInfo.REASON_ANR || r == android.app.ApplicationExitInfo.REASON_CRASH) {
                        try (java.io.InputStream in = info.getTraceInputStream()) {
                            if (in != null) {
                                sb.append(readableText(in, 60_000)).append("\n");
                            }
                        } catch (Throwable e) {
                            sb.append("(sin traza: ").append(e.getClass().getSimpleName()).append(")\n");
                        }
                    }
                }
            } catch (Throwable e) {
                sb.append("No disponible: ").append(e).append("\n");
            }
        } else {
            sb.append("Requiere Android 11 o superior.\n");
        }
        return sb.toString();
    }

    private static String reasonName(int reason) {
        switch (reason) {
            case 4: return "CRASH (Java)";
            case 5: return "CRASH_NATIVE";
            case 6: return "ANR";
            case 7: return "INIT_FAILURE";
            case 3: return "LOW_MEMORY";
            case 1: return "EXIT_SELF";
            case 2: return "SIGNALED";
            case 10: return "USER_REQUESTED";
            default: return String.valueOf(reason);
        }
    }

    /** Extrae las partes legibles (la traza nativa viene en formato binario). */
    private static String readableText(java.io.InputStream in, int max) throws java.io.IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) > 0 && buf.size() < 2_000_000) {
            buf.write(chunk, 0, n);
        }
        byte[] data = buf.toByteArray();
        StringBuilder out = new StringBuilder();
        StringBuilder run = new StringBuilder();
        for (byte b : data) {
            int c = b & 0xFF;
            if (c >= 32 && c < 127 || c == '\n' || c == '\t') {
                run.append((char) c);
            } else {
                if (run.length() >= 6) {
                    out.append(run).append('\n');
                }
                run.setLength(0);
            }
            if (out.length() > max) break;
        }
        if (run.length() >= 6) out.append(run);
        return out.toString();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String report = getIntent() != null ? getIntent().getStringExtra("report") : null;
        if (report == null) {
            report = collectDiagnostics();
        }
        final String text = report;
        int pad = (int) (16 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF17212B);
        root.setPadding(pad, pad * 2, pad, pad);

        TextView title = new TextView(this);
        title.setText(getIntent() != null && getIntent().hasExtra("report") ? "Neko Mod se cerró por un error" : "Neko Mod Diagnóstico");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("Toca \"Copiar reporte\" y pégalo en el chat con Claude.");
        hint.setTextColor(0xFFC5CDD5);
        hint.setTextSize(14);
        hint.setPadding(0, pad / 2, 0, pad);
        root.addView(hint);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button copy = new Button(this);
        copy.setText("Copiar reporte");
        copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("Neko Mod crash", text));
                Toast.makeText(this, "Copiado", Toast.LENGTH_SHORT).show();
            }
        });
        Button share = new Button(this);
        share.setText("Compartir");
        share.setOnClickListener(v -> {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_TEXT, text);
            startActivity(Intent.createChooser(send, "Compartir reporte"));
        });
        Button close = new Button(this);
        close.setText("Cerrar");
        close.setOnClickListener(v -> {
            finishAndRemoveTask();
            Process.killProcess(Process.myPid());
        });
        buttons.addView(copy, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        buttons.addView(share, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        buttons.addView(close, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(buttons);

        ScrollView scroll = new ScrollView(this);
        TextView body = new TextView(this);
        body.setText(text);
        body.setTextColor(0xFFE0E6EB);
        body.setTextSize(11);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextIsSelectable(true);
        body.setGravity(Gravity.START);
        body.setPadding(0, pad, 0, pad);
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }
}
