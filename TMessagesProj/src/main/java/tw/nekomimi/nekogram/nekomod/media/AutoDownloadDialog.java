package tw.nekomimi.nekogram.nekomod.media;

import android.content.Context;
import android.widget.LinearLayout;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Cells.CheckBoxCell;
import org.telegram.ui.Components.LayoutHelper;

/** NekoMod: dialogo de descarga automatica por chat (Fotos / Videos / Archivos o configuracion global). */
public final class AutoDownloadDialog {

    private AutoDownloadDialog() {
    }

    private static CheckBoxCell makeCell(Context ctx, String text, boolean checked) {
        CheckBoxCell cell = new CheckBoxCell(ctx, 1);
        cell.setText(text, "", checked, false);
        cell.setOnClickListener(v -> {
            CheckBoxCell c = (CheckBoxCell) v;
            c.setChecked(!c.isChecked(), true);
        });
        return cell;
    }

    public static void show(BaseFragment fragment, long dialogId) {
        final Context ctx = fragment.getParentActivity();
        if (ctx == null) return;
        final int current = AutoDownloadRules.maskFor(dialogId);
        final CheckBoxCell photos = makeCell(ctx, LocaleController.getString(R.string.NekoModAutoDlPhotos), current >= 0 && (current & AutoDownloadRules.PHOTO) != 0);
        final CheckBoxCell videos = makeCell(ctx, LocaleController.getString(R.string.NekoModAutoDlVideos), current >= 0 && (current & AutoDownloadRules.VIDEO) != 0);
        final CheckBoxCell files = makeCell(ctx, LocaleController.getString(R.string.NekoModAutoDlFiles), current >= 0 && (current & AutoDownloadRules.FILE) != 0);
        final CheckBoxCell global = makeCell(ctx, LocaleController.getString(R.string.NekoModAutoDlUseGlobal), current < 0);
        // marcar un tipo desmarca "global"; marcar "global" desmarca los tipos
        final CheckBoxCell[] kinds = {photos, videos, files};
        for (CheckBoxCell c : kinds) {
            c.setOnClickListener(v -> {
                CheckBoxCell cell = (CheckBoxCell) v;
                cell.setChecked(!cell.isChecked(), true);
                if (cell.isChecked()) global.setChecked(false, true);
            });
        }
        global.setOnClickListener(v -> {
            global.setChecked(!global.isChecked(), true);
            if (global.isChecked()) {
                for (CheckBoxCell c : kinds) c.setChecked(false, true);
            }
        });
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(photos, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        box.addView(videos, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        box.addView(files, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        box.addView(global, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        AlertDialog.Builder builder = new AlertDialog.Builder(ctx);
        builder.setTitle(LocaleController.getString(R.string.NekoModAutoDlTitle));
        builder.setView(box);
        builder.setPositiveButton(LocaleController.getString(R.string.NekoModAutoDlSave), (d, w) -> {
            int mask;
            if (global.isChecked()) {
                mask = -1;
            } else {
                mask = (photos.isChecked() ? AutoDownloadRules.PHOTO : 0)
                        | (videos.isChecked() ? AutoDownloadRules.VIDEO : 0)
                        | (files.isChecked() ? AutoDownloadRules.FILE : 0);
            }
            AutoDownloadRules.set(dialogId, mask);
        });
        builder.setNegativeButton(LocaleController.getString(R.string.NekoModAutoDlCancel), null);
        fragment.showDialog(builder.create());
    }
}
