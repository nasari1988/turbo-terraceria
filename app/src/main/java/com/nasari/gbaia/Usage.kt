package com.nasari.gbaia

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Debug
import android.os.SystemClock
import java.io.File

/**
 * Medidores de uso para el contador y el reporte. Android no deja ver el uso total del sistema
 * a una app normal, así que cada valor indica de dónde sale:
 * - Procesador: % de la app (todos sus hilos) sobre el total de núcleos, leído de /proc/self/stat.
 * - RAM: memoria de la app (PSS) y memoria libre del sistema.
 * - GPU: ocupación real del Adreno si el sistema deja leerla; si no, el tiempo de GPU de la IA.
 * - NPU: tiempo de la NPU por cuadro sobre los 16.7 ms de un cuadro (no hay medidor público).
 */
class Usage(private val context: Context) {
    private val cores = Runtime.getRuntime().availableProcessors()
    private val ticksPerSec = 100.0 // USER_HZ en Android
    private var lastCpuTicks = -1L
    private var lastWallMs = 0L

    /** Rutas conocidas de ocupación del Adreno (pueden estar bloqueadas por SELinux). */
    private val gpuBusyFiles = listOf(
        "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
        "/sys/class/kgsl/kgsl-3d0/gpubusy",
    )
    private var gpuFile: File? = gpuBusyFiles.map(::File).firstOrNull { f -> runCatching { f.readText() }.isSuccess }
    private var gpuBusyPrev: Pair<Long, Long>? = null

    /** % de procesador que usa la app desde la última llamada (0-100 sobre todos los núcleos). */
    fun cpuPercent(): Float {
        val ticks = runCatching {
            val f = File("/proc/self/stat").readText()
            val parts = f.substring(f.lastIndexOf(')') + 2).split(' ')
            parts[11].toLong() + parts[12].toLong() // utime + stime
        }.getOrDefault(-1L)
        val now = SystemClock.elapsedRealtime()
        var pct = -1f
        if (ticks >= 0 && lastCpuTicks >= 0 && now > lastWallMs) {
            val cpuSec = (ticks - lastCpuTicks) / ticksPerSec
            val wallSec = (now - lastWallMs) / 1000.0
            pct = (100.0 * cpuSec / wallSec / cores).toFloat()
        }
        lastCpuTicks = ticks
        lastWallMs = now
        return pct
    }

    /** Ocupación real del Adreno en %, o -1 si el sistema no deja leerla. */
    fun gpuBusyPercent(): Float {
        val f = gpuFile ?: return -1f
        val text = runCatching { f.readText().trim() }.getOrElse { gpuFile = null; return -1f }
        return if (f.name == "gpu_busy_percentage") {
            text.removeSuffix("%").trim().toFloatOrNull() ?: -1f
        } else {
            // gpubusy: "ocupado total" acumulados
            val nums = text.split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }
            if (nums.size < 2) return -1f
            val prev = gpuBusyPrev
            gpuBusyPrev = nums[0] to nums[1]
            if (prev == null || nums[1] <= prev.second) -1f
            else 100f * (nums[0] - prev.first) / (nums[1] - prev.second)
        }
    }

    /** [MB que usa la app, MB libres del sistema, MB totales]. */
    fun ramMb(): IntArray {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val dm = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        return intArrayOf(dm.totalPss / 1024, (mi.availMem / 1048576).toInt(), (mi.totalMem / 1048576).toInt())
    }

    /** Temperatura de la batería en °C (aproxima el calor del celular), o -1. */
    fun batteryTempC(): Float {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return -1f
        val t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return if (t == Int.MIN_VALUE) -1f else t / 10f
    }

    /**
     * Línea para el contador. gpuAiMs: ms de GPU de la IA (-1 si no aplica);
     * npuMs: ms de NPU por cuadro (-1 si no aplica).
     */
    fun line(gpuAiMs: Float, npuMs: Float): String {
        val cpu = cpuPercent()
        val gpu = gpuBusyPercent()
        val ram = ramMb()
        val temp = batteryTempC()
        return buildString {
            append(if (cpu >= 0) "CPU app %.0f%%".format(cpu) else "CPU app ?")
            when {
                gpu >= 0 -> append(" · GPU %.0f%%".format(gpu))
                gpuAiMs >= 0 -> append(" · GPU (est.) %.0f%%".format(100f * gpuAiMs / 16.7f))
                else -> append(" · GPU ?")
            }
            if (npuMs >= 0) append(" · NPU %.0f%%".format(minOf(100f, 100f * npuMs / 16.7f)))
            append(" · RAM app %d MB (libre %.1f/%.1f GB)".format(ram[0], ram[1] / 1024f, ram[2] / 1024f))
            if (temp >= 0) append(" · %.1f °C".format(temp))
        }
    }

    /** Qué medidores son reales y cuáles estimados (para el reporte). */
    fun sources(): String =
        "CPU: /proc/self/stat (solo la app, sobre $cores núcleos) · " +
            "GPU: ${gpuFile?.path ?: "sin acceso al medidor del sistema; se estima con el tiempo de GPU de la IA o de armar la imagen de la NPU (sin contar el dibujo 8x del juego en el híbrido)"} · " +
            "NPU: tiempo por cuadro / 16.7 ms (estimado) · RAM: PSS de la app y memoria libre del sistema · Temp: batería"
}
