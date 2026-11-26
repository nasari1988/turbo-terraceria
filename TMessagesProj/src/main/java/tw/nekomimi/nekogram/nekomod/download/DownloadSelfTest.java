package tw.nekomimi.nekogram.nekomod.download;

import android.app.job.JobScheduler;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;

import org.telegram.messenger.UserConfig;

/** NekoMod: autodiagnóstico de la descarga masiva. */
public class DownloadSelfTest {

    public static String[] run(Context ctx) {
        StringBuilder detail = new StringBuilder();
        boolean fail = false;
        boolean warn = false;

        // 1) base SQLite: insertar, leer y borrar un item de prueba
        try {
            SQLiteDatabase db = BulkDownloadManager.get(UserConfig.selectedAccount).getDb();
            ContentValues v = new ContentValues();
            v.put("dialog_id", -999999L);
            v.put("msg_id", 1);
            v.put("file_name", "selftest.bin");
            v.put("size", 123L);
            v.put("state", BulkDownloadManager.STATE_PENDING);
            v.put("local_path", "/tmp/selftest");
            db.insertWithOnConflict("items", null, v, SQLiteDatabase.CONFLICT_REPLACE);
            boolean readOk = false;
            try (Cursor c = db.rawQuery("SELECT file_name, size FROM items WHERE dialog_id=? AND msg_id=?", new String[]{"-999999", "1"})) {
                readOk = c.moveToFirst() && "selftest.bin".equals(c.getString(0)) && c.getLong(1) == 123L;
            }
            int deleted = db.delete("items", "dialog_id=?", new String[]{"-999999"});
            if (readOk && deleted >= 1) {
                detail.append("Base SQLite: OK\n");
            } else {
                fail = true;
                detail.append("Base SQLite: FALLA (lectura=").append(readOk).append(", borrados=").append(deleted).append(")\n");
            }
        } catch (Throwable e) {
            fail = true;
            detail.append("Base SQLite: FALLA ").append(e).append('\n');
        }

        // 2) carpeta destino vía MediaStore (solo comprobación, sin crear archivos)
        try {
            if (ctx.getContentResolver() == null) {
                fail = true;
                detail.append("MediaStore: FALLA (sin ContentResolver)\n");
            } else if (Build.VERSION.SDK_INT >= 29) {
                detail.append("MediaStore: OK (Android ").append(Build.VERSION.SDK_INT).append(", Download/Neko Mod)\n");
            } else {
                warn = true;
                detail.append("MediaStore: AVISO (Android < 10: se copia con archivos directos y requiere permiso de almacenamiento)\n");
            }
        } catch (Throwable e) {
            fail = true;
            detail.append("MediaStore: FALLA ").append(e).append('\n');
        }

        // 3) JobScheduler
        try {
            JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js != null) {
                detail.append("JobScheduler: OK\n");
            } else {
                warn = true;
                detail.append("JobScheduler: AVISO (no disponible, la sincronización nocturna no funcionará)\n");
            }
        } catch (Throwable e) {
            warn = true;
            detail.append("JobScheduler: AVISO ").append(e).append('\n');
        }

        return new String[]{fail ? "FALLA" : (warn ? "AVISO" : "OK"), detail.toString().trim()};
    }
}
