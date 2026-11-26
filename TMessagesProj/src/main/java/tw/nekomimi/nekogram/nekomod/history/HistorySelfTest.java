package tw.nekomimi.nekogram.nekomod.history;

import android.content.Context;

import java.util.List;

// NekoMod: prueba de verdad de la base de historial (escribe, lee y borra filas con dialog_id=-999999).
public class HistorySelfTest {

    public static String[] run(Context ctx) {
        final long did = -999999L;
        final int mid = 424242;
        NekoHistoryDb db = null;
        try {
            db = NekoHistoryDb.get();
            // borrados
            db.markDeletedSync(did, mid, 1234567, new byte[]{1, 2, 3});
            int d = db.getDeletedDateSync(did, mid);
            if (d != 1234567) {
                return new String[]{"FALLA", "getDeletedDateSync devolvio " + d + " (esperado 1234567)"};
            }
            // ediciones
            db.addEditVersionSync(did, mid, 100, "uno");
            db.addEditVersionSync(did, mid, 200, "dos");
            db.addEditVersionSync(did, mid, 200, "dos"); // duplicado, debe ignorarse
            int count = db.countEditVersions(did, mid);
            if (count != 2) {
                return new String[]{"FALLA", "countEditVersions=" + count + " (esperado 2)"};
            }
            List<NekoHistoryDb.EditVersion> list = db.getEditVersions(did, mid);
            if (list.size() != 2 || list.get(0).date != 200 || !"dos".equals(list.get(0).text) || !"uno".equals(list.get(1).text)) {
                return new String[]{"FALLA", "getEditVersions orden/contenido incorrecto (size=" + list.size() + ")"};
            }
            // cache
            db.loadDialogSync(did);
            if (!db.isDeleted(did, mid) || db.getEditCountCached(did, mid) != 2) {
                return new String[]{"FALLA", "cache en memoria incorrecta tras loadDialogSync"};
            }
            // limpieza
            db.deleteDeletedSync(did, mid);
            db.deleteEditVersionsSync(did, mid);
            if (db.getDeletedDateSync(did, mid) != 0 || db.countEditVersions(did, mid) != 0) {
                return new String[]{"FALLA", "las filas de prueba no se borraron"};
            }
            return new String[]{"OK", "deleted + edits: escritura, lectura, orden, duplicados, cache y borrado correctos"};
        } catch (Throwable e) {
            try {
                if (db != null) {
                    db.deleteDeletedSync(did, mid);
                    db.deleteEditVersionsSync(did, mid);
                }
            } catch (Throwable ignore) {
            }
            return new String[]{"FALLA", e.toString()};
        }
    }
}
