package tw.nekomimi.nekogram.nekomod.media;

import org.telegram.ui.ActionBar.ActionBarMenuSlider;

import tw.nekomimi.nekogram.NekoModConfig;

/** NekoMod: autodiagnostico de las funciones de medios. */
public final class MediaSelfTest {

    private MediaSelfTest() {
    }

    /** @return {"OK"|"AVISO"|"FALLA", detalle} */
    public static String[] run() {
        StringBuilder detail = new StringBuilder();
        boolean fail = false;
        boolean warn = false;
        try {
            float max = ActionBarMenuSlider.SpeedSlider.MAX_SPEED;
            if (max >= 3.0f) {
                detail.append("velocidad max ").append(max).append("x");
            } else {
                fail = true;
                detail.append("velocidad max ").append(max).append("x < 3.0");
            }
        } catch (Throwable e) {
            fail = true;
            detail.append("velocidad: ").append(e);
        }
        final long testId = -999999L;
        int previous = -1;
        try {
            previous = NekoModConfig.getAutoDownloadMask(testId);
            NekoModConfig.setAutoDownloadMask(testId, 5);
            int got = NekoModConfig.getAutoDownloadMask(testId);
            if (got != 5) {
                fail = true;
                detail.append("; regla descarga: esperado 5, leido ").append(got);
            } else {
                detail.append("; regla descarga ok");
            }
        } catch (Throwable e) {
            fail = true;
            detail.append("; regla descarga: ").append(e);
        } finally {
            try {
                NekoModConfig.setAutoDownloadMask(testId, previous);
                if (NekoModConfig.getAutoDownloadMask(testId) != previous) {
                    warn = true;
                    detail.append("; no se pudo restaurar la regla de prueba");
                }
            } catch (Throwable e) {
                warn = true;
            }
        }
        return new String[]{fail ? "FALLA" : warn ? "AVISO" : "OK", detail.toString()};
    }
}
