package tw.nekomimi.nekogram.nekomod.crash;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Process;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * NekoMod: captura cierres inesperados, guarda el error y abre una pantalla
 * (en un proceso aparte) para que el usuario pueda copiarlo.
 */
public final class CrashCatcher {

    public static final String CRASH_FILE = "nekomod_last_crash.txt";
    private static boolean installed;

    private CrashCatcher() {
    }

    public static boolean isCrashProcess() {
        if (Build.VERSION.SDK_INT >= 28) {
            String name = Application.getProcessName();
            return name != null && name.endsWith(":nekocrash");
        }
        return false;
    }

    public static synchronized void install(Context base) {
        if (installed || isCrashProcess()) {
            return;
        }
        installed = true;
        final Context context = base;
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                String report = buildReport(context, thread, throwable);
                File file = new File(context.getFilesDir(), CRASH_FILE);
                try (FileOutputStream out = new FileOutputStream(file)) {
                    out.write(report.getBytes(StandardCharsets.UTF_8));
                }
                Intent intent = new Intent();
                intent.setClassName(context.getPackageName(), CrashReportActivity.class.getName());
                intent.putExtra("report", report);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                context.startActivity(intent);
                Process.killProcess(Process.myPid());
                System.exit(10);
            } catch (Throwable ignore) {
                if (previous != null) {
                    previous.uncaughtException(thread, throwable);
                }
            }
        });
    }

    private static String buildReport(Context context, Thread thread, Throwable throwable) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println("Neko Mod - reporte de error");
        pw.println("Fecha: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
        try {
            pw.println("Version: " + context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName);
        } catch (Throwable ignore) {
        }
        pw.println("Android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        pw.println("Equipo: " + Build.MANUFACTURER + " " + Build.MODEL);
        pw.println("Hilo: " + thread.getName());
        pw.println();
        throwable.printStackTrace(pw);
        pw.flush();
        return sw.toString();
    }
}
