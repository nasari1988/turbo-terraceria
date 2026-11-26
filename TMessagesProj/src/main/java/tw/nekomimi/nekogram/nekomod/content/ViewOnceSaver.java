package tw.nekomimi.nekogram.nekomod.content;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

// NekoMod: guarda en Pictures/Neko Mod o Movies/Neko Mod el medio de un solo uso al abrirlo (una vez por mensaje).
public class ViewOnceSaver {

    private static final String PREFS = "nekomod_content";
    private static final String DIR = "Neko Mod";

    public static void onOpened(final int account, final MessageObject messageObject) {
        if (!ContentGate.shouldSaveViewOnce() || messageObject == null || messageObject.messageOwner == null) {
            return;
        }
        final String key = "vo_" + account + "_" + messageObject.getDialogId() + "_" + messageObject.getId();
        final SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (prefs.getBoolean(key, false)) {
            return;
        }
        final TLRPC.Document document = messageObject.getDocument();
        final TLRPC.Message owner = messageObject.messageOwner;
        final boolean live = messageObject.isLivePhoto();
        new Thread(() -> {
            try {
                // El archivo puede terminar de descargarse despues de abrir el visor: reintenta hasta 30 s.
                for (int i = 0; i < 30; i++) {
                    File file = null;
                    if (owner.attachPath != null && owner.attachPath.length() > 0) {
                        File f = new File(owner.attachPath);
                        if (f.exists()) {
                            file = f;
                        }
                    }
                    if (file == null) {
                        File f = live && document != null
                                ? FileLoader.getInstance(account).getPathToAttach(document, null, true)
                                : FileLoader.getInstance(account).getPathToMessage(owner);
                        if (f != null && f.exists() && f.length() > 0) {
                            file = f;
                        }
                    }
                    if (file != null) {
                        if (save(file, document != null)) {
                            prefs.edit().putBoolean(key, true).apply();
                            AndroidUtilities.runOnUIThread(() -> Toast.makeText(ApplicationLoader.applicationContext,
                                    LocaleController.getString(R.string.NekoModContentSavedToGallery), Toast.LENGTH_SHORT).show());
                        }
                        return;
                    }
                    Thread.sleep(1000);
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }).start();
    }

    private static boolean save(File src, boolean isDocument) throws Exception {
        String ext = FileLoader.getFileExtension(src);
        String mime = ext != null ? MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase()) : null;
        boolean video = mime != null ? mime.startsWith("video") : isDocument;
        if (mime == null) {
            mime = video ? "video/mp4" : "image/jpeg";
        }
        String name = AndroidUtilities.generateFileName(video ? 1 : 0, ext);
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, (video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES) + File.separator + DIR + File.separator);
            android.net.Uri collection = video
                    ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    : MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            android.net.Uri dst = ApplicationLoader.applicationContext.getContentResolver().insert(collection, cv);
            if (dst == null) {
                return false;
            }
            try (InputStream in = new FileInputStream(src); OutputStream out = ApplicationLoader.applicationContext.getContentResolver().openOutputStream(dst)) {
                if (out == null) {
                    return false;
                }
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
            return true;
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES), DIR);
            dir.mkdirs();
            File dst = new File(dir, name);
            try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
            AndroidUtilities.addMediaToGallery(dst);
            return true;
        }
    }
}
