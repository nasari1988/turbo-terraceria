package tw.nekomimi.nekogram.nekomod.download;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Set;

import tw.nekomimi.nekogram.NekoModConfig;

/** NekoMod: lista de chats sincronizados (syncedDialogs) con "Actualizar todos". */
public class SyncedChatsActivity extends BaseFragment {

    private LinearLayout root;
    private boolean updating;
    private HashMap<Long, Long> syncTimes = new HashMap<>();
    private TextView updateAllButton;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.NekoModDlSyncedTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(root, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        fragmentView = scroll;
        rebuild();
        BulkDownloadManager.get(currentAccount).getAllSyncTimes(map -> {
            syncTimes = map;
            rebuild();
        });
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (root != null) {
            BulkDownloadManager.get(currentAccount).getAllSyncTimes(map -> {
                syncTimes = map;
                rebuild();
            });
        }
    }

    private void rebuild() {
        final Context context = root.getContext();
        root.removeAllViews();
        final ArrayList<Long> ids = new ArrayList<>(NekoModConfig.parseIds(NekoModConfig.syncedDialogs));

        // opciones globales
        HeaderCell header = new HeaderCell(context);
        header.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        header.setText(LocaleController.getString(R.string.NekoModDlOptions));
        root.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final TextCheckCell nightly = new TextCheckCell(context);
        nightly.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        nightly.setTextAndCheck(LocaleController.getString(R.string.NekoModDlNightly), NekoModConfig.nightlySync, true);
        nightly.setOnClickListener(v -> {
            boolean enable = !nightly.isChecked();
            nightly.setChecked(enable);
            NekoModConfig.setBoolean("nightlySync", enable);
            NightlySyncJob.schedule(context.getApplicationContext(), enable);
        });
        root.addView(nightly, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final TextCheckCell redownload = new TextCheckCell(context);
        redownload.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        redownload.setTextAndCheck(LocaleController.getString(R.string.NekoModDlRedownload), NekoModConfig.redownloadDeleted, false);
        redownload.setOnClickListener(v -> {
            redownload.setChecked(!redownload.isChecked());
            NekoModConfig.setBoolean("redownloadDeleted", redownload.isChecked());
        });
        root.addView(redownload, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextInfoPrivacyCell gap = new TextInfoPrivacyCell(context);
        gap.setText(ids.isEmpty() ? LocaleController.getString(R.string.NekoModDlSyncedEmpty) : "");
        root.addView(gap, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        if (ids.isEmpty()) {
            return;
        }

        // botón actualizar todos
        LinearLayout box = new LinearLayout(context);
        box.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(4), AndroidUtilities.dp(16), AndroidUtilities.dp(12));
        updateAllButton = new TextView(context);
        updateAllButton.setGravity(Gravity.CENTER);
        updateAllButton.setTypeface(AndroidUtilities.bold());
        updateAllButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        updateAllButton.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
        int color = Theme.getColor(Theme.key_featuredStickers_addButton);
        updateAllButton.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(8), color, ColorUtils.setAlphaComponent(color, 190)));
        updateAllButton.setText(LocaleController.getString(R.string.NekoModDlUpdateAll));
        updateAllButton.setAlpha(updating ? 0.5f : 1f);
        updateAllButton.setOnClickListener(v -> updateAll(ids));
        box.addView(updateAllButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));
        root.addView(box, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        DateFormat df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
        for (int i = 0; i < ids.size(); i++) {
            final long id = ids.get(i);
            String name = org.telegram.messenger.MessagesController.getInstance(currentAccount).getFullName(id);
            if (name == null) name = LocaleController.getString(R.string.NekoModDlUnknownChat) + " " + id;
            Long t = syncTimes.get(id);
            String value = t != null && t > 0 ? df.format(new Date(t)) : LocaleController.getString(R.string.NekoModDlNeverSynced);
            TextSettingsCell cell = new TextSettingsCell(context);
            cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            cell.setTextAndValue(name, value, i < ids.size() - 1);
            cell.setOnClickListener(v -> presentFragment(new BulkDownloadActivity(id)));
            cell.setOnLongClickListener(v -> {
                if (getParentActivity() == null) return true;
                AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
                b.setTitle(LocaleController.getString(R.string.NekoModDlRemove));
                b.setPositiveButton(LocaleController.getString(R.string.NekoModDlOk), (d, w) -> {
                    Set<Long> set = NekoModConfig.parseIds(NekoModConfig.syncedDialogs);
                    set.remove(id);
                    NekoModConfig.setString("syncedDialogs", NekoModConfig.joinIds(set));
                    rebuild();
                });
                b.setNegativeButton(LocaleController.getString(R.string.NekoModDlCancel), null);
                showDialog(b.create());
                return true;
            });
            root.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
    }

    private void updateAll(ArrayList<Long> ids) {
        if (updating) return;
        updating = true;
        if (updateAllButton != null) updateAllButton.setAlpha(0.5f);
        Context c = getParentActivity();
        if (c != null) {
            Toast.makeText(c, LocaleController.formatString(R.string.NekoModDlUpdatingAll, ids.size()), Toast.LENGTH_SHORT).show();
        }
        final BulkDownloadManager manager = BulkDownloadManager.get(currentAccount);
        manager.updateDialogs(ids, BulkDownloadManager.isWifiOrEthernet(org.telegram.messenger.ApplicationLoader.applicationContext), () -> {
            updating = false;
            manager.getAllSyncTimes(map -> {
                syncTimes = map;
                if (root != null) rebuild();
            });
        });
    }
}
