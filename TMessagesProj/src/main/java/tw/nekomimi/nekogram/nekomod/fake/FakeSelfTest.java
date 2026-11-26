package tw.nekomimi.nekogram.nekomod.fake;

import android.content.Context;
import android.content.pm.PackageManager;

import tw.nekomimi.nekogram.NekoModConfig;

// NekoMod: autodiagnóstico de código falso / disfraz de calculadora
public class FakeSelfTest {

    public static String[] run(Context ctx) {
        try {
            String h1 = NekoModConfig.sha256("1234");
            String h2 = NekoModConfig.sha256("1234");
            if (h1 == null || h1.length() != 64 || !h1.equals(h2) || h1.equals(NekoModConfig.sha256("1235"))) {
                return new String[]{"FALLA", "sha256 inestable o vacío"};
            }
        } catch (Throwable t) {
            return new String[]{"FALLA", "sha256: " + t};
        }
        try {
            PackageManager pm = ctx.getPackageManager();
            int state = pm.getComponentEnabledSetting(CalculatorDisguise.aliasComponent(ctx));
            if (NekoModConfig.calculatorDisguise && state != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                return new String[]{"AVISO", "disfraz activo pero el alias no está habilitado (estado " + state + ")"};
            }
            return new String[]{"OK", "alias presente (estado " + state + "), sha256 estable"};
        } catch (Throwable t) {
            return new String[]{"FALLA", "alias de calculadora no existe: " + t};
        }
    }
}
