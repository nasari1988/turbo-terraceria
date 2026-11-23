package com.nasari.gbaia

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.util.zip.ZipInputStream

object Roms {
    fun displayName(context: Context, uri: Uri): String {
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0)?.let { name -> return name } }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "juego"
    }

    /** Nombre seguro para archivos de partida, sin extensión. */
    fun safeName(name: String): String =
        name.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9 ._()-]"), "_").ifBlank { "juego" }

    /** Lee la ROM. Si viene en .zip, toma el primer .gba de adentro. */
    fun read(context: Context, uri: Uri): ByteArray {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("sin acceso al archivo")
        if (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            ZipInputStream(bytes.inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory && entry.name.lowercase().endsWith(".gba")) return zip.readBytes()
                }
            }
            error("el .zip no contiene ningún .gba")
        }
        return bytes
    }
}

/** Juegos abiertos recientemente (para la pantalla de inicio). */
object Recents {
    private const val KEY = "recientes"
    private const val MAX = 30

    data class Item(val uri: Uri, val name: String)

    fun list(context: Context): List<Item> =
        prefs(context).getString(KEY, "")!!.lines().filter { '\t' in it }.map {
            val (u, n) = it.split('\t', limit = 2)
            Item(Uri.parse(u), n)
        }

    fun add(context: Context, uri: Uri, name: String) {
        val items = listOf(Item(uri, name)) + list(context).filter { it.uri != uri }
        prefs(context).edit()
            .putString(KEY, items.take(MAX).joinToString("\n") { "${it.uri}\t${it.name.replace('\n', ' ')}" })
            .apply()
    }

    fun remove(context: Context, uri: Uri) {
        val items = list(context).filter { it.uri != uri }
        prefs(context).edit().putString(KEY, items.joinToString("\n") { "${it.uri}\t${it.name}" }).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("gbaia", Context.MODE_PRIVATE)
}
