package tw.nekomimi.nekogram.nekomod.ocr;

import android.content.Context;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;

/**
 * NekoMod: autodiagnóstico del OCR. No hace reconocimiento real.
 */
public final class OcrSelfTest {

    private OcrSelfTest() {
    }

    public static String[] run(Context ctx) {
        try {
            Class.forName("com.google.mlkit.vision.text.TextRecognition");
        } catch (Throwable t) {
            return new String[]{"FALLA", "ML Kit text-recognition no está en el APK: " + t};
        }
        try {
            int code = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(ctx);
            if (code == ConnectionResult.SUCCESS) {
                return new String[]{"OK", "ML Kit y Google Play services disponibles"};
            }
            return new String[]{"AVISO", "Play services no disponible (código " + code + "); el OCR podría no funcionar"};
        } catch (Throwable t) {
            return new String[]{"AVISO", "No se pudo consultar Play services: " + t};
        }
    }
}
