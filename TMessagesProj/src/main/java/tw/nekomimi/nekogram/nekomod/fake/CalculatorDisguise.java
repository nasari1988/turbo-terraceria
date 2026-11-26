package tw.nekomimi.nekogram.nekomod.fake;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import org.telegram.ui.LauncherIconController;

import tw.nekomimi.nekogram.NekoModConfig;

// NekoMod: activa/desactiva el ícono de calculadora (nunca toca DiagnosticLauncher)
public class CalculatorDisguise {

    public static ComponentName aliasComponent(Context ctx) {
        return new ComponentName(ctx.getPackageName(), "tw.nekomimi.nekogram.nekomod.fake.CalculatorAlias");
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences("nekomodconfig", Context.MODE_PRIVATE);
    }

    public static void apply(Context ctx, boolean enable) {
        PackageManager pm = ctx.getPackageManager();
        LauncherIconController.LauncherIcon[] icons = LauncherIconController.LauncherIcon.values();
        if (enable) {
            // recordar el ícono activo para restaurarlo
            LauncherIconController.LauncherIcon active = LauncherIconController.LauncherIcon.DEFAULT;
            for (LauncherIconController.LauncherIcon i : icons) {
                if (LauncherIconController.isEnabled(i)) {
                    active = i;
                    break;
                }
            }
            prefs(ctx).edit().putString("calcPrevIcon", active.name()).apply();
            // primero se activa la calculadora para no quedar sin ícono
            pm.setComponentEnabledSetting(aliasComponent(ctx), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
            for (LauncherIconController.LauncherIcon i : icons) {
                pm.setComponentEnabledSetting(i.getComponentName(ctx), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
            }
        } else {
            LauncherIconController.LauncherIcon restore = LauncherIconController.LauncherIcon.DEFAULT;
            String saved = prefs(ctx).getString("calcPrevIcon", "DEFAULT");
            for (LauncherIconController.LauncherIcon i : icons) {
                if (i.name().equals(saved)) {
                    restore = i;
                    break;
                }
            }
            pm.setComponentEnabledSetting(restore.getComponentName(ctx), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
            pm.setComponentEnabledSetting(aliasComponent(ctx), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
            for (LauncherIconController.LauncherIcon i : icons) {
                if (i != restore) {
                    pm.setComponentEnabledSetting(i.getComponentName(ctx), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
                }
            }
        }
        NekoModConfig.calculatorDisguise = enable;
        NekoModConfig.setBoolean("calculatorDisguise", enable);
    }

    /** Guarda el código de acceso (solo dígitos). Vacío = sin código (pulsación larga en "="). */
    public static void setCode(Context ctx, String digits) {
        String hash = (digits == null || digits.length() == 0) ? "" : NekoModConfig.sha256(digits);
        NekoModConfig.setString("calcCodeHash", hash);
    }
}
