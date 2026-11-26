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

public class NekoModSettingsActivity extends BaseNekoSettingsActivity {

    private static final int[] DELAYS = {0, 1, 2, 3, 5, 10};
    // NekoMod: velocidades de audio
    private static final float[] VOICE_SPEEDS = {1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f};
    private static final String[] VOICE_SPEED_LABELS = {"1x", "1.25x", "1.5x", "1.75x", "2x", "2.5x", "3x"};

    private static int voiceSpeedIndex(float speed) {
        int best = 0;
        for (int i = 1; i < VOICE_SPEEDS.length; i++) {
            if (Math.abs(VOICE_SPEEDS[i] - speed) < Math.abs(VOICE_SPEEDS[best] - speed)) best = i;
        }
        return best;
    }

    private static String qualityLabel(int q) {
        return switch (q) {
            case NekoModConfig.QUALITY_HIGH -> LocaleController.getString(R.string.NekoModSetQualityHigh);
            case NekoModConfig.QUALITY_MEDIUM -> LocaleController.getString(R.string.NekoModSetQualityMedium);
            case NekoModConfig.QUALITY_LOW -> LocaleController.getString(R.string.NekoModSetQualityLow);
            default -> LocaleController.getString(R.string.NekoModSetQualityAuto);
        };
    }

    private final int selfTestRow = rowId++; // NekoMod: autodiagnóstico
    // NekoMod: filas de privacidad (etapa 2)
    private final int keepDeletedRow = rowId++;
    private final int keepEditsRow = rowId++;
    private final int ghostModeRow = rowId++;
    private final int ghostNoReadRow = rowId++;
    private final int ghostNoTypingRow = rowId++;
    private final int ghostNoOnlineRow = rowId++;
    private final int ghostNoStoriesRow = rowId++;
    private final int ghostReadOnReplyRow = rowId++;
    private final int relockModeRow = rowId++;
    private final int changePinRow = rowId++;
    private final int fakeSetupRow = rowId++;
    private final int fakeRemoveRow = rowId++;
    private final int fakeWipeRow = rowId++;
    private final int calcCodeRow = rowId++;
    private final int calcDisguiseRow = rowId++;
    // NekoMod: contenido, multimedia y descargas (etapa 3)
    private final int allowSaveProtectedRow = rowId++;
    private final int saveViewOnceRow = rowId++;
    private final int playbackQualityRow = rowId++;
    private final int saveBestQualityRow = rowId++;
    private final int askQualityRow = rowId++;
    private final int backgroundPlaybackRow = rowId++;
    private final int sendOriginalVideoRow = rowId++;
    private final int voiceSpeedRow = rowId++;
    private final int syncedChatsRow = rowId++;
    private final int nightlySyncRow = rowId++;
    private final int redownloadRow = rowId++;

    private final int videoAutoScrollRow = rowId++;
    private final int autoScrollDelayRow = rowId++;
    private final int autoScrollGifsRow = rowId++;
    private final int autoScrollLoopRow = rowId++;

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

    // NekoMod
    private static String relockLabel(int mode) {
        return LocaleController.getString(mode == NekoModConfig.RELOCK_ALWAYS ? R.string.NekoModRelockAlways : R.string.NekoModRelockExit);
    }

    // NekoMod: ¿hay código de calculadora configurado?
    private static boolean hasCalcCode() {
        android.content.SharedPreferences sp = org.telegram.messenger.ApplicationLoader.applicationContext.getSharedPreferences("nekomodconfig", android.app.Activity.MODE_PRIVATE);
        return !sp.getString("calcCodeHash", "").isEmpty();
    }

    // NekoMod: pide el código de la calculadora (4-8 dígitos); onSaved se ejecuta tras guardarlo
    private void askCalcCode(Runnable onSaved) {
        final android.content.Context ctx = getParentActivity();
        if (ctx == null) return;
        final android.widget.EditText edit = new android.widget.EditText(ctx);
        edit.setHint(LocaleController.getString(R.string.NekoModCalcCodeHint));
        edit.setSingleLine(true);
        edit.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        edit.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(8)});
        android.widget.FrameLayout box = new android.widget.FrameLayout(ctx);
        box.addView(edit, org.telegram.ui.Components.LayoutHelper.createFrame(org.telegram.ui.Components.LayoutHelper.MATCH_PARENT, org.telegram.ui.Components.LayoutHelper.WRAP_CONTENT, android.view.Gravity.CENTER, 24, 0, 24, 0));
        showDialog(new org.telegram.ui.ActionBar.AlertDialog.Builder(ctx)
                .setTitle(LocaleController.getString(R.string.NekoModCalcCodeTitle))
                .setView(box)
                .setPositiveButton(LocaleController.getString(R.string.NekoModCalcAccept), (d, w) -> {
                    String digits = edit.getText().toString();
                    if (digits.length() < 4 || digits.length() > 8) {
                        showMessage(R.string.NekoModCalcCodeInvalid);
                        return;
                    }
                    tw.nekomimi.nekogram.nekomod.fake.CalculatorDisguise.setCode(ctx, digits);
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.done, LocaleController.getString(R.string.NekoModCalcCodeSaved)).show();
                    if (onSaved != null) onSaved.run();
                })
                .setNegativeButton(LocaleController.getString(R.string.NekoModCalcCancel), null)
                .create());
    }

    // NekoMod: confirma y activa el disfraz de calculadora
    private void confirmCalcDisguise() {
        final android.content.Context ctx = getParentActivity();
        if (ctx == null) return;
        showDialog(new org.telegram.ui.ActionBar.AlertDialog.Builder(ctx)
                .setTitle(LocaleController.getString(R.string.NekoModCalcConfirmTitle))
                .setMessage(LocaleController.getString(R.string.NekoModCalcConfirm))
                .setPositiveButton(LocaleController.getString(R.string.NekoModCalcAccept), (d, w) -> {
                    tw.nekomimi.nekogram.nekomod.fake.CalculatorDisguise.apply(ctx, true);
                    listView.adapter.update(true);
                })
                .setNegativeButton(LocaleController.getString(R.string.NekoModCalcCancel), (d, w) -> listView.adapter.update(true))
                .create());
    }

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

    private static String delayLabel(int seconds) {
        if (seconds <= 0) {
            return LocaleController.getString(R.string.NekoModSecondsNone);
        }
        return LocaleController.formatString(R.string.NekoModSecondsFormat, seconds);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asButton(selfTestRow, R.drawable.msg_info, "Ejecutar diagnóstico").slug("selfTest"));
        items.add(UItem.asShadow(null));
        // NekoMod: ajustes de privacidad (etapa 2)
        items.add(UItem.asHeader(LocaleController.getString(R.string.NekoModMessagesHeader)));
        items.add(UItem.asCheck(keepDeletedRow, LocaleController.getString(R.string.NekoModKeepDeleted)).slug("saveDeletedMessages").setChecked(NekoModConfig.saveDeletedMessages));
        items.add(UItem.asCheck(keepEditsRow, LocaleController.getString(R.string.NekoModKeepEdits)).slug("saveEditHistory").setChecked(NekoModConfig.saveEditHistory));
        items.add(UItem.asShadow(null));
        items.add(UItem.asHeader(LocaleController.getString(R.string.NekoModGhostHeader)));
        items.add(UItem.asCheck(ghostModeRow, LocaleController.getString(R.string.NekoModGhostMain), LocaleController.getString(R.string.NekoModGhostMainAbout)).slug("ghostMode").setChecked(NekoModConfig.ghostMode));
        items.add(UItem.asCheck(ghostNoReadRow, LocaleController.getString(R.string.NekoModGhostNoRead)).slug("ghostNoRead").setChecked(NekoModConfig.ghostNoRead));
        items.add(UItem.asCheck(ghostNoTypingRow, LocaleController.getString(R.string.NekoModGhostNoTyping)).slug("ghostNoTyping").setChecked(NekoModConfig.ghostNoTyping));
        items.add(UItem.asCheck(ghostNoOnlineRow, LocaleController.getString(R.string.NekoModGhostNoOnline)).slug("ghostNoOnline").setChecked(NekoModConfig.ghostNoOnline));
        items.add(UItem.asCheck(ghostNoStoriesRow, LocaleController.getString(R.string.NekoModGhostNoStories)).slug("ghostNoStories").setChecked(NekoModConfig.ghostNoStories));
        items.add(UItem.asCheck(ghostReadOnReplyRow, LocaleController.getString(R.string.NekoModGhostReadOnReply)).slug("ghostReadOnReply").setChecked(NekoModConfig.ghostReadOnReply));
        items.add(UItem.asShadow(null));
        items.add(UItem.asHeader(LocaleController.getString(R.string.NekoModLockHeader)));
        items.add(UItem.asShadow(LocaleController.getString(R.string.NekoModLockInfo)));
        items.add(TextSettingsCellFactory.of(relockModeRow, LocaleController.getString(R.string.NekoModRelockMode), relockLabel(NekoModConfig.relockMode)).slug("relockMode"));
        items.add(UItem.asButton(changePinRow, R.drawable.msg_secret, LocaleController.getString(R.string.NekoModChangeHiddenPin)).slug("changeHiddenPin"));
        items.add(UItem.asShadow(null));
        items.add(UItem.asHeader(LocaleController.getString(R.string.NekoModFakeHeader)));
        items.add(UItem.asButton(fakeSetupRow, R.drawable.msg_secret, LocaleController.getString(R.string.NekoModFakeSetup)).slug("fakeSetup"));
        if (NekoModConfig.fakePasscodeHash != null && !NekoModConfig.fakePasscodeHash.isEmpty()) {
            items.add(UItem.asButton(fakeRemoveRow, R.drawable.msg_delete, LocaleController.getString(R.string.NekoModFakeRemove)).slug("fakeRemove"));
        }
        items.add(UItem.asCheck(fakeWipeRow, LocaleController.getString(R.string.NekoModFakeWipe)).slug("fakeWipeHidden").setChecked(NekoModConfig.fakeWipeHidden));
        items.add(UItem.asShadow(null));
        items.add(UItem.asHeader(LocaleController.getString(R.string.NekoModCalcHeader)));
        items.add(UItem.asButton(calcCodeRow, R.drawable.msg_edit, LocaleController.getString(R.string.NekoModCalcCode)).slug("calcCode"));
        items.add(UItem.asCheck(calcDisguiseRow, LocaleController.getString(R.string.NekoModCalcShow)).slug("calculatorDisguise").setChecked(NekoModConfig.calculatorDisguise));
        items.add(UItem.asShadow(null));

        // NekoMod: contenido
        items.add(UItem.asHeader(LocaleController.getString(R.string.NekoModSetContentHeader)));
        items.add(UItem.asCheck(allowSaveProtectedRow, LocaleController.getString(R.string.NekoModSetAllowProtected), LocaleController.getString(R.string.NekoModSetAllowProtectedAbout)).slug("allowSaveProtected").setChecked(NekoModConfig.allowSaveProtected));
        items.add(UItem.asCheck(saveViewOnceRow, LocaleController.getString(R.string.NekoModSetSaveViewOnce), LocaleController.getString(R.string.NekoModSetSaveViewOnceAbout)).slug("saveViewOnce").setChecked(NekoModConfig.saveViewOnce));
        items.add(UItem.asShadow(null));

        // NekoMod: multimedia
        items.add(UItem.asHeader(LocaleController.getString(R.string.NekoModSetMediaHeader)));
        items.add(TextSettingsCellFactory.of(playbackQualityRow, LocaleController.getString(R.string.NekoModSetPlaybackQuality), qualityLabel(NekoModConfig.videoPlaybackQuality)).slug("videoPlaybackQuality"));
        items.add(UItem.asCheck(saveBestQualityRow, LocaleController.getString(R.string.NekoModSetSaveBest)).slug("saveBestQuality").setChecked(NekoModConfig.saveBestQuality));
        items.add(UItem.asCheck(askQualityRow, LocaleController.getString(R.string.NekoModSetAskQuality), LocaleController.getString(R.string.NekoModSetAskQualityAbout)).slug("askQualityOnSave").setChecked(NekoModConfig.askQualityOnSave));
        items.add(UItem.asCheck(backgroundPlaybackRow, LocaleController.getString(R.string.NekoModSetBackgroundPlayback)).slug("backgroundPlayback").setChecked(NekoModConfig.backgroundPlayback));
        items.add(UItem.asCheck(sendOriginalVideoRow, LocaleController.getString(R.string.NekoModSetSendOriginal)).slug("sendOriginalVideo").setChecked(NekoModConfig.sendOriginalVideo));
        items.add(TextSettingsCellFactory.of(voiceSpeedRow, LocaleController.getString(R.string.NekoModSetVoiceSpeed), VOICE_SPEED_LABELS[voiceSpeedIndex(NekoModConfig.defaultVoiceSpeed)]).slug("defaultVoiceSpeed"));
        items.add(UItem.asShadow(null));

        // NekoMod: descargas
        items.add(UItem.asHeader(LocaleController.getString(R.string.NekoModSetDownloadsHeader)));
        items.add(UItem.asButton(syncedChatsRow, R.drawable.msg_download, LocaleController.getString(R.string.NekoModSetSyncedChats)).slug("syncedChats"));
        items.add(UItem.asCheck(nightlySyncRow, LocaleController.getString(R.string.NekoModSetNightlySync)).slug("nightlySync").setChecked(NekoModConfig.nightlySync));
        items.add(UItem.asCheck(redownloadRow, LocaleController.getString(R.string.NekoModSetRedownload)).slug("redownloadDeleted").setChecked(NekoModConfig.redownloadDeleted));
        items.add(UItem.asShadow(LocaleController.getString(R.string.NekoModSetBulkHint)));

        items.add(UItem.asHeader(LocaleController.getString(R.string.NekoModVideos)));
        items.add(UItem.asCheck(videoAutoScrollRow, LocaleController.getString(R.string.NekoModVideoAutoScroll)).slug("videoAutoScroll").setChecked(NekoModConfig.videoAutoScroll));
        items.add(TextSettingsCellFactory.of(autoScrollDelayRow, LocaleController.getString(R.string.NekoModAutoScrollDelay), delayLabel(NekoModConfig.autoScrollDelay)).slug("autoScrollDelay"));
        items.add(UItem.asCheck(autoScrollGifsRow, LocaleController.getString(R.string.NekoModAutoScrollGifs)).slug("autoScrollGifs").setChecked(NekoModConfig.autoScrollGifs));
        items.add(UItem.asCheck(autoScrollLoopRow, LocaleController.getString(R.string.NekoModAutoScrollLoop)).slug("autoScrollLoop").setChecked(NekoModConfig.autoScrollLoop));
        items.add(UItem.asShadow(null));

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
        if (id == selfTestRow) {
            final String report = tw.nekomimi.nekogram.nekomod.NekoModSelfTest.run(getParentActivity());
            showDialog(new org.telegram.ui.ActionBar.AlertDialog.Builder(getParentActivity())
                    .setTitle("Diagnóstico")
                    .setMessage(report)
                    .setPositiveButton("Copiar reporte", (dialog, which) -> {
                        org.telegram.messenger.AndroidUtilities.addToClipboard(report);
                        BulletinFactory.of(this).createCopyBulletin("Reporte copiado").show();
                    })
                    .setNegativeButton("Cerrar", null)
                    .create());
        } else if (id == keepDeletedRow) {
            toggle(view, "saveDeletedMessages", !NekoModConfig.saveDeletedMessages);
        } else if (id == keepEditsRow) {
            toggle(view, "saveEditHistory", !NekoModConfig.saveEditHistory);
        } else if (id == ghostModeRow) {
            toggle(view, "ghostMode", !NekoModConfig.ghostMode);
        } else if (id == ghostNoReadRow) {
            toggle(view, "ghostNoRead", !NekoModConfig.ghostNoRead);
        } else if (id == ghostNoTypingRow) {
            toggle(view, "ghostNoTyping", !NekoModConfig.ghostNoTyping);
        } else if (id == ghostNoOnlineRow) {
            toggle(view, "ghostNoOnline", !NekoModConfig.ghostNoOnline);
        } else if (id == ghostNoStoriesRow) {
            toggle(view, "ghostNoStories", !NekoModConfig.ghostNoStories);
        } else if (id == ghostReadOnReplyRow) {
            toggle(view, "ghostReadOnReply", !NekoModConfig.ghostReadOnReply);
        } else if (id == relockModeRow) {
            ArrayList<String> arrayList = new ArrayList<>();
            arrayList.add(relockLabel(NekoModConfig.RELOCK_ON_EXIT));
            arrayList.add(relockLabel(NekoModConfig.RELOCK_ALWAYS));
            showPopup(arrayList, NekoModConfig.relockMode, item, view, i -> {
                NekoModConfig.setInt("relockMode", i);
                listView.adapter.notifyItemChanged(position, PARTIAL);
            });
        } else if (id == changePinRow) {
            tw.nekomimi.nekogram.nekomod.lock.LockActions.changeHiddenPin(this, () ->
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.done, LocaleController.getString(R.string.NekoModPinSaved)).show());
        } else if (id == fakeSetupRow) {
            tw.nekomimi.nekogram.nekomod.fake.FakePasscodeSetup.show(this);
            listView.adapter.update(true);
        } else if (id == fakeRemoveRow) {
            tw.nekomimi.nekogram.nekomod.fake.FakePasscodeSetup.clear();
            listView.adapter.update(true);
        } else if (id == fakeWipeRow) {
            toggle(view, "fakeWipeHidden", !NekoModConfig.fakeWipeHidden);
        } else if (id == calcCodeRow) {
            askCalcCode(null);
        } else if (id == calcDisguiseRow) {
            if (NekoModConfig.calculatorDisguise) {
                tw.nekomimi.nekogram.nekomod.fake.CalculatorDisguise.apply(getParentActivity(), false);
                listView.adapter.update(true);
            } else if (hasCalcCode()) {
                confirmCalcDisguise();
            } else {
                askCalcCode(this::confirmCalcDisguise);
            }
        } else if (id == allowSaveProtectedRow) { // NekoMod: etapa 3
            toggle(view, "allowSaveProtected", !NekoModConfig.allowSaveProtected);
        } else if (id == saveViewOnceRow) {
            toggle(view, "saveViewOnce", !NekoModConfig.saveViewOnce);
        } else if (id == playbackQualityRow) {
            ArrayList<String> arrayList = new ArrayList<>();
            arrayList.add(qualityLabel(NekoModConfig.QUALITY_AUTO));
            arrayList.add(qualityLabel(NekoModConfig.QUALITY_HIGH));
            arrayList.add(qualityLabel(NekoModConfig.QUALITY_MEDIUM));
            arrayList.add(qualityLabel(NekoModConfig.QUALITY_LOW));
            showPopup(arrayList, NekoModConfig.videoPlaybackQuality, item, view, i -> {
                NekoModConfig.setInt("videoPlaybackQuality", i);
                listView.adapter.notifyItemChanged(position, PARTIAL);
            });
        } else if (id == saveBestQualityRow) {
            toggle(view, "saveBestQuality", !NekoModConfig.saveBestQuality);
        } else if (id == askQualityRow) {
            toggle(view, "askQualityOnSave", !NekoModConfig.askQualityOnSave);
        } else if (id == backgroundPlaybackRow) {
            toggle(view, "backgroundPlayback", !NekoModConfig.backgroundPlayback);
        } else if (id == sendOriginalVideoRow) {
            toggle(view, "sendOriginalVideo", !NekoModConfig.sendOriginalVideo);
        } else if (id == voiceSpeedRow) {
            ArrayList<String> arrayList = new ArrayList<>();
            for (String label : VOICE_SPEED_LABELS) {
                arrayList.add(label);
            }
            showPopup(arrayList, voiceSpeedIndex(NekoModConfig.defaultVoiceSpeed), item, view, i -> {
                NekoModConfig.setFloat("defaultVoiceSpeed", VOICE_SPEEDS[i]);
                listView.adapter.notifyItemChanged(position, PARTIAL);
            });
        } else if (id == syncedChatsRow) {
            presentFragment(new tw.nekomimi.nekogram.nekomod.download.SyncedChatsActivity());
        } else if (id == nightlySyncRow) {
            final boolean enable = !NekoModConfig.nightlySync;
            toggle(view, "nightlySync", enable);
            tw.nekomimi.nekogram.nekomod.download.NightlySyncJob.schedule(org.telegram.messenger.ApplicationLoader.applicationContext, enable);
        } else if (id == redownloadRow) {
            toggle(view, "redownloadDeleted", !NekoModConfig.redownloadDeleted);
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
        } else if (id == amoledRow) {
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
        return LocaleController.getString(R.string.NekoModSettings);
    }

    @Override
    protected String getKey() {
        return "m";
    }
}
