package tw.nekomimi.nekogram.nekomod.history;

import androidx.collection.LongSparseArray;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesStorage;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import tw.nekomimi.nekogram.NekoModConfig;

// NekoMod: logica de enganche para MessagesController (borrados remotos conservados + historial de ediciones).
public class NekoHistoryHooks {

    // Borrados iniciados por el usuario desde esta app: "dialogId:msgId" -> timestamp ms.
    private static final ConcurrentHashMap<String, Long> ownDeletes = new ConcurrentHashMap<>();
    private static final long OWN_TTL = 10 * 60 * 1000L;

    private static String key(long dialogId, int msgId) {
        return dialogId + ":" + msgId;
    }

    /** Llamar desde MessagesController.deleteMessages (borrado local del usuario). */
    public static void markOwnDelete(long dialogId, ArrayList<Integer> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (ownDeletes.size() > 500) {
            Iterator<Map.Entry<String, Long>> it = ownDeletes.entrySet().iterator();
            while (it.hasNext()) {
                if (now - it.next().getValue() > OWN_TTL) {
                    it.remove();
                }
            }
        }
        for (int i = 0, n = ids.size(); i < n; i++) {
            Integer id = ids.get(i);
            if (id != null) {
                ownDeletes.put(key(dialogId, id), now);
                // el mismo id sin dialogo (borrado de chat privado llega con clave 0)
                ownDeletes.put(key(0, id), now);
            }
        }
    }

    private static boolean isOwn(long dialogId, int msgId) {
        long now = System.currentTimeMillis();
        Long t = ownDeletes.get(key(dialogId, msgId));
        if (t != null && now - t <= OWN_TTL) {
            return true;
        }
        return false;
    }

    /**
     * Quita de "deleted" los ids que se deben conservar (borrado remoto con la opcion activa) y los registra en
     * la base propia. Se llama sincronicamente en el hilo de updates, antes de borrar en storage / notificar.
     * Clave 0 = mensajes de chats privados/basicos (el dialogo se resuelve consultando messages_v2).
     */
    public static void filterRemoteDeletes(int account, LongSparseArray<ArrayList<Integer>> deleted, int date) {
        if (!NekoModConfig.saveDeletedMessages || deleted == null || deleted.size() == 0) {
            return;
        }
        try {
            for (int a = deleted.size() - 1; a >= 0; a--) {
                long key = deleted.keyAt(a);
                ArrayList<Integer> ids = deleted.valueAt(a);
                if (ids == null || ids.isEmpty()) {
                    continue;
                }
                // resolver (uid, mid) existentes en storage
                final ArrayList<long[]> found = queryExisting(account, key, ids);
                if (found == null || found.isEmpty()) {
                    continue;
                }
                ArrayList<Integer> remaining = new ArrayList<>(ids);
                for (int i = 0; i < found.size(); i++) {
                    long uid = found.get(i)[0];
                    int mid = (int) found.get(i)[1];
                    if (isOwn(uid, mid)) {
                        continue;
                    }
                    remaining.remove(Integer.valueOf(mid));
                    NekoHistoryDb.get().markDeleted(uid, mid, date != 0 ? date : (int) (System.currentTimeMillis() / 1000), null);
                }
                if (remaining.isEmpty()) {
                    deleted.removeAt(a);
                } else if (remaining.size() != ids.size()) {
                    deleted.setValueAt(a, remaining);
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static ArrayList<long[]> queryExisting(int account, long key, ArrayList<Integer> ids) {
        final MessagesStorage storage = MessagesStorage.getInstance(account);
        final ArrayList<long[]> result = new ArrayList<>();
        final CountDownLatch latch = new CountDownLatch(1);
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(ids.get(i).intValue());
        }
        final String sql = key != 0
                ? "SELECT uid, mid FROM messages_v2 WHERE mid IN(" + sb + ") AND uid = " + key
                : "SELECT uid, mid FROM messages_v2 WHERE mid IN(" + sb + ") AND is_channel = 0";
        storage.getStorageQueue().postRunnable(() -> {
            SQLiteCursor cursor = null;
            try {
                cursor = storage.getDatabase().queryFinalized(sql);
                while (cursor.next()) {
                    result.add(new long[]{cursor.longValue(0), cursor.intValue(1)});
                }
            } catch (Throwable e) {
                FileLog.e(e);
            } finally {
                if (cursor != null) {
                    cursor.dispose();
                }
                latch.countDown();
            }
        });
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) {
                return null;
            }
        } catch (InterruptedException e) {
            return null;
        }
        synchronized (result) {
            return result;
        }
    }

    /**
     * Llamar en el procesamiento de una edicion (antes de que storage aplique el mensaje nuevo). Lee el mensaje
     * anterior desde storage (en su cola, que corre antes de la escritura de la edicion) y guarda su texto.
     * message.dialog_id debe estar calculado.
     */
    public static void onEditUpdate(int account, TLRPC.Message message) {
        if (!NekoModConfig.saveEditHistory || message == null) {
            return;
        }
        try {
            final long dialogId = MessageObject.getDialogId(message);
            final int mid = message.id;
            final String newText = message.message != null ? message.message : "";
            final MessagesStorage storage = MessagesStorage.getInstance(account);
            storage.getStorageQueue().postRunnable(() -> {
                SQLiteCursor cursor = null;
                try {
                    cursor = storage.getDatabase().queryFinalized("SELECT data FROM messages_v2 WHERE uid = " + dialogId + " AND mid = " + mid + " LIMIT 1");
                    if (cursor.next()) {
                        NativeByteBuffer data = cursor.byteBufferValue(0);
                        if (data != null) {
                            TLRPC.Message old = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                            data.reuse();
                            if (old != null && old.message != null && !old.message.equals(newText)) {
                                int versionDate = old.edit_date != 0 ? old.edit_date : old.date;
                                NekoHistoryDb.get().addEditVersion(dialogId, mid, versionDate, old.message);
                            }
                        }
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                } finally {
                    if (cursor != null) {
                        cursor.dispose();
                    }
                }
            });
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }
}
