package tw.nekomimi.nekogram.nekomod.lock;

import android.content.Context;

import androidx.biometric.BiometricManager;

import java.util.HashSet;
import java.util.Set;

import tw.nekomimi.nekogram.NekoModConfig;

/**
 * NekoMod: autodiagnóstico del candado de chats.
 */
public final class LockSelfTest {

    private LockSelfTest() {
    }

    /** @return {"OK"|"AVISO"|"FALLA", detalle} */
    public static String[] run(Context ctx) {
        // 1. parseIds / joinIds ida y vuelta
        try {
            Set<Long> in = new HashSet<>();
            in.add(1L);
            in.add(-1002345678901L);
            in.add(42L);
            Set<Long> out = NekoModConfig.parseIds(NekoModConfig.joinIds(in));
            if (!in.equals(out)) {
                return new String[]{"FALLA", "parseIds/joinIds no devuelven los mismos ids"};
            }
            if (!NekoModConfig.parseIds("").isEmpty() || !NekoModConfig.parseIds(null).isEmpty()) {
                return new String[]{"FALLA", "parseIds no maneja la cadena vacía"};
            }
        } catch (Throwable t) {
            return new String[]{"FALLA", "parseIds/joinIds lanzó " + t};
        }

        // 2. el dispositivo puede autenticar
        try {
            BiometricManager manager = BiometricManager.from(ctx);
            int withCredential = BiometricManager.Authenticators.BIOMETRIC_WEAK | BiometricManager.Authenticators.DEVICE_CREDENTIAL;
            int r = manager.canAuthenticate(withCredential);
            if (r == BiometricManager.BIOMETRIC_SUCCESS) {
                return new String[]{"OK", "huella/rostro o PIN del teléfono disponible"};
            }
            int weak = manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK);
            if (weak == BiometricManager.BIOMETRIC_SUCCESS) {
                return new String[]{"OK", "solo biometría disponible (sin PIN del teléfono como respaldo)"};
            }
            String why;
            switch (r) {
                case BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED:
                    why = "no hay huella ni bloqueo de pantalla configurado";
                    break;
                case BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE:
                    why = "sin hardware biométrico";
                    break;
                case BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE:
                    why = "hardware biométrico no disponible ahora";
                    break;
                default:
                    why = "código " + r;
            }
            return new String[]{"AVISO", "el dispositivo no puede autenticar: " + why + ". Los chats bloqueados no se podrán abrir"};
        } catch (Throwable t) {
            return new String[]{"FALLA", "BiometricManager lanzó " + t};
        }
    }
}
