package com.nasari.gbaia

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import java.io.File

/**
 * Fondos de pantalla: uno para toda la app, uno por consola y uno por juego.
 * Se usa el más específico: juego › consola › app.
 */
object Wallpapers {
    enum class Source(val label: String) { GAME("Fondo del juego"), SYSTEM("Fondo de la consola"), APP("Fondo de la app"), NONE("Sin fondo") }

    private fun dir(c: Context) = File(c.filesDir, "fondos").apply { mkdirs() }
    fun appFile(c: Context) = File(dir(c), "app.img")
    fun systemFile(c: Context, sys: String) = File(dir(c), "consola_$sys.img")
    fun gameFile(c: Context, g: Game) = File(dir(c), "juego_${Covers.hash(g.path)}.img")
    fun gameFileFor(c: Context, path: String) = File(dir(c), "juego_${Covers.hash(path)}.img")

    /** Fondo al azar de la carpeta elegida (se decide una vez por apertura del launcher). */
    private var randomPick: File? = null
    private var randomFrom: String? = null

    fun pickRandom(c: Context) {
        val folder = LauncherPrefs.randomFolder(c)
        randomFrom = folder
        randomPick = folder?.let { File(it).listFiles() }
            ?.filter { it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") }
            ?.randomOrNull()
    }

    fun resolve(c: Context, sys: String?, game: Game?): Pair<File?, Source> {
        if (game != null) gameFile(c, game).takeIf { it.exists() }?.let { return it to Source.GAME }
        if (sys != null) systemFile(c, sys).takeIf { it.exists() }?.let { return it to Source.SYSTEM }
        if (randomFrom != LauncherPrefs.randomFolder(c)) pickRandom(c)
        randomPick?.takeIf { it.exists() }?.let { return it to Source.APP }
        appFile(c).takeIf { it.exists() }?.let { return it to Source.APP }
        return null to Source.NONE
    }

    /** Copia la imagen elegida en la galería como fondo. */
    fun setFrom(c: Context, uri: Uri, target: File): Boolean = try {
        c.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
        cache.evictAll()
        target.exists()
    } catch (_: Exception) {
        false
    }

    fun setBitmap(target: File, bmp: Bitmap): Boolean = try {
        target.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        cache.evictAll()
        true
    } catch (_: Exception) {
        false
    }

    fun remove(target: File) {
        target.delete()
        cache.evictAll()
    }

    private val cache = object : android.util.LruCache<String, Bitmap>(64 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun load(f: File, maxSide: Int): Bitmap? {
        val key = f.absolutePath + f.lastModified()
        cache.get(key)?.let { return it }
        val b = Covers.decode(f, maxSide) ?: return null
        cache.put(key, b)
        return b
    }
}
