package tw.nekomimi.nekogram.nekomod.content;

import tw.nekomimi.nekogram.NekoModConfig;

// NekoMod: puertas de contenido protegido y de un solo uso. Los ganchos y el autotest usan estas mismas funciones.
public class ContentGate {

    /** true si hay que ignorar las restricciones noforwards (copiar/guardar/captura). */
    public static boolean ignoreNoForwards() {
        return NekoModConfig.allowSaveProtected;
    }

    /** true si hay que guardar en galeria el medio de un solo uso al abrirlo. */
    public static boolean shouldSaveViewOnce() {
        return NekoModConfig.saveViewOnce;
    }
}
