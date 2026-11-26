package tw.nekomimi.nekogram.nekomod.lock;

import android.os.Bundle;
import android.os.SystemClock;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.LaunchActivity;

import java.util.ArrayList;
import java.util.HashSet;

import tw.nekomimi.nekogram.NekoModConfig;

import static org.telegram.messenger.LocaleController.getString;

/**
 * NekoMod: candado por chat o carpeta con huella / bloqueo de pantalla del teléfono.
 * Estado en memoria: diálogos desbloqueados en esta sesión.
 */
public final class ChatLockManager {

    /** Segundos de gracia fuera de la app antes de volver a bloquear todo. */
    private static final long RELOCK_AFTER_MS = 30_000L;

    private static final HashSet<Long> unlocked = new HashSet<>();
    private static long pausedAt = 0; // elapsedRealtime en que la app pasó a segundo plano (0 = en primer plano)
    private static BaseFragment approvedFragment; // fragmento ya autenticado, para no volver a pedir al re-presentarlo

    private ChatLockManager() {
    }

    // ------------------------------------------------------------------ estado

    public static boolean needsUnlock(int account, long dialogId) {
        if (dialogId == 0) {
            return false;
        }
        boolean locked = NekoModConfig.isDialogLocked(dialogId) || isInLockedFolder(account, dialogId);
        if (!locked) {
            return false;
        }
        if (NekoModConfig.relockMode == NekoModConfig.RELOCK_ALWAYS) {
            return true;
        }
        return !unlocked.contains(dialogId);
    }

    public static boolean isInLockedFolder(int account, long dialogId) {
        String folders = NekoModConfig.lockedFolders;
        if (folders == null || folders.isEmpty()) {
            return false;
        }
        try {
            MessagesController controller = MessagesController.getInstance(account);
            ArrayList<MessagesController.DialogFilter> filters = controller.dialogFilters;
            if (filters == null || filters.isEmpty()) {
                return false;
            }
            AccountInstance accountInstance = AccountInstance.getInstance(account);
            for (int i = 0; i < filters.size(); i++) {
                MessagesController.DialogFilter filter = filters.get(i);
                if (filter == null || filter.isDefault() || !NekoModConfig.isFolderLocked(filter.id)) {
                    continue;
                }
                if (filter.includesDialog(accountInstance, dialogId)) {
                    return true;
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return false;
    }

    public static void markUnlocked(long dialogId) {
        unlocked.add(dialogId);
    }

    public static void lockAll() {
        unlocked.clear();
        NekoModConfig.hiddenUnlocked = false;
    }

    public static void onAppPaused() {
        pausedAt = SystemClock.elapsedRealtime();
    }

    /** Se llama desde ActionBarLayout.onResume(). */
    public static void onAppResumed(INavigationLayout layout) {
        long was = pausedAt;
        pausedAt = 0;
        if (was != 0 && SystemClock.elapsedRealtime() - was > RELOCK_AFTER_MS) {
            lockAll();
            // Si el usuario quedó dentro de un chat bloqueado, volver a pedir autenticación.
            try {
                BaseFragment last = layout.getLastFragment();
                if (last instanceof ChatActivity) {
                    final long did = getDialogId(last);
                    if (needsUnlock(last.getCurrentAccount(), did)) {
                        authenticate(getString(R.string.NekoModLockPromptTitle), false, ok -> {
                            if (ok) {
                                markUnlocked(did);
                            } else {
                                last.finishFragment();
                            }
                        });
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
    }

    // ------------------------------------------------------------ intercepción

    /**
     * Llamado al inicio de ActionBarLayout.presentFragment(NavigationParams).
     * @return true si la apertura debe cancelarse (se pidió autenticación o está bloqueada).
     */
    public static boolean interceptPresent(final INavigationLayout layout, final INavigationLayout.NavigationParams params) {
        final BaseFragment fragment = params.fragment;
        if (!(fragment instanceof ChatActivity)) {
            return false;
        }
        if (fragment == approvedFragment) {
            approvedFragment = null;
            return false;
        }
        final long dialogId = getDialogId(fragment);
        if (dialogId == 0 || !needsUnlock(fragment.getCurrentAccount(), dialogId)) {
            return false;
        }
        if (params.preview) {
            return true; // sin vistas previas de chats bloqueados
        }
        authenticate(getString(R.string.NekoModLockPromptTitle), false, ok -> {
            if (ok) {
                markUnlocked(dialogId);
                approvedFragment = fragment;
                if (!layout.presentFragment(params)) {
                    approvedFragment = null;
                }
            }
        });
        return true;
    }

    /** Mismo cálculo que ChatActivity: user_id / chat_id / enc_id. 0 si no se puede determinar. */
    public static long getDialogId(BaseFragment fragment) {
        Bundle args = fragment != null ? fragment.getArguments() : null;
        if (args == null) {
            return 0;
        }
        long userId = args.getLong("user_id", 0);
        long chatId = args.getLong("chat_id", 0);
        int encId = args.getInt("enc_id", 0);
        if (userId != 0) {
            return userId;
        } else if (chatId != 0) {
            return -chatId;
        } else if (encId != 0) {
            return DialogObject.makeEncryptedDialogId(encId);
        }
        return 0;
    }

    // ----------------------------------------------------------- autenticación

    public interface ResultCallback {
        void onResult(boolean success);
    }

    /** Autenticadores disponibles: huella/rostro (débil o fuerte) + PIN/patrón del teléfono. */
    private static int pickAuthenticators(FragmentActivity activity) {
        BiometricManager manager = BiometricManager.from(activity);
        int withCredential = BiometricManager.Authenticators.BIOMETRIC_WEAK | BiometricManager.Authenticators.DEVICE_CREDENTIAL;
        if (manager.canAuthenticate(withCredential) == BiometricManager.BIOMETRIC_SUCCESS) {
            return withCredential;
        }
        if (manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS) {
            return BiometricManager.Authenticators.BIOMETRIC_WEAK;
        }
        return 0;
    }

    /**
     * Pide huella o PIN/patrón del teléfono.
     * @param allowIfUnavailable si el dispositivo no tiene ningún método, dar éxito (true) o fallar (false).
     */
    public static void authenticate(String title, boolean allowIfUnavailable, final ResultCallback callback) {
        final FragmentActivity activity = LaunchActivity.instance;
        if (activity == null) {
            callback.onResult(allowIfUnavailable);
            return;
        }
        try {
            final int authenticators = pickAuthenticators(activity);
            if (authenticators == 0) {
                if (allowIfUnavailable) {
                    callback.onResult(true);
                } else {
                    Toast.makeText(activity, getString(R.string.NekoModLockNoAuth), Toast.LENGTH_LONG).show();
                    callback.onResult(false);
                }
                return;
            }
            final boolean[] done = new boolean[1];
            BiometricPrompt prompt = new BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), new BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                    if (done[0]) return;
                    done[0] = true;
                    callback.onResult(false);
                }

                @Override
                public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                    if (done[0]) return;
                    done[0] = true;
                    callback.onResult(true);
                }
            });
            BiometricPrompt.PromptInfo.Builder info = new BiometricPrompt.PromptInfo.Builder()
                    .setTitle(title)
                    .setAllowedAuthenticators(authenticators);
            if ((authenticators & BiometricManager.Authenticators.DEVICE_CREDENTIAL) == 0) {
                info.setNegativeButtonText(getString(R.string.NekoModLockCancel)); // obligatorio sin DEVICE_CREDENTIAL
            }
            prompt.authenticate(info.build());
        } catch (Exception e) {
            FileLog.e(e);
            callback.onResult(allowIfUnavailable);
        }
    }

    /** Verdadero si el dispositivo puede autenticar con alguna variante (para el autodiagnóstico). */
    public static boolean canAuthenticate(android.content.Context context) {
        BiometricManager manager = BiometricManager.from(context);
        int withCredential = BiometricManager.Authenticators.BIOMETRIC_WEAK | BiometricManager.Authenticators.DEVICE_CREDENTIAL;
        return manager.canAuthenticate(withCredential) == BiometricManager.BIOMETRIC_SUCCESS
                || manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS;
    }
}
