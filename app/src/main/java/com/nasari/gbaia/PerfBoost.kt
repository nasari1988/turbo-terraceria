package com.nasari.gbaia

import android.content.Context
import android.os.PerformanceHintManager
import android.os.Process
import java.io.File

/**
 * Pide al sistema que el hilo del emulador vaya en los núcleos rápidos:
 * prioridad alta + PerformanceHint (el sistema ajusta núcleo y frecuencia según
 * cuánto tarda cada cuadro frente a lo que debería tardar).
 */
class PerfBoost(context: Context, targetFps: Double) {
    private val tid = Process.myTid()
    private val session: PerformanceHintManager.Session?

    init {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        } catch (_: Exception) {
        }
        val target = (1e9 / targetFps).toLong()
        session = try {
            context.getSystemService(PerformanceHintManager::class.java)?.createHintSession(intArrayOf(tid), target)
        } catch (_: Exception) {
            null
        }
    }

    val active get() = session != null

    /** Cuánto tardó el trabajo del cuadro (sin contar la espera del audio). */
    fun report(workNs: Long) {
        try {
            session?.reportActualWorkDuration(workNs.coerceAtLeast(1))
        } catch (_: Exception) {
        }
    }

    fun close() {
        try {
            session?.close()
        } catch (_: Exception) {
        }
    }

    /** "CPU 7 · 3.2 GHz": en qué núcleo va el hilo y a qué frecuencia (para el reporte). */
    fun where(): String = try {
        val stat = File("/proc/self/task/$tid/stat").readText()
        val fields = stat.substringAfterLast(')').trim().split(' ')
        val cpu = fields[36].toInt()  // campo 39 de stat (processor)
        val khz = File("/sys/devices/system/cpu/cpu$cpu/cpufreq/scaling_cur_freq").takeIf { it.canRead() }?.readText()?.trim()?.toLongOrNull()
        if (khz != null) "CPU %d · %.1f GHz".format(cpu, khz / 1e6) else "CPU $cpu"
    } catch (_: Exception) {
        "CPU ?"
    }
}
