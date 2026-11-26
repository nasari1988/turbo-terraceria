package tw.nekomimi.nekogram.nekomod.lock;

import android.content.Context;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Set;

import tw.nekomimi.nekogram.NekoModConfig;

import static org.telegram.messenger.LocaleController.getString;

/**
 * NekoMod: acciones de interfaz para candado (chat/carpeta) y chats ocultos.
 */
public final class LockActions {

    private LockActions() {
    }

    // ------------------------------------------------------------ chats ocultos: filtro

    private static String hiddenCacheKey = "";
    private static long[] hiddenCacheIds = new long[0];

    private static long[] hiddenIds() {
        String csv = NekoModConfig.hiddenDialogs;
        if (csv == null) {
            csv = "";
        }
        if (!csv.equals(hiddenCacheKey)) {
            Set<Long> set = NekoModConfig.parseIds(csv);
            long[] ids = new long[set.size()];
            int i = 0;
            for (Long id : set) {
                ids[i++] = id;
            }
            Arrays.sort(ids);
            hiddenCacheIds = ids;
            hiddenCacheKey = csv;
        }
        return hiddenCacheIds;
    }

    /** Devuelve la misma lista si no hay nada que filtrar; si no, una copia sin los chats ocultos. */
    public static ArrayList<TLRPC.Dialog> filterHidden(ArrayList<TLRPC.Dialog> source) {
        if (source == null || (NekoModConfig.hiddenUnlocked && !NekoModConfig.fakeModeActive)) {
            return source;
        }
        long[] ids = hiddenIds();
        if (ids.length == 0) {
            return source;
        }
        ArrayList<TLRPC.Dialog> result = null;
        final int n = source.size();
        for (int i = 0; i < n; i++) {
            TLRPC.Dialog d = source.get(i);
            if (d != null && Arrays.binarySearch(ids, d.id) >= 0) {
                if (result == null) {
                    result = new ArrayList<>(n);
                    result.addAll(source.subList(0, i));
                }
            } else if (result != null) {
                result.add(d);
            }
        }
        return result == null ? source : result;
    }

    // ------------------------------------------------------------ candado: acciones

    private static void bulletin(BaseFragment fragment, int rawIcon, String text) {
        try {
            BulletinFactory.of(fragment).createSimpleBulletin(rawIcon, text).show();
        } catch (Exception ignore) {
        }
    }

    private static boolean allIn(Set<Long> set, ArrayList<Long> ids) {
        if (ids.isEmpty()) {
            return false;
        }
        for (int i = 0; i < ids.size(); i++) {
            if (!set.contains(ids.get(i))) {
                return false;
            }
        }
        return true;
    }

    public static boolean allLocked(ArrayList<Long> dialogIds) {
        return allIn(NekoModConfig.parseIds(NekoModConfig.lockedDialogs), dialogIds);
    }

    public static boolean allHidden(ArrayList<Long> dialogIds) {
        return allIn(NekoModConfig.parseIds(NekoModConfig.hiddenDialogs), dialogIds);
    }

    /** Alterna el candado de los chats elegidos. Quitar el candado exige autenticarse. */
    public static void toggleDialogsLock(final BaseFragment fragment, final ArrayList<Long> dialogIds, final Runnable after) {
        if (dialogIds == null || dialogIds.isEmpty()) {
            return;
        }
        final ArrayList<Long> ids = new ArrayList<>(dialogIds);
        final boolean unlock = allLocked(ids);
        Runnable apply = () -> {
            Set<Long> set = NekoModConfig.parseIds(NekoModConfig.lockedDialogs);
            if (unlock) {
                set.removeAll(ids);
            } else {
                set.addAll(ids);
                for (int i = 0; i < ids.size(); i++) {
                    ChatLockManager.markUnlocked(ids.get(i)); // quien lo bloquea ya está autenticado en esta sesión
                }
            }
            NekoModConfig.setString("lockedDialogs", NekoModConfig.joinIds(set));
            bulletin(fragment, unlock ? R.raw.passcode_lock : R.raw.passcode_lock_close,
                    getString(unlock ? R.string.NekoModLockChatUnlockedBulletin : R.string.NekoModLockChatLockedBulletin));
            if (after != null) {
                after.run();
            }
        };
        if (unlock) {
            ChatLockManager.authenticate(getString(R.string.NekoModLockPromptTitle), true, ok -> {
                if (ok) {
                    apply.run();
                }
            });
        } else {
            apply.run();
        }
    }

    /** Alterna el candado de una carpeta (id = DialogFilter.id). */
    public static void toggleFolderLock(final BaseFragment fragment, final int folderId) {
        final boolean unlock = NekoModConfig.isFolderLocked(folderId);
        Runnable apply = () -> {
            Set<Long> set = NekoModConfig.parseIds(NekoModConfig.lockedFolders);
            if (unlock) {
                set.remove((long) folderId);
            } else {
                set.add((long) folderId);
            }
            NekoModConfig.setString("lockedFolders", NekoModConfig.joinIds(set));
            bulletin(fragment, unlock ? R.raw.passcode_lock : R.raw.passcode_lock_close,
                    getString(unlock ? R.string.NekoModLockFolderUnlockedBulletin : R.string.NekoModLockFolderLockedBulletin));
        };
        if (unlock) {
            ChatLockManager.authenticate(getString(R.string.NekoModLockPromptTitle), true, ok -> {
                if (ok) {
                    apply.run();
                }
            });
        } else {
            apply.run();
        }
    }

    public static String folderLockLabel(int folderId) {
        return getString(NekoModConfig.isFolderLocked(folderId) ? R.string.NekoModLockUnlockFolder : R.string.NekoModLockLockFolder);
    }

    // ------------------------------------------------------------ chats ocultos: acciones

    /** Actualiza los textos de los dos ítems del modo de selección según los chats elegidos. */
    public static void updateActionItems(ActionBarMenuSubItem lockItem, ActionBarMenuSubItem hideItem, ArrayList<Long> selected) {
        if (lockItem != null) {
            lockItem.setTextAndIcon(getString(allLocked(selected) ? R.string.NekoModLockUnlockChat : R.string.NekoModLockLockChat), R.drawable.msg_secret);
        }
        if (hideItem != null) {
            hideItem.setTextAndIcon(getString(allHidden(selected) ? R.string.NekoModLockShowChat : R.string.NekoModLockHideChat), R.drawable.msg_viewchats);
        }
    }

    /** Oculta o muestra los chats elegidos. La primera vez pide crear el PIN. */
    public static void toggleDialogsHidden(final BaseFragment fragment, final ArrayList<Long> dialogIds, final Runnable after) {
        if (dialogIds == null || dialogIds.isEmpty() || fragment.getParentActivity() == null) {
            return;
        }
        final ArrayList<Long> ids = new ArrayList<>(dialogIds);
        final boolean show = allHidden(ids);
        final Runnable apply = () -> {
            Set<Long> set = NekoModConfig.parseIds(NekoModConfig.hiddenDialogs);
            if (show) {
                set.removeAll(ids);
            } else {
                set.addAll(ids);
            }
            NekoModConfig.setString("hiddenDialogs", NekoModConfig.joinIds(set));
            bulletin(fragment, show ? R.raw.chats_unhide : R.raw.chats_hide,
                    getString(show ? R.string.NekoModLockChatShownBulletin : R.string.NekoModLockChatHiddenBulletin));
            if (after != null) {
                after.run();
            }
        };
        if (!show && (NekoModConfig.hiddenPinHash == null || NekoModConfig.hiddenPinHash.isEmpty())) {
            askNewPin(fragment, apply);
        } else {
            apply.run();
        }
    }

    private static EditText makePinEdit(Context context) {
        EditText edit = new EditText(context);
        edit.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        edit.setFilters(new InputFilter[]{new InputFilter.LengthFilter(8)});
        edit.setSingleLine(true);
        edit.setGravity(Gravity.CENTER);
        edit.setTextSize(20);
        return edit;
    }

    private static AlertDialog showPinDialog(BaseFragment fragment, String title, String message, String positive, final EditText edit, AlertDialog.OnButtonClickListener onOk) {
        Context context = fragment.getParentActivity();
        FrameLayout container = new FrameLayout(context);
        container.addView(edit, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 24, 0, 24, 0));
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(title);
        builder.setMessage(message);
        builder.setView(container);
        builder.setPositiveButton(positive, onOk);
        builder.setNegativeButton(getString(R.string.NekoModLockCancel), null);
        AlertDialog dialog = builder.create();
        fragment.showDialog(dialog);
        AndroidUtilities.runOnUIThread(() -> {
            edit.requestFocus();
            AndroidUtilities.showKeyboard(edit);
        }, 200);
        return dialog;
    }

    private static boolean validPin(String pin) {
        return pin.length() >= 4 && pin.length() <= 8;
    }

    /** Crear el PIN: escribirlo y confirmarlo. Al coincidir se guarda el hash y se ejecuta onDone. */
    private static void askNewPin(final BaseFragment fragment, final Runnable onDone) {
        Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        final EditText first = makePinEdit(context);
        showPinDialog(fragment, getString(R.string.NekoModLockPinCreateTitle), getString(R.string.NekoModLockPinCreateMessage),
                getString(R.string.NekoModLockNext), first, (d, which) -> {
                    final String pin = first.getText().toString();
                    if (!validPin(pin)) {
                        bulletin(fragment, R.raw.error, getString(R.string.NekoModLockPinInvalid));
                        return;
                    }
                    final EditText second = makePinEdit(context);
                    AndroidUtilities.runOnUIThread(() -> showPinDialog(fragment, getString(R.string.NekoModLockPinConfirmTitle), getString(R.string.NekoModLockPinConfirmMessage),
                            getString(R.string.NekoModLockSave), second, (d2, w2) -> {
                                if (!pin.equals(second.getText().toString())) {
                                    bulletin(fragment, R.raw.error, getString(R.string.NekoModLockPinMismatch));
                                    return;
                                }
                                NekoModConfig.setString("hiddenPinHash", NekoModConfig.sha256(pin));
                                onDone.run();
                            }), 150);
                });
    }

    /**
     * Pide el PIN de chats ocultos. Si coincide alterna hiddenUnlocked (ver / volver a ocultar) y llama a onChanged.
     */
    public static void askPinToToggleHidden(final BaseFragment fragment, final Runnable onChanged) {
        Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        if (NekoModConfig.hiddenPinHash == null || NekoModConfig.hiddenPinHash.isEmpty()) {
            bulletin(fragment, R.raw.info, getString(R.string.NekoModLockPinNone));
            return;
        }
        final EditText edit = makePinEdit(context);
        showPinDialog(fragment, getString(R.string.NekoModLockPinEnterTitle), getString(R.string.NekoModLockPinEnterMessage),
                getString(R.string.NekoModLockAccept), edit, (d, which) -> {
                    if (tryPin(edit.getText().toString())) {
                        if (onChanged != null) {
                            onChanged.run();
                        }
                    } else {
                        bulletin(fragment, R.raw.error, getString(R.string.NekoModLockPinWrong));
                    }
                });
    }

    /**
     * Si el texto es el PIN exacto, alterna hiddenUnlocked y devuelve true. Útil para el buscador.
     */
    public static boolean tryPin(String text) {
        String hash = NekoModConfig.hiddenPinHash;
        if (text == null || hash == null || hash.isEmpty() || !validPin(text)) {
            return false;
        }
        if (!hash.equals(NekoModConfig.sha256(text))) {
            return false;
        }
        NekoModConfig.hiddenUnlocked = !NekoModConfig.hiddenUnlocked;
        return true;
    }
}
