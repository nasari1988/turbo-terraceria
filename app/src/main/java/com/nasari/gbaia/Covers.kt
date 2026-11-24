package com.nasari.gbaia

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.Normalizer
import java.util.concurrent.Executors

/**
 * Carátulas: las propias (de tu galería) mandan; si no, se bajan de la base libre de libretro
 * (libretro-thumbnails, la misma que usan RetroArch y ES-DE) buscando el nombre más parecido.
 */
object Covers {
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val pending = HashSet<String>()
    private val retryAfter = HashMap<String, Long>()
    /** Último estado de la descarga, para mostrarlo en Ajustes › Carátulas. */
    @Volatile var status: String = "Aún no se ha bajado nada en esta sesión"
    @Volatile private var lastError: String? = null
    private val indices = HashMap<String, List<String>>()

    private val bitmaps = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun hash(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(20)

    private fun dir(c: Context, sub: String) = File(c.filesDir, "caratulas/$sub").apply { mkdirs() }
    fun ownFile(c: Context, g: Game) = File(dir(c, "propias"), hash(g.path) + ".png")
    private fun dlFile(c: Context, g: Game) = File(dir(c, g.system), hash(g.path) + ".png")
    private fun missFile(c: Context, g: Game) = File(dir(c, g.system), hash(g.path) + ".sin")

    fun file(c: Context, g: Game): File? = ownFile(c, g).takeIf { it.exists() } ?: dlFile(c, g).takeIf { it.exists() }

    /** Bitmap en memoria si ya está cargado; si no, lo carga (y baja) en segundo plano y avisa. */
    fun get(c: Context, g: Game, onReady: () -> Unit): Bitmap? {
        val f = file(c, g)
        val key = f?.let { it.absolutePath + it.lastModified() }
        if (key != null) bitmaps.get(key)?.let { return it }
        val job = "${g.path}|${key ?: "bajar"}"
        // Sin red o sin carátula: no reintentar en cada cuadro del carrusel (1 vez por minuto)
        val now = System.currentTimeMillis()
        synchronized(pending) {
            if ((retryAfter[job] ?: 0L) > now) return null
            if (!pending.add(job)) return null
        }
        exec.execute {
            var ok = false
            try {
                var file = f
                if (file == null && LauncherPrefs.coversAuto(c) && !missFile(c, g).exists()) file = download(c, g)
                if (file != null) {
                    decode(file, 512)?.let { bitmaps.put(file.absolutePath + file.lastModified(), it); ok = true; main.post(onReady) }
                }
            } finally {
                synchronized(pending) {
                    pending.remove(job)
                    if (!ok) retryAfter[job] = System.currentTimeMillis() + 60_000L
                }
            }
        }
        return null
    }

    fun forget(c: Context, g: Game) {
        dlFile(c, g).delete()
        missFile(c, g).delete()
        bitmaps.evictAll()
    }

    fun decode(f: File, max: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        var s = 1
        while (o.outWidth / (s * 2) >= max && o.outHeight / (s * 2) >= max) s *= 2
        return BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = s })
    }

    /** Baja todas las que falten (desde Ajustes). Devuelve cuántas consiguió. */
    fun downloadAll(c: Context, games: List<Game>, progress: (Int, Int) -> Unit): Int {
        var ok = 0
        games.forEachIndexed { i, g ->
            if (file(c, g) == null && !missFile(c, g).exists() && download(c, g) != null) ok++
            progress(i + 1, games.size)
        }
        return ok
    }

    // ---------- libretro-thumbnails ----------

    private fun download(c: Context, g: Game): File? {
        val sys = Systems.byId(g.system) ?: return null
        val names = index(c, sys) ?: return null  // sin red: se vuelve a intentar después
        val best = bestMatch(g.rawName, names)
        if (best == null) {
            missFile(c, g).writeText("")
            status = "Sin carátula parecida para: ${g.title}"
            return null
        }
        val repo = sys.thumbs.replace(' ', '_')
        val urls = listOf(
            "https://thumbnails.libretro.com/${enc(sys.thumbs)}/Named_Boxarts/${enc(best)}.png",
            "https://raw.githubusercontent.com/libretro-thumbnails/$repo/master/Named_Boxarts/${enc(best)}.png",
        )
        for (u in urls) {
            val bytes = fetch(u) ?: continue
            val out = dlFile(c, g)
            out.writeBytes(bytes)
            status = "Bajada: $best"
            return out
        }
        return null
    }

    private fun index(c: Context, sys: GameSystem): List<String>? {
        synchronized(indices) { indices[sys.id]?.let { return it } }
        val cached = File(c.cacheDir, "indice_${sys.id}.txt")
        if (cached.exists() && System.currentTimeMillis() - cached.lastModified() < 30L * 86_400_000L) {
            val l = cached.readLines().filter { it.isNotBlank() }
            synchronized(indices) { indices[sys.id] = l }
            return l
        }
        var names: List<String>? = null
        // 1) listado del servidor de libretro
        fetch("https://thumbnails.libretro.com/${enc(sys.thumbs)}/Named_Boxarts/")?.let { html ->
            names = Regex("href=\"([^\"]+)\\.png\"").findAll(String(html)).map {
                URLDecoder.decode(it.groupValues[1].replace("+", "%2B"), "UTF-8").substringAfterLast('/')
            }.filter { it.isNotBlank() }.distinct().toList().takeIf { it.isNotEmpty() }
        }
        // 2) respaldo: el repositorio en GitHub
        if (names == null) {
            val repo = sys.thumbs.replace(' ', '_')
            fetch("https://api.github.com/repos/libretro-thumbnails/$repo/git/trees/master?recursive=1")?.let { json ->
                names = Regex("\"path\"\\s*:\\s*\"Named_Boxarts/([^\"]+)\\.png\"").findAll(String(json))
                    .map { it.groupValues[1].replace("\\u0026", "&").replace("\\\"", "\"") }.toList().takeIf { it.isNotEmpty() }
            }
        }
        val l = names ?: run {
            status = "No pude bajar la lista de ${sys.short} (${lastError ?: "sin conexión"})"
            return null
        }
        status = "Lista de ${sys.short}: ${l.size} carátulas"
        cached.writeText(l.joinToString("\n"))
        synchronized(indices) { indices[sys.id] = l }
        return l
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun fetch(url: String): ByteArray? = try {
        (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = 8000
            readTimeout = 15000
            setRequestProperty("User-Agent", "GBA-IA-Launcher")
            try {
                if (responseCode == 200) inputStream.use { it.readBytes() } else { lastError = "HTTP $responseCode"; null }
            } finally {
                disconnect()
            }
        }
    } catch (e: Exception) {
        lastError = e.javaClass.simpleName + (e.message?.let { ": " + it.take(60) } ?: "")
        null
    }

    fun normalize(s: String): String {
        val noTags = s.replace(Regex("\\[[^]]*]"), " ").replace(Regex("\\([^)]*\\)"), " ")
        val ascii = Normalizer.normalize(noTags, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        return ascii.lowercase().replace("&", " and ").replace(Regex("[^a-z0-9]+"), " ")
            .split(' ').filter { it.isNotBlank() && it != "the" }.joinToString(" ")
    }

    /** El nombre de la base más parecido al del archivo (null si nada se parece lo suficiente). */
    fun bestMatch(raw: String, names: List<String>): String? {
        val want = normalize(raw)
        val wantTokens = want.split(' ').toSet()
        if (wantTokens.isEmpty()) return null
        val region = raw.lowercase()
        var best: String? = null
        var bestScore = 0.0
        for (n in names) {
            val norm = normalize(n)
            var score = if (norm == want) 2.0 else {
                val t = norm.split(' ').toSet()
                2.0 * t.intersect(wantTokens).size / (t.size + wantTokens.size)
            }
            val nl = n.lowercase()
            // Preferir la misma región del archivo; si no dice, USA/Europa
            if ("spain" in region || "espa" in region) { if ("spain" in nl) score += 0.04 }
            if ("europe" in region && "europe" in nl) score += 0.03
            if ("usa" in region && "usa" in nl) score += 0.03
            if ("usa" in nl) score += 0.01
            if ("beta" in nl || "proto" in nl || "demo" in nl) score -= 0.05
            if (score > bestScore) {
                bestScore = score
                best = n
            }
        }
        return if (bestScore >= 0.62) best else null
    }
}
