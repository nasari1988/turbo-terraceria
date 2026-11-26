package tw.nekomimi.nekogram.settings;

import android.view.View;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;

import tw.nekomimi.nekogram.NekoModConfig;

public class NekoModSettingsActivity extends BaseNekoSettingsActivity {

    private static final int[] DELAYS = {0, 1, 2, 3, 5, 10};

    private final int appearanceRow = rowId++;

    private final int videoAutoScrollRow = rowId++;
    private final int autoScrollDelayRow = rowId++;
    private final int autoScrollGifsRow = rowId++;
    private final int autoScrollLoopRow = rowId++;

    private static String delayLabel(int seconds) {
        if (seconds <= 0) {
            return LocaleController.getString(R.string.NekoModSecondsNone);
        }
        return LocaleController.formatString(R.string.NekoModSecondsFormat, seconds);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.NekoModAppearance)));
        items.add(UItem.asButton(appearanceRow, R.drawable.msg_theme, LocaleController.getString(R.string.NekoModAppearance)).slug("modAppearance"));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(LocaleController.getString(R.string.NekoModVideos)));
        items.add(UItem.asCheck(videoAutoScrollRow, LocaleController.getString(R.string.NekoModVideoAutoScroll)).slug("videoAutoScroll").setChecked(NekoModConfig.videoAutoScroll));
        items.add(TextSettingsCellFactory.of(autoScrollDelayRow, LocaleController.getString(R.string.NekoModAutoScrollDelay), delayLabel(NekoModConfig.autoScrollDelay)).slug("autoScrollDelay"));
        items.add(UItem.asCheck(autoScrollGifsRow, LocaleController.getString(R.string.NekoModAutoScrollGifs)).slug("autoScrollGifs").setChecked(NekoModConfig.autoScrollGifs));
        items.add(UItem.asCheck(autoScrollLoopRow, LocaleController.getString(R.string.NekoModAutoScrollLoop)).slug("autoScrollLoop").setChecked(NekoModConfig.autoScrollLoop));
        items.add(UItem.asShadow(null));
    }

    @Override
    protected void onItemClick(UItem item, View view, int position, float x, float y) {
        var id = item.id;
        if (id == appearanceRow) {
            presentFragment(new NekoModAppearanceActivity());
        } else if (id == videoAutoScrollRow) {
            NekoModConfig.setBoolean("videoAutoScroll", !NekoModConfig.videoAutoScroll);
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoModConfig.videoAutoScroll);
            }
        } else if (id == autoScrollGifsRow) {
            NekoModConfig.setBoolean("autoScrollGifs", !NekoModConfig.autoScrollGifs);
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoModConfig.autoScrollGifs);
            }
        } else if (id == autoScrollLoopRow) {
            NekoModConfig.setBoolean("autoScrollLoop", !NekoModConfig.autoScrollLoop);
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoModConfig.autoScrollLoop);
            }
        } else if (id == autoScrollDelayRow) {
            ArrayList<String> arrayList = new ArrayList<>();
            int checked = 0;
            for (int i = 0; i < DELAYS.length; i++) {
                arrayList.add(delayLabel(DELAYS[i]));
                if (DELAYS[i] == NekoModConfig.autoScrollDelay) {
                    checked = i;
                }
            }
            showPopup(arrayList, checked, item, view, i -> {
                NekoModConfig.setInt("autoScrollDelay", DELAYS[i]);
                listView.adapter.notifyItemChanged(position, PARTIAL);
            });
        }
    }

    @Override
    protected String getActionBarTitle() {
        return LocaleController.getString(R.string.NekoModSettings);
    }

    @Override
    protected String getKey() {
        return "m";
    }
}
