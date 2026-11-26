package tw.nekomimi.nekogram.nekomod.content;

import tw.nekomimi.nekogram.NekoModConfig;

// NekoMod: autotest de contenido protegido y de un solo uso. Devuelve {"OK"|"AVISO"|"FALLA", detalle}.
public class ContentSelfTest {

    public static String[] run() {
        try {
            if (!NekoModConfig.allowSaveProtected && !NekoModConfig.saveViewOnce) {
                return new String[]{"AVISO", "Contenido protegido y guardado de un solo uso estan apagados"};
            }
            if (ContentGate.ignoreNoForwards() != NekoModConfig.allowSaveProtected) {
                return new String[]{"FALLA", "ContentGate.ignoreNoForwards no coincide con allowSaveProtected"};
            }
            if (ContentGate.shouldSaveViewOnce() != NekoModConfig.saveViewOnce) {
                return new String[]{"FALLA", "ContentGate.shouldSaveViewOnce no coincide con saveViewOnce"};
            }
            // Con la opcion activa, los ayudantes centrales no deben reportar restriccion.
            if (NekoModConfig.allowSaveProtected) {
                org.telegram.tgnet.TLRPC.TL_channel chat = new org.telegram.tgnet.TLRPC.TL_channel();
                chat.noforwards = true;
                org.telegram.messenger.MessagesController mc = org.telegram.messenger.MessagesController.getInstance(org.telegram.messenger.UserConfig.selectedAccount);
                if (mc.isChatNoForwards(chat)) {
                    return new String[]{"FALLA", "isChatNoForwards sigue devolviendo true"};
                }
            }
            if (!NekoModConfig.allowSaveProtected) {
                return new String[]{"AVISO", "Contenido protegido apagado; guardado de un solo uso activo"};
            }
            if (!NekoModConfig.saveViewOnce) {
                return new String[]{"AVISO", "Contenido protegido activo; guardado de un solo uso apagado"};
            }
            return new String[]{"OK", "Contenido protegido y un solo uso activos (el reenvio puede ser rechazado por el servidor)"};
        } catch (Throwable e) {
            return new String[]{"FALLA", String.valueOf(e)};
        }
    }
}
