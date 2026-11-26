package tw.nekomimi.nekogram.nekomod.media;

import tw.nekomimi.nekogram.NekoModConfig;

/** NekoMod: eleccion pura de calidad de reproduccion segun la preferencia. */
public final class QualityPicker {

    private QualityPicker() {
    }

    /**
     * @param heights alturas (p) de cada calidad disponible, en cualquier orden
     * @param preference NekoModConfig.QUALITY_*
     * @return indice elegido en heights, o -1 para AUTO / sin datos
     */
    public static int pick(int[] heights, int preference) {
        if (heights == null || heights.length == 0 || preference == NekoModConfig.QUALITY_AUTO) {
            return -1;
        }
        int maxI = 0;
        int minI = 0;
        for (int i = 1; i < heights.length; i++) {
            if (heights[i] > heights[maxI]) maxI = i;
            if (heights[i] < heights[minI]) minI = i;
        }
        if (preference == NekoModConfig.QUALITY_HIGH) {
            return maxI;
        }
        if (preference == NekoModConfig.QUALITY_LOW) {
            return minI;
        }
        if (preference == NekoModConfig.QUALITY_MEDIUM) {
            if (heights.length <= 2) {
                return minI;
            }
            int best = -1;
            int bestDiff = Integer.MAX_VALUE;
            for (int i = 0; i < heights.length; i++) {
                if (i == maxI || i == minI) continue;
                int diff = Math.abs(heights[i] - 720);
                if (diff < bestDiff) {
                    bestDiff = diff;
                    best = i;
                }
            }
            return best >= 0 ? best : minI;
        }
        return -1;
    }
}
