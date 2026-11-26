package tw.nekomimi.nekogram.nekomod.history;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

// NekoMod: base propia de historial (mensajes borrados + versiones de ediciones).
public class NekoHistoryDb extends SQLiteOpenHelper {

    public static class EditVersion {
        public final int date;
        public final String text;

        public EditVersion(int date, String text) {
            this.date = date;
            this.text = text;
        }
    }

    private static volatile NekoHistoryDb instance;

    public static NekoHistoryDb get() {
        NekoHistoryDb local = instance;
        if (local == null) {
            synchronized (NekoHistoryDb.class) {
                local = instance;
                if (local == null) {
                    local = instance = new NekoHistoryDb(ApplicationLoader.applicationContext);
                }
            }
        }
        return local;
    }

    // Caches en memoria (nunca SQL desde onDraw).
    private final ConcurrentHashMap<Long, ConcurrentHashMap<Integer, Long>> deletedCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, ConcurrentHashMap<Integer, Integer>> editCountCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Boolean> loadedDialogs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Runnable> listeners = new ConcurrentHashMap<>();

    private NekoHistoryDb(Context context) {
        super(context, "nekomod_history.db", null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS deleted(dialog_id INTEGER, msg_id INTEGER, date INTEGER, data BLOB, PRIMARY KEY(dialog_id, msg_id))");
        db.execSQL("CREATE TABLE IF NOT EXISTS edits(dialog_id INTEGER, msg_id INTEGER, version_date INTEGER, text TEXT)");
        db.execSQL("CREATE INDEX IF NOT EXISTS edits_idx ON edits(dialog_id, msg_id)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    }

    // ---------------------------------------------------------------- listeners (hilo UI)

    public void setDialogListener(long dialogId, Runnable listener) {
        if (listener == null) {
            listeners.remove(dialogId);
        } else {
            listeners.put(dialogId, listener);
        }
    }

    private void notifyDialog(long dialogId) {
        Runnable r = listeners.get(dialogId);
        if (r != null) {
            AndroidUtilities.runOnUIThread(r);
        }
    }

    // ---------------------------------------------------------------- borrados

    /** Marca (cache inmediata + escritura asincrona). */
    public void markDeleted(long dialogId, int msgId, int date, byte[] data) {
        ConcurrentHashMap<Integer, Long> map = deletedCache.get(dialogId);
        if (map == null) {
            ConcurrentHashMap<Integer, Long> created = new ConcurrentHashMap<>();
            map = deletedCache.putIfAbsent(dialogId, created);
            if (map == null) {
                map = created;
            }
        }
        map.put(msgId, (long) date);
        notifyDialog(dialogId);
        final byte[] blob = data != null ? data : new byte[0];
        Utilities.globalQueue.postRunnable(() -> markDeletedSync(dialogId, msgId, date, blob));
    }

    public void markDeletedSync(long dialogId, int msgId, int date, byte[] data) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            ContentValues cv = new ContentValues();
            cv.put("dialog_id", dialogId);
            cv.put("msg_id", msgId);
            cv.put("date", date);
            cv.put("data", data != null ? data : new byte[0]);
            db.insertWithOnConflict("deleted", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** Solo cache en memoria: seguro para llamar desde onDraw. Requiere loadDialog previo. */
    public boolean isDeleted(long dialogId, int msgId) {
        ConcurrentHashMap<Integer, Long> map = deletedCache.get(dialogId);
        return map != null && map.containsKey(msgId);
    }

    /** Fecha (unix seg) del borrado o 0. Solo cache. */
    public int getDeletedDate(long dialogId, int msgId) {
        ConcurrentHashMap<Integer, Long> map = deletedCache.get(dialogId);
        Long v = map != null ? map.get(msgId) : null;
        return v != null ? v.intValue() : 0;
    }

    /** Consulta directa a SQL (NO usar en hilo UI). */
    public int getDeletedDateSync(long dialogId, int msgId) {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery("SELECT date FROM deleted WHERE dialog_id = ? AND msg_id = ?",
                    new String[]{String.valueOf(dialogId), String.valueOf(msgId)});
            if (c.moveToFirst()) {
                return c.getInt(0);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return 0;
    }

    public void deleteDeletedSync(long dialogId, int msgId) {
        try {
            getWritableDatabase().delete("deleted", "dialog_id = ? AND msg_id = ?",
                    new String[]{String.valueOf(dialogId), String.valueOf(msgId)});
            ConcurrentHashMap<Integer, Long> map = deletedCache.get(dialogId);
            if (map != null) {
                map.remove(msgId);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    // ---------------------------------------------------------------- ediciones

    public void addEditVersion(long dialogId, int msgId, int versionDate, String text) {
        Utilities.globalQueue.postRunnable(() -> addEditVersionSync(dialogId, msgId, versionDate, text));
    }

    public void addEditVersionSync(long dialogId, int msgId, int versionDate, String text) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            String t = text != null ? text : "";
            Cursor c = null;
            try {
                c = db.rawQuery("SELECT COUNT(*) FROM edits WHERE dialog_id = ? AND msg_id = ? AND version_date = ? AND text = ?",
                        new String[]{String.valueOf(dialogId), String.valueOf(msgId), String.valueOf(versionDate), t});
                if (c.moveToFirst() && c.getInt(0) > 0) {
                    return; // duplicado
                }
            } finally {
                if (c != null) {
                    c.close();
                }
            }
            ContentValues cv = new ContentValues();
            cv.put("dialog_id", dialogId);
            cv.put("msg_id", msgId);
            cv.put("version_date", versionDate);
            cv.put("text", t);
            db.insert("edits", null, cv);

            ConcurrentHashMap<Integer, Integer> map = editCountCache.get(dialogId);
            if (map == null) {
                ConcurrentHashMap<Integer, Integer> created = new ConcurrentHashMap<>();
                map = editCountCache.putIfAbsent(dialogId, created);
                if (map == null) {
                    map = created;
                }
            }
            map.put(msgId, countEditVersions(dialogId, msgId));
            notifyDialog(dialogId);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** Versiones anteriores, la mas reciente primero (SQL: NO usar en hilo UI). */
    public List<EditVersion> getEditVersions(long dialogId, int msgId) {
        ArrayList<EditVersion> result = new ArrayList<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery("SELECT version_date, text FROM edits WHERE dialog_id = ? AND msg_id = ? ORDER BY version_date DESC, rowid DESC",
                    new String[]{String.valueOf(dialogId), String.valueOf(msgId)});
            while (c.moveToNext()) {
                result.add(new EditVersion(c.getInt(0), c.getString(1)));
            }
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return result;
    }

    /** SQL (NO usar en hilo UI). */
    public int countEditVersions(long dialogId, int msgId) {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM edits WHERE dialog_id = ? AND msg_id = ?",
                    new String[]{String.valueOf(dialogId), String.valueOf(msgId)});
            if (c.moveToFirst()) {
                return c.getInt(0);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return 0;
    }

    /** Solo cache (seguro en UI / onDraw / medicion). */
    public int getEditCountCached(long dialogId, int msgId) {
        ConcurrentHashMap<Integer, Integer> map = editCountCache.get(dialogId);
        Integer v = map != null ? map.get(msgId) : null;
        return v != null ? v : 0;
    }

    public void deleteEditVersionsSync(long dialogId, int msgId) {
        try {
            getWritableDatabase().delete("edits", "dialog_id = ? AND msg_id = ?",
                    new String[]{String.valueOf(dialogId), String.valueOf(msgId)});
            ConcurrentHashMap<Integer, Integer> map = editCountCache.get(dialogId);
            if (map != null) {
                map.remove(msgId);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    // ---------------------------------------------------------------- carga de caches

    public boolean isLoaded(long dialogId) {
        return loadedDialogs.containsKey(dialogId);
    }

    /** Carga en segundo plano las caches del dialogo; onLoaded (puede ser null) corre en el hilo UI. */
    public void loadDialog(long dialogId, Runnable onLoaded) {
        Utilities.globalQueue.postRunnable(() -> {
            loadDialogSync(dialogId);
            if (onLoaded != null) {
                AndroidUtilities.runOnUIThread(onLoaded);
            }
        });
    }

    public void loadDialogSync(long dialogId) {
        Cursor c = null;
        try {
            SQLiteDatabase db = getReadableDatabase();
            ConcurrentHashMap<Integer, Long> del = new ConcurrentHashMap<>();
            c = db.rawQuery("SELECT msg_id, date FROM deleted WHERE dialog_id = ?", new String[]{String.valueOf(dialogId)});
            while (c.moveToNext()) {
                del.put(c.getInt(0), c.getLong(1));
            }
            c.close();
            c = null;
            ConcurrentHashMap<Integer, Integer> cnt = new ConcurrentHashMap<>();
            c = db.rawQuery("SELECT msg_id, COUNT(*) FROM edits WHERE dialog_id = ? GROUP BY msg_id", new String[]{String.valueOf(dialogId)});
            while (c.moveToNext()) {
                cnt.put(c.getInt(0), c.getInt(1));
            }
            // fusiona con lo que haya entrado en memoria durante la carga
            ConcurrentHashMap<Integer, Long> old = deletedCache.get(dialogId);
            if (old != null) {
                del.putAll(old);
            }
            deletedCache.put(dialogId, del);
            ConcurrentHashMap<Integer, Integer> oldCnt = editCountCache.get(dialogId);
            if (oldCnt != null) {
                cnt.putAll(oldCnt);
            }
            editCountCache.put(dialogId, cnt);
            loadedDialogs.put(dialogId, Boolean.TRUE);
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }
}
