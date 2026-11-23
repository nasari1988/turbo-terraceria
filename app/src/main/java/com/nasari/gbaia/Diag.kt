package com.nasari.gbaia

import android.content.Context
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Registro corto de eventos y reporte de diagnóstico para enviar a Claude. */
object Diag {
    private val log = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile var glInfo = "sin datos"
    @Volatile var npuStatus = "no probada"
    @Volatile var lastStats = "sin datos"
    @Volatile var usageSources = "sin datos"

    @Synchronized
    fun log(msg: String) {
        log.addLast("${fmt.format(Date())} $msg")
        while (log.size > 80) log.removeFirst()
    }

    /** Líneas del logcat de esta app relacionadas con la NPU (una app puede leer su propio log). */
    private fun ownLogcat(): List<String> = try {
        val p = ProcessBuilder("logcat", "-d", "-v", "brief", "--pid=" + android.os.Process.myPid())
            .redirectErrorStream(true).start()
        val lines = p.inputStream.bufferedReader().readLines()
        p.waitFor()
        val keys = listOf("qnn", "htp", "tflite", "delegate", "dsp", "rpc", "skel", "litert", "hexagon")
        lines.filter { l -> keys.any { l.contains(it, ignoreCase = true) } }.takeLast(60)
            .ifEmpty { listOf("(sin líneas relevantes)") }
    } catch (e: Throwable) {
        listOf("no se pudo leer logcat: ${e.message}")
    }

    @Synchronized
    fun report(context: Context, game: String?, mode: String): String {
        val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
        return buildString {
            appendLine("=== Reporte GBA IA ===")
            appendLine("App: ${pkg.versionName} (${if (Build.VERSION.SDK_INT >= 28) pkg.longVersionCode else 0})")
            appendLine("Equipo: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            if (Build.VERSION.SDK_INT >= 31) appendLine("Chip: ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
            appendLine("GPU: $glInfo")
            appendLine("NPU: $npuStatus")
            appendLine("Juego: ${game ?: "-"}")
            appendLine("Modo: $mode")
            appendLine("Rendimiento: $lastStats")
            appendLine("Medidores: $usageSources")
            appendLine("--- Registro ---")
            log.forEach { appendLine(it) }
            appendLine("--- Logcat (NPU/QNN) ---")
            ownLogcat().forEach { appendLine(it) }
        }
    }
}
