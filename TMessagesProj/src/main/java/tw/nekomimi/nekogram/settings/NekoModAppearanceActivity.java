package tw.nekomimi.nekogram.settings;

import android.view.View;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.ThemeActivity;
import org.telegram.ui.WallpapersListActivity;

import java.util.ArrayList;

import tw.nekomimi.nekogram.NekoModConfig;

public class NekoModAppearanceActivity extends BaseNekoSettingsActivity {

    private final int amoledRow = rowId++;
    private final int themeRow = rowId++;
    private final int autoNightRow = rowId++;
    private final int chatBackgroundRow = rowId++;
    private final int appIconRow = rowId++;

    private final int bubbleStyleRow = rowId++;
    private final int messageCornersRow = rowId++;

    private final int fontFamilyRow = rowId++;

    private final int compactChatListRow = rowId++;
    private final int squareAvatarsRow = rowId++;

    private final int glassBarsRow = rowId++;
    private final int animationSpeedRow = rowId++;

    private static String bubbleLabel(int style) {
        return switch (style) {
            case NekoModConfig.BUBBLE_SQUARE -> LocaleController.getString(R.string.NekoModBubbleSquare);
            case NekoModConfig.BUBBLE_IOS -> LocaleController.getString(R.string.NekoModBubbleIos);
            default -> LocaleController.getString(R.string.NekoModBubbleRounded);
        };
    }

    private static String fontLabel(int font) {
        return switch (font) {
            case NekoModConfig.FONT_INTER -> LocaleController.getString(R.string.NekoModFontInter);
            case NekoModConfig.FONT_SYSTEM -> LocaleController.getString(R.string.NekoModFontSystem);
            default -> LocaleController.getString(R.string.NekoModFontDefault);
        };
    }

    private static String animLabel(int speed) {
        return switch (speed) {
            case NekoModConfig.ANIM_FAST -> LocaleController.getString(R.string.NekoModAnimFast);
            case NekoModConfig.ANIM_SMOOTH -> LocaleController.getString(R.string.NekoModAnimSmooth);
            case NekoModConfig.ANIM_OFF -> LocaleController.getString(R.string.NekoModAnimOff);
            default -> LocaleController.getString(R.string.NekoModAnimNormal);
        };
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.NekoModTheme)));
        items.add(UItem.asCheck(amoledRow, LocaleController.getString(R.string.NekoModAmoled), LocaleController.getString(R.string.NekoModAmoledAbout)).slug("amoledTheme").setChecked(NekoModConfig.amoledTheme));
        items.add(UItem.asButton(themeRow, R.drawable.msg_theme, LocaleController.getString(R.string.NekoModThemeAccent)).slug("modTheme"));
        items.add(UItem.asButton(autoNightRow, R.drawable.msg_theme, LocaleController.getString(R.string.NekoModAutoNight)).slug("modAutoNight"));
        items.add(UItem.asButton(chatBackgroundRow, R.drawable.msg_media, LocaleController.getString(R.string.NekoModChatBackground)).slug("modChatBackground"));
        items.add(UItem.asButton(appIconRow, R.drawable.msg_theme, LocaleController.getString(R.string.NekoModAppIcon)).slug("modAppIcon"));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(LocaleController.getString(R.string.NekoModBubbles)));
        items.add(TextSettingsCellFactory.of(bubbleStyleRow, LocaleController.getString(R.string.NekoModBubbleShape), bubbleLabel(NekoModConfig.bubbleStyle)).slug("bubbleStyle"));
        items.add(UItem.asButton(messageCornersRow, LocaleController.getString(R.string.NekoModMessageCorners)).slug("modMessageCorners"));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(LocaleController.getString(R.string.NekoModFont)));
        items.add(TextSettingsCellFactory.of(fontFamilyRow, LocaleController.getString(R.string.NekoModTypography), fontLabel(NekoModConfig.fontFamily)).slug("fontFamily"));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(LocaleController.getString(R.string.NekoModChatList)));
        items.add(UItem.asCheck(compactChatListRow, LocaleController.getString(R.string.NekoModCompactList)).slug("compactChatList").setChecked(NekoModConfig.compactChatList));
        items.add(UItem.asCheck(squareAvatarsRow, LocaleController.getString(R.string.NekoModSquareAvatars)).slug("squareAvatars").setChecked(NekoModConfig.squareAvatars));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(LocaleController.getString(R.string.NekoModEffects)));
        items.add(UItem.asCheck(glassBarsRow, LocaleController.getString(R.string.NekoModGlassBars)).slug("glassBars").setChecked(NekoModConfig.glassBars));
        items.add(TextSettingsCellFactory.of(animationSpeedRow, LocaleController.getString(R.string.NekoModAnimations), animLabel(NekoModConfig.animationSpeed)).slug("animationSpeed"));
        items.add(UItem.asShadow(null));
    }

    private void toggle(View view, String key, boolean newValue) {
        NekoModConfig.setBoolean(key, newValue);
        if (view instanceof TextCheckCell) {
            ((TextCheckCell) view).setChecked(newValue);
        }
    }

    private void showMessage(int resId) {
        BulletinFactory.of(this).createErrorBulletin(LocaleController.getString(resId)).show();
    }

    @Override
    protected void onItemClick(UItem item, View view, int position, float x, float y) {
        var id = item.id;
        if (id == amoledRow) {
            toggle(view, "amoledTheme", !NekoModConfig.amoledTheme);
            showMessage(R.string.NekoModRestartChanges);
        } else if (id == themeRow || id == appIconRow || id == messageCornersRow) {
            presentFragment(new ThemeActivity(ThemeActivity.THEME_TYPE_BASIC));
        } else if (id == autoNightRow) {
            presentFragment(new ThemeActivity(ThemeActivity.THEME_TYPE_NIGHT));
        } else if (id == chatBackgroundRow) {
            presentFragment(new WallpapersListActivity(WallpapersListActivity.TYPE_ALL));
        } else if (id == bubbleStyleRow) {
            ArrayList<String> arrayList = new ArrayList<>();
            arrayList.add(bubbleLabel(NekoModConfig.BUBBLE_ROUNDED));
            arrayList.add(bubbleLabel(NekoModConfig.BUBBLE_SQUARE));
            arrayList.add(bubbleLabel(NekoModConfig.BUBBLE_IOS));
            showPopup(arrayList, NekoModConfig.bubbleStyle, item, view, i -> {
                NekoModConfig.setInt("bubbleStyle", i);
                listView.adapter.notifyItemChanged(position, PARTIAL);
                showMessage(R.string.NekoModRestartChanges);
            });
        } else if (id == fontFamilyRow) {
            ArrayList<String> arrayList = new ArrayList<>();
            arrayList.add(fontLabel(NekoModConfig.FONT_DEFAULT));
            arrayList.add(fontLabel(NekoModConfig.FONT_INTER));
            arrayList.add(fontLabel(NekoModConfig.FONT_SYSTEM));
            showPopup(arrayList, NekoModConfig.fontFamily, item, view, i -> {
                NekoModConfig.setInt("fontFamily", i);
                listView.adapter.notifyItemChanged(position, PARTIAL);
                showMessage(R.string.NekoModRestartFont);
            });
        } else if (id == compactChatListRow) {
            toggle(view, "compactChatList", !NekoModConfig.compactChatList);
            parentLayout.rebuildAllFragmentViews(false, false);
        } else if (id == squareAvatarsRow) {
            toggle(view, "squareAvatars", !NekoModConfig.squareAvatars);
            parentLayout.rebuildAllFragmentViews(false, false);
        } else if (id == glassBarsRow) {
            toggle(view, "glassBars", !NekoModConfig.glassBars);
            parentLayout.rebuildAllFragmentViews(false, false);
        } else if (id == animationSpeedRow) {
            ArrayList<String> arrayList = new ArrayList<>();
            arrayList.add(animLabel(NekoModConfig.ANIM_FAST));
            arrayList.add(animLabel(NekoModConfig.ANIM_NORMAL));
            arrayList.add(animLabel(NekoModConfig.ANIM_SMOOTH));
            arrayList.add(animLabel(NekoModConfig.ANIM_OFF));
            showPopup(arrayList, NekoModConfig.animationSpeed, item, view, i -> {
                NekoModConfig.setInt("animationSpeed", i);
                listView.adapter.notifyItemChanged(position, PARTIAL);
            });
        }
    }

    @Override
    protected String getActionBarTitle() {
        return LocaleController.getString(R.string.NekoModAppearance);
    }

    @Override
    protected String getKey() {
        return "n";
    }
}
