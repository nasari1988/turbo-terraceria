package tw.nekomimi.nekogram.nekomod.ghost;

import tw.nekomimi.nekogram.NekoModConfig;

// NekoMod: autotest del modo fantasma.
public class GhostSelfTest {

    public static String[] run() {
        try {
            if (!NekoModConfig.isGhost()) {
                return new String[]{"AVISO", "apagado"};
            }
            StringBuilder bad = new StringBuilder();
            if (NekoModConfig.ghostNoRead && !GhostGate.shouldBlockRead()) bad.append("lectura ");
            if (NekoModConfig.ghostNoTyping && !GhostGate.shouldBlockTyping()) bad.append("escribiendo ");
            if (NekoModConfig.ghostNoOnline && !GhostGate.shouldBlockOnline()) bad.append("en linea ");
            if (NekoModConfig.ghostNoStories && !GhostGate.shouldBlockStoryView()) bad.append("historias ");
            if (bad.length() > 0) {
                return new String[]{"FALLA", "sin bloqueo: " + bad.toString().trim()};
            }
            int active = 0;
            if (NekoModConfig.ghostNoRead) active++;
            if (NekoModConfig.ghostNoTyping) active++;
            if (NekoModConfig.ghostNoOnline) active++;
            if (NekoModConfig.ghostNoStories) active++;
            if (active == 0) {
                return new String[]{"AVISO", "activo pero sin sub-opciones"};
            }
            return new String[]{"OK", active + " bloqueos activos"};
        } catch (Throwable t) {
            return new String[]{"FALLA", String.valueOf(t)};
        }
    }
}
