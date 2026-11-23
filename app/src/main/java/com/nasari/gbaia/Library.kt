package com.nasari.gbaia

import android.content.Context
import java.io.File

data class Game(val path: String, val system: String) {
    val file get() = File(path)
    val fileName: String get() = path.substringAfterLast('/')
    /** Nombre del archivo sin extensión (para buscar carátula). */
    val rawName: String get() = fileName.substringBeforeLast('.')
    /** Nombre bonito: sin extensión, etiquetas [..] ni (..), guiones bajos como espacios. */
    val title: String
        get() = rawName.replace('_', ' ')
            .replace(Regex("\\s*\\[[^]]*]"), "")
            .replace(Regex("\\s*\\([^)]*\\)"), "")
            .replace(Regex("\\s+"), " ").trim()
            .ifBlank { rawName }
}

/** Busca los juegos en la carpeta elegida y los acomoda por consola. */
object Library {
    @Volatile private var cache: List<Game>? = null

    private fun cacheFile(context: Context) = File(context.filesDir, "biblioteca.tsv")

    fun games(context: Context): List<Game> {
        cache?.let { return it }
        val f = cacheFile(context)
        val list = if (f.exists()) f.readLines().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size == 2) Game(parts[1], parts[0]) else null
        } else emptyList()
        cache = list
        return list
    }

    fun bySystem(context: Context): Map<String, List<Game>> =
        games(context).groupBy { it.system }.mapValues { (_, v) -> v.sortedBy { it.title.lowercase() } }

    /** Recorre la carpeta (hasta 5 niveles). Corre fuera del hilo principal. */
    fun scan(context: Context): List<Game> {
        val root = LauncherPrefs.romRoot(context)?.let { File(it) }
        val found = ArrayList<Game>()
        if (root != null && root.isDirectory) walk(root, Systems.forFolder(root.name), 0, found)
        val list = found.distinctBy { it.path }
        cacheFile(context).writeText(list.joinToString("\n") { "${it.system}\t${it.path}" })
        cache = list
        return list
    }

    private fun walk(dir: File, sys: GameSystem?, depth: Int, out: MutableList<Game>) {
        val children = dir.listFiles() ?: return
        val names = children.filter { it.isFile }.map { it.name.lowercase() }.toSet()
        val hasM3u = names.any { it.endsWith(".m3u") }
        for (c in children.sortedBy { it.name.lowercase() }) {
            if (c.name.startsWith(".")) continue
            if (c.isDirectory) {
                if (depth < 5) walk(c, Systems.forFolder(c.name) ?: sys, depth + 1, out)
                continue
            }
            val ext = c.name.substringAfterLast('.', "").lowercase()
            if (ext.isEmpty()) continue
            val s = when {
                sys != null && (ext in sys.exts || ext in sys.folderOnlyExts) -> sys
                else -> Systems.forExtension(ext)
            } ?: continue
            // Juegos de disco: el .cue manda sobre los .bin; el .m3u manda sobre los discos
            if (ext == "bin" && names.any { it.endsWith(".cue") }) continue
            if (hasM3u && (ext == "cue" || ext == "chd")) continue
            out += Game(c.absolutePath, s.id)
        }
    }
}

/** Ajustes del launcher. */
object LauncherPrefs {
    private fun p(c: Context) = c.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    fun romRoot(c: Context): String? = p(c).getString("raiz", null)
    fun setRomRoot(c: Context, path: String) = p(c).edit().putString("raiz", path).apply()

    fun accent(c: Context): Int = p(c).getInt("acento", 0)
    fun setAccent(c: Context, i: Int) = p(c).edit().putInt("acento", i).apply()

    /** Oscurecer el fondo, 0–90 %. */
    fun dim(c: Context): Int = p(c).getInt("oscurecer", 55)
    fun setDim(c: Context, v: Int) = p(c).edit().putInt("oscurecer", v).apply()

    /** Desenfoque del fondo: 0 nada, 1 bajo, 2 medio, 3 alto. */
    fun blur(c: Context): Int = p(c).getInt("desenfoque", 1)
    fun setBlur(c: Context, v: Int) = p(c).edit().putInt("desenfoque", v).apply()

    /** Carpeta para el fondo al azar (vacío = apagado). */
    fun randomFolder(c: Context): String? = p(c).getString("fondo_azar", null)
    fun setRandomFolder(c: Context, path: String?) = p(c).edit().putString("fondo_azar", path).apply()

    /** Índice del emulador elegido para una consola (-1 = el primero instalado). */
    fun launcherIndex(c: Context, sys: String): Int = p(c).getInt("emu_$sys", -1)
    fun setLauncherIndex(c: Context, sys: String, i: Int) = p(c).edit().putInt("emu_$sys", i).apply()

    fun lastSystem(c: Context): String? = p(c).getString("ultima_consola", null)
    fun setLastSystem(c: Context, id: String) = p(c).edit().putString("ultima_consola", id).apply()

    fun coversAuto(c: Context): Boolean = p(c).getBoolean("caratulas_auto", true)
    fun setCoversAuto(c: Context, on: Boolean) = p(c).edit().putBoolean("caratulas_auto", on).apply()
}

/** Datos de cada juego: última vez, tiempo jugado, favorito, modo de imagen. */
object GameDb {
    private fun p(c: Context) = c.getSharedPreferences("juegos", Context.MODE_PRIVATE)

    fun lastPlayed(c: Context, path: String): Long = p(c).getLong("ultima|$path", 0)
    fun playTime(c: Context, path: String): Long = p(c).getLong("tiempo|$path", 0)
    fun isFavorite(c: Context, path: String): Boolean = p(c).getBoolean("fav|$path", false)
    fun setFavorite(c: Context, path: String, on: Boolean) = p(c).edit().putBoolean("fav|$path", on).apply()

    /** Modo de imagen solo para este juego (null = el de la app). */
    fun mode(c: Context, path: String): Int? = p(c).getInt("modo|$path", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
    fun setMode(c: Context, path: String, mode: Int?) =
        p(c).edit().apply { if (mode == null) remove("modo|$path") else putInt("modo|$path", mode) }.apply()

    fun markLaunched(c: Context, path: String) = p(c).edit().putLong("ultima|$path", System.currentTimeMillis()).apply()

    fun addPlayTime(c: Context, path: String, ms: Long) {
        if (ms <= 0) return
        p(c).edit().putLong("tiempo|$path", playTime(c, path) + ms).putLong("ultima|$path", System.currentTimeMillis()).apply()
    }

    fun whenText(t: Long): String {
        if (t == 0L) return "Nunca"
        val now = System.currentTimeMillis()
        val cal = java.util.Calendar.getInstance()
        val today = cal.apply { timeInMillis = now; set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0) }.timeInMillis
        val hm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(t))
        return when {
            t >= today -> "Hoy, $hm"
            t >= today - 86_400_000L -> "Ayer, $hm"
            now - t < 7 * 86_400_000L -> "Hace ${(now - t) / 86_400_000L + 1} días"
            else -> java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale("es")).format(java.util.Date(t))
        }
    }

    fun durationText(ms: Long): String {
        if (ms < 60_000) return if (ms == 0L) "—" else "< 1 min"
        val m = ms / 60_000
        return if (m < 60) "$m min" else "${m / 60} h ${"%02d".format(m % 60)} min"
    }
}
