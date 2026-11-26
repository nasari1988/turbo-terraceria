package tw.nekomimi.nekogram.nekomod.ghost;

import tw.nekomimi.nekogram.NekoModConfig;

// NekoMod: puertas del modo fantasma. Los ganchos y el autotest usan estas mismas funciones.
public class GhostGate {

    public static boolean shouldBlockRead() {
        return NekoModConfig.isGhost() && NekoModConfig.ghostNoRead;
    }

    public static boolean shouldBlockTyping() {
        return NekoModConfig.isGhost() && NekoModConfig.ghostNoTyping;
    }

    public static boolean shouldBlockOnline() {
        return NekoModConfig.isGhost() && NekoModConfig.ghostNoOnline;
    }

    public static boolean shouldBlockStoryView() {
        return NekoModConfig.isGhost() && NekoModConfig.ghostNoStories;
    }

    /** true si, al enviar un mensaje, hay que marcar el chat como leido pese al bloqueo. */
    public static boolean shouldReadOnReply() {
        return shouldBlockRead() && NekoModConfig.ghostReadOnReply;
    }
}
