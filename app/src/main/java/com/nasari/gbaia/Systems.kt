package com.nasari.gbaia

/** Cómo se abre un juego de una consola. */
sealed class Launcher(val label: String) {
    /** Nuestro emulador de GBA. */
    object Internal : Launcher("GBA IA (integrado)")

    /** Nuestro emulador de SNES (bsnes-hd con Mode 7 en HD). */
    object InternalSnes : Launcher("SNES IA (integrado, Mode 7 HD)")

    /** RetroArch con un núcleo: ROM y LIBRETRO como extras, con ruta de archivo. */
    class RetroArch(val core: String, coreLabel: String) : Launcher("RetroArch · $coreLabel") {
        val packages = listOf("com.retroarch.aarch64", "com.retroarch", "com.retroarch.ra32")
    }

    /** Una app con actividad y extra conocidos (como los usa ES-DE). */
    class App(
        label: String,
        val packages: List<String>,
        val activity: String?,      // null: ACTION_VIEW a la app con el archivo como data
        val extra: String?,         // nombre del extra con el archivo (o null si va en data)
        val asUri: Boolean = true,  // true: content://, false: ruta de archivo
    ) : Launcher(label)

    /** Cualquier app que abra el archivo (Android pregunta cuál). */
    object Any : Launcher("Elegir app al abrir")
}

class GameSystem(
    val id: String,
    val name: String,
    val short: String,
    val folders: List<String>,
    val exts: Set<String>,
    /** Carpeta de carátulas en libretro-thumbnails. */
    val thumbs: String,
    val launchers: List<Launcher>,
    /** Extensiones que solo cuentan dentro de su carpeta (zip, iso…). */
    val folderOnlyExts: Set<String> = setOf("zip", "7z"),
)

object Systems {
    val ALL = listOf(
        GameSystem(
            "gba", "Game Boy Advance", "GBA", listOf("gba", "gameboyadvance", "advance"), setOf("gba"),
            "Nintendo - Game Boy Advance",
            listOf(Launcher.Internal, Launcher.RetroArch("mgba", "mGBA")),
        ),
        GameSystem(
            "gbc", "Game Boy Color", "GBC", listOf("gbc", "gameboycolor"), setOf("gbc"),
            "Nintendo - Game Boy Color",
            listOf(Launcher.RetroArch("gambatte", "Gambatte"), Launcher.RetroArch("mgba", "mGBA"), Launcher.Any),
        ),
        GameSystem(
            "gb", "Game Boy", "GB", listOf("gb", "gameboy"), setOf("gb"),
            "Nintendo - Game Boy",
            listOf(Launcher.RetroArch("gambatte", "Gambatte"), Launcher.RetroArch("mgba", "mGBA"), Launcher.Any),
        ),
        GameSystem(
            "nes", "Nintendo (NES)", "NES", listOf("nes", "famicom", "fc"), setOf("nes", "fds"),
            "Nintendo - Nintendo Entertainment System",
            listOf(Launcher.RetroArch("fceumm", "FCEUmm"), Launcher.RetroArch("nestopia", "Nestopia"), Launcher.Any),
        ),
        GameSystem(
            "snes", "Super Nintendo", "SNES", listOf("snes", "sfc", "superfamicom", "supernintendo"), setOf("sfc", "smc"),
            "Nintendo - Super Nintendo Entertainment System",
            listOf(Launcher.InternalSnes, Launcher.RetroArch("snes9x", "Snes9x"), Launcher.RetroArch("bsnes", "bsnes"), Launcher.Any),
        ),
        GameSystem(
            "n64", "Nintendo 64", "N64", listOf("n64", "nintendo64"), setOf("n64", "z64", "v64"),
            "Nintendo - Nintendo 64",
            listOf(
                Launcher.RetroArch("mupen64plus_next_gles3", "Mupen64Plus-Next"),
                Launcher.App("M64Plus FZ", listOf("org.mupen64plusae.v3.fzurita", "org.mupen64plusae.v3.fzurita.pro"), null, null),
                Launcher.Any,
            ),
        ),
        GameSystem(
            "nds", "Nintendo DS", "NDS", listOf("nds", "ds"), setOf("nds"),
            "Nintendo - Nintendo DS",
            listOf(
                Launcher.App("melonDS", listOf("me.magnum.melonds", "me.magnum.melonds.nightly"), "me.magnum.melonds.ui.emulator.EmulatorActivity", "uri"),
                Launcher.RetroArch("melondsds", "melonDS DS"),
                Launcher.Any,
            ),
        ),
        GameSystem(
            "psx", "PlayStation", "PS1", listOf("psx", "ps1", "playstation"), setOf("cue", "chd", "pbp", "m3u"),
            "Sony - PlayStation",
            listOf(
                Launcher.App("DuckStation", listOf("com.github.stenzek.duckstation"), "com.github.stenzek.duckstation.EmulationActivity", "bootPath"),
                Launcher.RetroArch("swanstation", "SwanStation"),
                Launcher.RetroArch("pcsx_rearmed", "PCSX ReARMed"),
                Launcher.Any,
            ),
            folderOnlyExts = setOf("zip", "7z", "iso", "bin", "img"),
        ),
        GameSystem(
            "psp", "PlayStation Portable", "PSP", listOf("psp"), setOf("cso"),
            "Sony - PlayStation Portable",
            listOf(
                Launcher.App("PPSSPP", listOf("org.ppsspp.ppssppgold", "org.ppsspp.ppsspp"), "org.ppsspp.ppsspp.PpssppActivity", null),
                Launcher.RetroArch("ppsspp", "PPSSPP"),
                Launcher.Any,
            ),
            folderOnlyExts = setOf("zip", "7z", "iso", "chd", "pbp"),
        ),
        GameSystem(
            "megadrive", "Mega Drive / Genesis", "MD", listOf("megadrive", "genesis", "md"), setOf("md", "gen", "smd"),
            "Sega - Mega Drive - Genesis",
            listOf(Launcher.RetroArch("genesis_plus_gx", "Genesis Plus GX"), Launcher.RetroArch("picodrive", "PicoDrive"), Launcher.Any),
            folderOnlyExts = setOf("zip", "7z", "bin"),
        ),
    )

    fun byId(id: String): GameSystem? = ALL.firstOrNull { it.id == id }

    /** Consola por nombre de carpeta (sin espacios ni guiones, en minúsculas). */
    fun forFolder(folder: String): GameSystem? {
        val f = folder.lowercase().replace(Regex("[^a-z0-9]"), "")
        return ALL.firstOrNull { f in it.folders }
    }

    /** Consola por extensión, para archivos sueltos fuera de carpetas de consola. */
    fun forExtension(ext: String): GameSystem? = ALL.firstOrNull { ext in it.exts }
}
