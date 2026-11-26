package tw.nekomimi.nekogram.nekomod.download;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;

import androidx.core.app.NotificationCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import tw.nekomimi.nekogram.NekoModConfig;

/**
 * NekoMod: motor de descarga masiva (uno por cuenta). Cola persistente en SQLite propio,
 * descarga con el FileLoader de Telegram y copia cada archivo a Descargas/Neko Mod/chat.
 * Todo el estado del motor se toca solo desde el hilo UI; la base y las copias van en dbQueue.
 */
public class BulkDownloadManager implements NotificationCenter.NotificationCenterDelegate {

    public static final int T_VIDEO = 1;
    public static final int T_PHOTO = 2;
    public static final int T_GIF = 4;
    public static final int T_AUDIO = 8;
    public static final int T_DOC = 16;
    public static final int T_MUSIC = 32;
    public static final int ALL_TYPES = 63;
    public static final int[] TYPE_LIST = {T_VIDEO, T_PHOTO, T_GIF, T_AUDIO, T_DOC, T_MUSIC};

    public static final int STATE_PENDING = 0;
    public static final int STATE_DONE = 1;
    public static final int STATE_ERROR = 2;
    public static final int STATE_SKIPPED = 3;

    private static final int PARALLEL = 2;
    private static final int FETCH_BATCH = 40;
    private static final String CHANNEL_ID = "nekomod_downloads";
    private static final int NOTIF_BASE = 0x4E440000;

    // ------------------------------------------------------------------ tipos públicos

    public static class Entry {
        public int msgId;
        public int date;
        public int type;
        public long size;
        public String name;
    }

    public interface ScanCallback {
        void onProgress(int found);

        void onDone(ArrayList<Entry> entries, boolean error);
    }

    public static class ScanTask {
        volatile boolean cancelled;
        int requestId;
        int account;

        public void cancel() {
            cancelled = true;
            if (requestId != 0) {
                ConnectionsManager.getInstance(account).cancelRequest(requestId, true);
            }
        }
    }

    public interface Listener {
        void onJobChanged(long dialogId);
    }

    public interface ValueCallback<T> {
        void run(T value);
    }

    public static class Job {
        public final long dialogId;
        public boolean running;
        public boolean paused;
        public boolean waitingWifi;
        public boolean fetchError;
        public boolean finished;
        public int total;
        public int done;
        public int failed;
        boolean wifiOnly;
        boolean fetching;
        int copying;
        String folder;
        Runnable onFinish;
        long lastNotify;
        final ArrayList<Integer> queue = new ArrayList<>();
        final ArrayList<TLRPC.Message> ready = new ArrayList<>();
        final ArrayList<Active> active = new ArrayList<>();

        Job(long dialogId) {
            this.dialogId = dialogId;
        }
    }

    private static class Active {
        Job job;
        TLRPC.Message msg;
        TLRPC.Document doc;
        TLRPC.Photo photo;
        TLRPC.PhotoSize photoSize;
        String fileName;
        String displayName;
    }

    // ------------------------------------------------------------------ singleton

    private static final BulkDownloadManager[] instances = new BulkDownloadManager[UserConfig.MAX_ACCOUNT_COUNT];

    public static BulkDownloadManager get(int account) {
        BulkDownloadManager local = instances[account];
        if (local == null) {
            synchronized (BulkDownloadManager.class) {
                local = instances[account];
                if (local == null) {
                    local = instances[account] = new BulkDownloadManager(account);
                }
            }
        }
        return local;
    }

    private final int account;
    private final Context context;
    private final DbHelper dbHelper;
    private final DispatchQueue dbQueue = new DispatchQueue("nekoBulkDb");
    private final HashMap<Long, Job> jobs = new HashMap<>();
    private final HashMap<String, ArrayList<Active>> activeFiles = new HashMap<>();
    private final ArrayList<Listener> listeners = new ArrayList<>();
    private boolean observersAdded;

    private BulkDownloadManager(int account) {
        this.account = account;
        this.context = ApplicationLoader.applicationContext;
        this.dbHelper = new DbHelper(context, account == 0 ? "nekomod_downloads.db" : "nekomod_downloads_" + account + ".db");
    }

    private static class DbHelper extends SQLiteOpenHelper {
        DbHelper(Context context, String name) {
            super(context, name, null, 1);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS items(dialog_id INTEGER, msg_id INTEGER, file_name TEXT, size INTEGER, state INTEGER, local_path TEXT, PRIMARY KEY(dialog_id, msg_id))");
            db.execSQL("CREATE TABLE IF NOT EXISTS chats(dialog_id INTEGER PRIMARY KEY, last_msg_id INTEGER, last_sync INTEGER)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        }
    }

    /** Base abierta (para el autodiagnóstico). Llamar fuera del hilo UI si es posible. */
    public SQLiteDatabase getDb() {
        return dbHelper.getWritableDatabase();
    }

    public void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public Job getJob(long dialogId) {
        return jobs.get(dialogId);
    }

    private void notifyListeners(long dialogId) {
        for (int i = 0, n = listeners.size(); i < n; i++) {
            if (i < listeners.size()) listeners.get(i).onJobChanged(dialogId);
        }
    }

    // ------------------------------------------------------------------ preferencias por chat

    private static android.content.SharedPreferences typePrefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("nekomod_download", Context.MODE_PRIVATE);
    }

    public static int getTypes(long dialogId) {
        return typePrefs().getInt("types_" + dialogId, ALL_TYPES);
    }

    public static void setTypes(long dialogId, int mask) {
        typePrefs().edit().putInt("types_" + dialogId, mask).apply();
    }

    public static boolean getWifiOnly(long dialogId) {
        return typePrefs().getBoolean("wifi_" + dialogId, true);
    }

    public static void setWifiOnly(long dialogId, boolean value) {
        typePrefs().edit().putBoolean("wifi_" + dialogId, value).apply();
    }

    // ------------------------------------------------------------------ utilidades

    public static boolean isWifiOrEthernet(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            Network n = cm.getActiveNetwork();
            if (n == null) return false;
            NetworkCapabilities c = cm.getNetworkCapabilities(n);
            return c != null && (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    public String chatFolderName(long dialogId) {
        String name = null;
        try {
            name = MessagesController.getInstance(account).getFullName(dialogId);
        } catch (Throwable ignore) {
        }
        if (name == null || name.trim().isEmpty()) {
            name = "chat_" + Math.abs(dialogId);
        }
        return sanitize(name, 60);
    }

    public static String sanitize(String s, int max) {
        String r = s.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        while (r.startsWith(".")) r = r.substring(1);
        if (r.length() > max) r = r.substring(0, max).trim();
        if (r.isEmpty()) r = "chat";
        return r;
    }

    private static boolean localExists(Context ctx, String path) {
        if (path == null || path.isEmpty()) return false;
        try {
            if (path.startsWith("content://")) {
                try (ParcelFileDescriptor pfd = ctx.getContentResolver().openFileDescriptor(Uri.parse(path), "r")) {
                    return pfd != null;
                }
            }
            return new File(path).exists();
        } catch (Throwable e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ escaneo con TL_messages_search

    private static TLRPC.MessagesFilter newFilter(int type) {
        switch (type) {
            case T_VIDEO:
                return new TLRPC.TL_inputMessagesFilterVideo();
            case T_PHOTO:
                return new TLRPC.TL_inputMessagesFilterPhotos();
            case T_GIF:
                return new TLRPC.TL_inputMessagesFilterGif();
            case T_AUDIO:
                return new TLRPC.TL_inputMessagesFilterRoundVoice();
            case T_DOC:
                return new TLRPC.TL_inputMessagesFilterDocument();
            default:
                return new TLRPC.TL_inputMessagesFilterMusic();
        }
    }

    private static TLRPC.PhotoSize bestPhotoSize(TLRPC.Photo photo) {
        if (photo == null || photo.sizes == null) return null;
        return FileLoader.getClosestPhotoSizeWithSize(photo.sizes, 2560);
    }

    private static Entry makeEntry(TLRPC.Message m, int type) {
        if (m == null || m.media == null) return null;
        TLRPC.MessageMedia media = MessageObject.getMedia(m);
        Entry e = new Entry();
        e.msgId = m.id;
        e.date = m.date;
        e.type = type;
        if (media instanceof TLRPC.TL_messageMediaPhoto) {
            TLRPC.Photo photo = ((TLRPC.TL_messageMediaPhoto) media).photo;
            if (photo == null || photo instanceof TLRPC.TL_photoEmpty) return null;
            TLRPC.PhotoSize ps = bestPhotoSize(photo);
            if (ps == null) return null;
            e.size = ps.size;
            e.name = "photo_" + m.id + ".jpg";
            return e;
        }
        TLRPC.Document doc = MessageObject.getDocument(m);
        if (doc == null || doc instanceof TLRPC.TL_documentEmpty) return null;
        e.size = doc.size;
        String n = FileLoader.getDocumentFileName(doc);
        e.name = n == null || n.isEmpty() ? "file_" + m.id : n;
        return e;
    }

    /**
     * Recorre el chat con TL_messages_search (un filtro por tipo, paginando con offset_id).
     * minId > 0 devuelve solo mensajes con id mayor (modo "Actualizar"). Callbacks en hilo UI.
     */
    public ScanTask scan(long dialogId, int typeMask, int minId, ScanCallback cb) {
        final ScanTask task = new ScanTask();
        task.account = account;
        final ArrayList<Entry> result = new ArrayList<>();
        final java.util.HashSet<Integer> seen = new java.util.HashSet<>();
        final ArrayList<Integer> types = new ArrayList<>();
        for (int t : TYPE_LIST) {
            if ((typeMask & t) != 0) types.add(t);
        }
        final TLRPC.InputPeer peer = MessagesController.getInstance(account).getInputPeer(dialogId);
        if (peer == null || types.isEmpty()) {
            AndroidUtilities.runOnUIThread(() -> cb.onDone(result, peer == null));
            return task;
        }
        scanStep(task, peer, types, 0, 0, minId, result, seen, cb);
        return task;
    }

    private void scanStep(ScanTask task, TLRPC.InputPeer peer, ArrayList<Integer> types, int typeIndex, int offsetId, int minId,
                          ArrayList<Entry> result, java.util.HashSet<Integer> seen, ScanCallback cb) {
        if (task.cancelled) return;
        if (typeIndex >= types.size()) {
            cb.onDone(result, false);
            return;
        }
        final int type = types.get(typeIndex);
        TLRPC.TL_messages_search req = new TLRPC.TL_messages_search();
        req.peer = peer;
        req.q = "";
        req.filter = newFilter(type);
        req.limit = 100;
        req.offset_id = offsetId;
        req.min_id = minId;
        task.requestId = ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (task.cancelled) return;
            if (!(response instanceof TLRPC.messages_Messages) || error != null) {
                cb.onDone(result, true);
                return;
            }
            TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
            int minSeen = Integer.MAX_VALUE;
            for (int i = 0; i < res.messages.size(); i++) {
                TLRPC.Message m = res.messages.get(i);
                if (m.id < minSeen) minSeen = m.id;
                if (m.id <= minId || !seen.add(m.id)) continue;
                Entry e = makeEntry(m, type);
                if (e != null) result.add(e);
            }
            cb.onProgress(result.size());
            if (res.messages.size() < 100 || minSeen == Integer.MAX_VALUE || (offsetId != 0 && minSeen >= offsetId)) {
                scanStep(task, peer, types, typeIndex + 1, 0, minId, result, seen, cb);
            } else {
                scanStep(task, peer, types, typeIndex, minSeen, minId, result, seen, cb);
            }
        }));
    }

    // ------------------------------------------------------------------ consultas a la base

    /** Quita de la lista los ya descargados (regla "saltar"); callback en hilo UI. */
    public void filterPending(long dialogId, List<Entry> entries, ValueCallback<ArrayList<Entry>> cb) {
        final ArrayList<Entry> copy = new ArrayList<>(entries);
        dbQueue.postRunnable(() -> {
            ArrayList<Entry> out = new ArrayList<>();
            try {
                out = filterPendingSync(dialogId, copy);
            } catch (Throwable e) {
                FileLog.e(e);
                out = copy;
            }
            final ArrayList<Entry> fin = out;
            AndroidUtilities.runOnUIThread(() -> cb.run(fin));
        });
    }

    private ArrayList<Entry> filterPendingSync(long dialogId, List<Entry> entries) {
        HashMap<Integer, String> done = new HashMap<>();
        try (Cursor c = getDb().rawQuery("SELECT msg_id, local_path FROM items WHERE dialog_id=? AND state=1", new String[]{String.valueOf(dialogId)})) {
            while (c.moveToNext()) {
                done.put(c.getInt(0), c.getString(1));
            }
        }
        ArrayList<Entry> out = new ArrayList<>();
        for (Entry e : entries) {
            if (done.containsKey(e.msgId)) {
                String p = done.get(e.msgId);
                if (!NekoModConfig.redownloadDeleted || localExists(context, p)) continue;
            }
            out.add(e);
        }
        return out;
    }

    /** lastMsgId y lastSync (ms) del chat, o -1/-1 si nunca se descargó. Hilo UI. */
    public void getChatInfo(long dialogId, ValueCallback<long[]> cb) {
        dbQueue.postRunnable(() -> {
            long[] r = {-1, -1};
            try (Cursor c = getDb().rawQuery("SELECT last_msg_id, last_sync FROM chats WHERE dialog_id=?", new String[]{String.valueOf(dialogId)})) {
                if (c.moveToFirst()) {
                    r[0] = c.getLong(0);
                    r[1] = c.getLong(1);
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
            AndroidUtilities.runOnUIThread(() -> cb.run(r));
        });
    }

    public void getPendingCount(long dialogId, ValueCallback<Integer> cb) {
        dbQueue.postRunnable(() -> {
            int n = 0;
            try (Cursor c = getDb().rawQuery("SELECT COUNT(*) FROM items WHERE dialog_id=? AND state=0", new String[]{String.valueOf(dialogId)})) {
                if (c.moveToFirst()) n = c.getInt(0);
            } catch (Throwable e) {
                FileLog.e(e);
            }
            final int fin = n;
            AndroidUtilities.runOnUIThread(() -> cb.run(fin));
        });
    }

    /** Mapa dialogId -> last_sync (ms) de todos los chats con registro. Hilo UI. */
    public void getAllSyncTimes(ValueCallback<HashMap<Long, Long>> cb) {
        dbQueue.postRunnable(() -> {
            HashMap<Long, Long> map = new HashMap<>();
            try (Cursor c = getDb().rawQuery("SELECT dialog_id, last_sync FROM chats", null)) {
                while (c.moveToNext()) {
                    map.put(c.getLong(0), c.getLong(1));
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
            AndroidUtilities.runOnUIThread(() -> cb.run(map));
        });
    }

    /**
     * Guarda en la cola lo que falte (aplica "saltar descargados") y actualiza last_msg_id con el id
     * más alto visto. Devuelve cuántos quedaron pendientes. Hilo UI.
     */
    public void enqueue(long dialogId, List<Entry> selected, ValueCallback<Integer> cb) {
        final ArrayList<Entry> copy = new ArrayList<>(selected);
        dbQueue.postRunnable(() -> {
            int pendingCount = 0;
            try {
                SQLiteDatabase db = getDb();
                ArrayList<Entry> pending = filterPendingSync(dialogId, copy);
                int maxId = 0;
                for (Entry e : copy) maxId = Math.max(maxId, e.msgId);
                db.beginTransaction();
                try {
                    for (Entry e : pending) {
                        ContentValues v = new ContentValues();
                        v.put("dialog_id", dialogId);
                        v.put("msg_id", e.msgId);
                        v.put("file_name", e.name);
                        v.put("size", e.size);
                        v.put("state", STATE_PENDING);
                        v.putNull("local_path");
                        db.insertWithOnConflict("items", null, v, SQLiteDatabase.CONFLICT_REPLACE);
                    }
                    long oldLast = 0;
                    boolean exists = false;
                    try (Cursor c = db.rawQuery("SELECT last_msg_id FROM chats WHERE dialog_id=?", new String[]{String.valueOf(dialogId)})) {
                        if (c.moveToFirst()) {
                            exists = true;
                            oldLast = c.getLong(0);
                        }
                    }
                    ContentValues cv = new ContentValues();
                    cv.put("last_msg_id", Math.max(oldLast, maxId));
                    if (exists) {
                        db.update("chats", cv, "dialog_id=?", new String[]{String.valueOf(dialogId)});
                    } else {
                        cv.put("dialog_id", dialogId);
                        cv.put("last_sync", 0);
                        db.insert("chats", null, cv);
                    }
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
                try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM items WHERE dialog_id=? AND state=0", new String[]{String.valueOf(dialogId)})) {
                    if (c.moveToFirst()) pendingCount = c.getInt(0);
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
            final int fin = pendingCount;
            AndroidUtilities.runOnUIThread(() -> cb.run(fin));
        });
    }

    private void dbExec(String sql, Object... args) {
        dbQueue.postRunnable(() -> {
            try {
                String[] a = new String[args.length];
                for (int i = 0; i < args.length; i++) a[i] = String.valueOf(args[i]);
                getDb().execSQL(sql, a);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    private void setItemState(long dialogId, int msgId, int state, String localPath) {
        dbQueue.postRunnable(() -> {
            try {
                ContentValues v = new ContentValues();
                v.put("state", state);
                if (localPath != null) v.put("local_path", localPath);
                getDb().update("items", v, "dialog_id=? AND msg_id=?", new String[]{String.valueOf(dialogId), String.valueOf(msgId)});
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    // ------------------------------------------------------------------ control del trabajo (hilo UI)

    private void ensureObservers() {
        if (observersAdded) return;
        observersAdded = true;
        NotificationCenter nc = NotificationCenter.getInstance(account);
        nc.addObserver(this, NotificationCenter.fileLoaded);
        nc.addObserver(this, NotificationCenter.fileLoadFailed);
    }

    /** Arranca (o reanuda tras reinicio) la cola pendiente del chat. Hilo UI. */
    public void start(long dialogId, boolean wifiOnly, Runnable onFinish) {
        ensureObservers();
        Job existing = jobs.get(dialogId);
        if (existing != null && existing.running) {
            if (onFinish != null) existing.onFinish = onFinish;
            if (existing.paused) resume(dialogId);
            return;
        }
        final Job job = new Job(dialogId);
        job.running = true;
        job.wifiOnly = wifiOnly;
        job.onFinish = onFinish;
        job.folder = chatFolderName(dialogId);
        jobs.put(dialogId, job);
        dbQueue.postRunnable(() -> {
            final ArrayList<Integer> ids = new ArrayList<>();
            try (Cursor c = getDb().rawQuery("SELECT msg_id FROM items WHERE dialog_id=? AND state=0 ORDER BY msg_id DESC", new String[]{String.valueOf(dialogId)})) {
                while (c.moveToNext()) ids.add(c.getInt(0));
            } catch (Throwable e) {
                FileLog.e(e);
            }
            AndroidUtilities.runOnUIThread(() -> {
                job.queue.addAll(ids);
                job.total = ids.size();
                notifyJob(job, false);
                pump(job);
            });
        });
    }

    public void pause(long dialogId) {
        Job job = jobs.get(dialogId);
        if (job != null) pauseInternal(job, false);
    }

    private void pauseInternal(Job job, boolean waitingWifi) {
        if (!job.running) return;
        job.paused = true;
        job.waitingWifi = waitingWifi;
        FileLoader fl = FileLoader.getInstance(account);
        // los que se estaban bajando vuelven al frente de la cola; Telegram conserva el parcial
        for (int i = job.active.size() - 1; i >= 0; i--) {
            Active a = job.active.get(i);
            removeActiveFile(a);
            job.ready.add(0, a.msg);
            try {
                if (a.photoSize != null) {
                    fl.cancelLoadFile(a.photoSize);
                } else if (a.doc != null) {
                    fl.cancelLoadFile(a.doc);
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        job.active.clear();
        notifyJob(job, false);
    }

    public void resume(long dialogId) {
        Job job = jobs.get(dialogId);
        if (job == null || !job.running) return;
        job.paused = false;
        job.waitingWifi = false;
        job.fetchError = false;
        notifyJob(job, false);
        pump(job);
    }

    /** Cancela: detiene y borra lo pendiente de la cola (lo ya descargado se conserva). */
    public void cancel(long dialogId) {
        Job job = jobs.get(dialogId);
        if (job != null) {
            pauseInternal(job, false);
            job.running = false;
            job.finished = true;
            jobs.remove(dialogId);
            cancelNotification(dialogId);
        }
        dbExec("DELETE FROM items WHERE dialog_id=? AND state=0", dialogId);
        notifyListeners(dialogId);
    }

    private void removeActiveFile(Active a) {
        ArrayList<Active> list = activeFiles.get(a.fileName);
        if (list != null) {
            list.remove(a);
            if (list.isEmpty()) activeFiles.remove(a.fileName);
        }
    }

    private void pump(Job job) {
        if (!job.running || job.paused) return;
        if (job.wifiOnly && !isWifiOrEthernet(context)) {
            pauseInternal(job, true);
            return;
        }
        while (job.active.size() < PARALLEL && !job.ready.isEmpty()) {
            startOne(job, job.ready.remove(0));
            if (job.paused) return;
        }
        if (job.ready.isEmpty() && !job.queue.isEmpty() && !job.fetching) {
            fetchNext(job);
        }
        if (job.queue.isEmpty() && job.ready.isEmpty() && !job.fetching && job.active.isEmpty() && job.copying == 0) {
            finish(job);
        }
    }

    private void fetchNext(Job job) {
        final ArrayList<Integer> ids = new ArrayList<>();
        while (ids.size() < FETCH_BATCH && !job.queue.isEmpty()) {
            ids.add(job.queue.remove(0));
        }
        if (ids.isEmpty()) return;
        job.fetching = true;
        TLObject req;
        TLRPC.Chat chat = DialogObject.isChatDialog(job.dialogId) ? MessagesController.getInstance(account).getChat(-job.dialogId) : null;
        if (ChatObject.isChannel(chat)) {
            TLRPC.TL_channels_getMessages r = new TLRPC.TL_channels_getMessages();
            r.channel = MessagesController.getInputChannel(chat);
            r.id.addAll(ids);
            req = r;
        } else {
            TLRPC.TL_messages_getMessages r = new TLRPC.TL_messages_getMessages();
            r.id.addAll(ids);
            req = r;
        }
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            job.fetching = false;
            if (!job.running) return;
            if (!(response instanceof TLRPC.messages_Messages) || error != null) {
                job.queue.addAll(0, ids);
                job.fetchError = true;
                pauseInternal(job, false);
                return;
            }
            TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
            HashMap<Integer, TLRPC.Message> byId = new HashMap<>();
            for (TLRPC.Message m : res.messages) byId.put(m.id, m);
            for (Integer id : ids) {
                TLRPC.Message m = byId.get(id);
                if (m != null && makeEntry(m, T_DOC) != null) {
                    job.ready.add(m);
                } else {
                    // mensaje borrado o sin archivo: se omite
                    setItemState(job.dialogId, id, STATE_SKIPPED, null);
                    job.done++;
                }
            }
            notifyJob(job, false);
            pump(job);
        }));
    }

    private File pathFor(Active a) {
        FileLoader fl = FileLoader.getInstance(account);
        TLObject o = a.photoSize != null ? a.photoSize : a.doc;
        File f = fl.getPathToAttach(o);
        if (f != null && f.exists()) return f;
        f = fl.getPathToAttach(o, true);
        if (f != null && f.exists()) return f;
        return null;
    }

    private void startOne(Job job, TLRPC.Message msg) {
        Active a = new Active();
        a.job = job;
        a.msg = msg;
        TLRPC.MessageMedia media = MessageObject.getMedia(msg);
        if (media instanceof TLRPC.TL_messageMediaPhoto) {
            a.photo = ((TLRPC.TL_messageMediaPhoto) media).photo;
            a.photoSize = bestPhotoSize(a.photo);
            a.fileName = a.photoSize != null ? FileLoader.getAttachFileName(a.photoSize) : null;
            a.displayName = "photo_" + msg.id + ".jpg";
        } else {
            a.doc = MessageObject.getDocument(msg);
            a.fileName = a.doc != null ? FileLoader.getAttachFileName(a.doc) : null;
            String n = a.doc != null ? FileLoader.getDocumentFileName(a.doc) : null;
            a.displayName = n == null || n.isEmpty() ? null : n;
        }
        if (a.fileName == null) {
            setItemState(job.dialogId, msg.id, STATE_ERROR, null);
            job.done++;
            job.failed++;
            notifyJob(job, false);
            return;
        }
        job.active.add(a);
        File existing = pathFor(a);
        if (existing != null) {
            job.active.remove(a);
            onFileReady(job, a, existing);
            return;
        }
        ArrayList<Active> list = activeFiles.get(a.fileName);
        boolean alreadyLoading = list != null && !list.isEmpty();
        if (list == null) {
            list = new ArrayList<>();
            activeFiles.put(a.fileName, list);
        }
        list.add(a);
        if (alreadyLoading) return;
        try {
            MessageObject parent = new MessageObject(account, msg, false, false);
            if (a.photoSize != null) {
                FileLoader.getInstance(account).loadFile(ImageLocation.getForPhoto(a.photoSize, a.photo), parent, null, FileLoader.PRIORITY_NORMAL, 0);
            } else {
                FileLoader.getInstance(account).loadFile(a.doc, parent, FileLoader.PRIORITY_NORMAL, 0);
            }
        } catch (Throwable e) {
            FileLog.e(e);
            removeActiveFile(a);
            job.active.remove(a);
            setItemState(job.dialogId, msg.id, STATE_ERROR, null);
            job.done++;
            job.failed++;
        }
    }

    @Override
    public void didReceivedNotification(int id, int acc, Object... args) {
        if (acc != account) return;
        if (id == NotificationCenter.fileLoaded) {
            if (args.length < 1 || !(args[0] instanceof String)) return;
            String fileName = (String) args[0];
            ArrayList<Active> list = activeFiles.remove(fileName);
            if (list == null) return;
            File f = args.length > 1 && args[1] instanceof File ? (File) args[1] : null;
            for (Active a : new ArrayList<>(list)) {
                a.job.active.remove(a);
                File use = f != null && f.exists() ? f : pathFor(a);
                if (use == null) {
                    failActive(a);
                } else {
                    onFileReady(a.job, a, use);
                }
            }
        } else if (id == NotificationCenter.fileLoadFailed) {
            if (args.length < 2 || !(args[0] instanceof String)) return;
            if (args[1] instanceof Integer && (Integer) args[1] == 1) return; // cancelado por nosotros
            ArrayList<Active> list = activeFiles.remove((String) args[0]);
            if (list == null) return;
            for (Active a : new ArrayList<>(list)) {
                a.job.active.remove(a);
                failActive(a);
            }
        }
    }

    private void failActive(Active a) {
        Job job = a.job;
        setItemState(job.dialogId, a.msg.id, STATE_ERROR, null);
        job.done++;
        job.failed++;
        notifyJob(job, false);
        pump(job);
    }

    private void onFileReady(Job job, Active a, File src) {
        job.copying++;
        final int msgId = a.msg.id;
        final String display = a.displayName;
        final String folder = job.folder;
        dbQueue.postRunnable(() -> {
            String path = null;
            try {
                path = copyToPublic(src, folder, display, msgId);
            } catch (Throwable e) {
                FileLog.e(e);
            }
            final String fin = path;
            if (fin != null) {
                try {
                    ContentValues v = new ContentValues();
                    v.put("state", STATE_DONE);
                    v.put("local_path", fin);
                    getDb().update("items", v, "dialog_id=? AND msg_id=?", new String[]{String.valueOf(job.dialogId), String.valueOf(msgId)});
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            } else {
                try {
                    ContentValues v = new ContentValues();
                    v.put("state", STATE_ERROR);
                    getDb().update("items", v, "dialog_id=? AND msg_id=?", new String[]{String.valueOf(job.dialogId), String.valueOf(msgId)});
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
            AndroidUtilities.runOnUIThread(() -> {
                job.copying--;
                job.done++;
                if (fin == null) job.failed++;
                notifyJob(job, false);
                pump(job);
            });
        });
    }

    private void finish(Job job) {
        job.running = false;
        job.finished = true;
        jobs.remove(job.dialogId);
        dbQueue.postRunnable(() -> {
            try {
                ContentValues v = new ContentValues();
                v.put("last_sync", System.currentTimeMillis());
                int n = getDb().update("chats", v, "dialog_id=?", new String[]{String.valueOf(job.dialogId)});
                if (n == 0) {
                    v.put("dialog_id", job.dialogId);
                    v.put("last_msg_id", 0);
                    getDb().insert("chats", null, v);
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
            AndroidUtilities.runOnUIThread(() -> {
                notifyJob(job, true);
                if (job.onFinish != null) job.onFinish.run();
            });
        });
    }

    // ------------------------------------------------------------------ copia a carpeta pública

    private String copyToPublic(File src, String folder, String displayName, int msgId) throws Exception {
        String name = displayName;
        if (name == null || name.isEmpty()) name = src.getName();
        name = sanitize(name, 120);
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) ext = name.substring(dot + 1).toLowerCase();
        String mime = ext.isEmpty() ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        if (mime == null) mime = "application/octet-stream";
        if (Build.VERSION.SDK_INT >= 29) {
            android.content.ContentResolver cr = context.getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Neko Mod/" + folder);
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new java.io.IOException("MediaStore insert failed");
            try (InputStream in = new FileInputStream(src); OutputStream out = cr.openOutputStream(uri)) {
                if (out == null) throw new java.io.IOException("no output stream");
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } catch (Exception e) {
                try {
                    cr.delete(uri, null, null);
                } catch (Throwable ignore) {
                }
                throw e;
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(uri, done, null, null);
            return uri.toString();
        }
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Neko Mod/" + folder);
        if (!dir.exists() && !dir.mkdirs()) throw new java.io.IOException("mkdirs failed");
        File dest = new File(dir, name);
        if (dest.exists()) {
            String base = dot > 0 ? name.substring(0, dot) : name;
            dest = new File(dir, base + "_" + msgId + (dot > 0 ? name.substring(dot) : ""));
        }
        try (InputStream in = new FileInputStream(src); java.io.FileOutputStream out = new java.io.FileOutputStream(dest)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        return dest.getAbsolutePath();
    }

    // ------------------------------------------------------------------ notificación

    private void cancelNotification(long dialogId) {
        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(notifId(dialogId));
        } catch (Throwable ignore) {
        }
    }

    private static int notifId(long dialogId) {
        return NOTIF_BASE + (int) (Math.abs(dialogId) % 100000);
    }

    private void notifyJob(Job job, boolean last) {
        notifyListeners(job.dialogId);
        long now = System.currentTimeMillis();
        if (!last && now - job.lastNotify < 1000) return;
        job.lastNotify = now;
        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_ID, LocaleController.getString(R.string.NekoModDlNotifChannel), NotificationManager.IMPORTANCE_LOW);
                nm.createNotificationChannel(ch);
            }
            String text;
            if (last) {
                text = LocaleController.formatString(R.string.NekoModDlFinished, job.done - job.failed, job.total, job.failed);
            } else if (job.paused) {
                text = LocaleController.formatString(job.waitingWifi ? R.string.NekoModDlWaitWifi : R.string.NekoModDlPaused, job.done, job.total);
            } else {
                text = LocaleController.formatString(R.string.NekoModDlProgress, job.done, job.total);
            }
            NotificationCompat.Builder b = new NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.notification)
                    .setContentTitle(LocaleController.getString(R.string.NekoModDlNotifTitle) + " · " + job.folder)
                    .setContentText(text)
                    .setOnlyAlertOnce(true)
                    .setOngoing(!last)
                    .setAutoCancel(last);
            if (!last) {
                b.setProgress(Math.max(job.total, 1), Math.min(job.done, Math.max(job.total, 1)), false);
            }
            nm.notify(notifId(job.dialogId), b.build());
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    // ------------------------------------------------------------------ actualización incremental

    /**
     * Actualiza varios chats uno tras otro (solo lo nuevo, con los tipos guardados de cada chat).
     * onAllDone se ejecuta en hilo UI al terminar o si algo falla.
     */
    public void updateDialogs(List<Long> dialogIds, boolean wifiOnly, Runnable onAllDone) {
        final ArrayList<Long> queue = new ArrayList<>(dialogIds);
        updateNext(queue, wifiOnly, onAllDone);
    }

    private void updateNext(ArrayList<Long> queue, boolean wifiOnly, Runnable onAllDone) {
        if (queue.isEmpty()) {
            if (onAllDone != null) onAllDone.run();
            return;
        }
        final long dialogId = queue.remove(0);
        final Runnable next = () -> updateNext(queue, wifiOnly, onAllDone);
        getChatInfo(dialogId, info -> {
            final int minId = (int) Math.max(info[0], 0);
            scan(dialogId, getTypes(dialogId), minId, new ScanCallback() {
                @Override
                public void onProgress(int found) {
                }

                @Override
                public void onDone(ArrayList<Entry> entries, boolean error) {
                    if (error) {
                        next.run();
                        return;
                    }
                    enqueue(dialogId, entries, pending -> {
                        getPendingCount(dialogId, total -> {
                            if (total > 0) {
                                start(dialogId, wifiOnly, next);
                            } else {
                                // sin nada pendiente: solo se anota la fecha de sincronización
                                dbExec("UPDATE chats SET last_sync=? WHERE dialog_id=?", System.currentTimeMillis(), dialogId);
                                next.run();
                            }
                        });
                    });
                }
            });
        });
    }
}
