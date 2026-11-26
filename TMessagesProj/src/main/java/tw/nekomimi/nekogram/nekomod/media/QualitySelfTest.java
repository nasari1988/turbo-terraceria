package tw.nekomimi.nekogram.nekomod.media;

import org.telegram.ui.ActionBar.ActionBarMenuSlider;

import tw.nekomimi.nekogram.NekoModConfig;

/** NekoMod: autodiagnostico de la seleccion de calidad. */
public final class QualitySelfTest {

    private QualitySelfTest() {
    }

    /** @return {"OK"|"AVISO"|"FALLA", detalle} */
    public static String[] run() {
        StringBuilder detail = new StringBuilder();
        boolean fail = false;
        try {
            int[] a = {360, 720, 1080};
            int h = QualityPicker.pick(a, NekoModConfig.QUALITY_HIGH);
            int l = QualityPicker.pick(a, NekoModConfig.QUALITY_LOW);
            int m = QualityPicker.pick(a, NekoModConfig.QUALITY_MEDIUM);
            if (h != 2 || l != 0 || m != 1) {
                fail = true;
                detail.append("3 calidades: H=").append(h).append(" L=").append(l).append(" M=").append(m).append(" (esperado 2,0,1)");
            } else {
                detail.append("3 calidades ok");
            }
            int[] b = {480, 1080};
            int m2 = QualityPicker.pick(b, NekoModConfig.QUALITY_MEDIUM);
            if (m2 != 0) {
                fail = true;
                detail.append("; 2 calidades M=").append(m2).append(" (esperado 0)");
            } else {
                detail.append("; 2 calidades ok");
            }
            if (QualityPicker.pick(a, NekoModConfig.QUALITY_AUTO) != -1) {
                fail = true;
                detail.append("; AUTO no devuelve -1");
            }
        } catch (Throwable e) {
            fail = true;
            detail.append("picker: ").append(e);
        }
        try {
            float max = ActionBarMenuSlider.SpeedSlider.MAX_SPEED;
            if (max >= 3.0f) {
                detail.append("; velocidad max ").append(max).append("x");
            } else {
                fail = true;
                detail.append("; velocidad max ").append(max).append("x < 3.0");
            }
        } catch (Throwable e) {
            fail = true;
            detail.append("; velocidad: ").append(e);
        }
        return new String[]{fail ? "FALLA" : "OK", detail.toString()};
    }
}
