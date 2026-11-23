package com.nasari.gbaia

import android.content.Context

/** Ajustes guardados de la app. */
object Settings {
    /** Modos IA (coinciden con GBA_SCALE_AI y GBA_SCALE_AI_LITE en el código nativo). */
    const val AI = 100
    const val AI_LITE = 101
    /** IA v2 calculada en la NPU Hexagon (solo en Kotlin; el núcleo va en modo 0). */
    const val NPU = 110

    /** IA, original exacto (CPU) y resolución interna en la GPU. */
    val SCALES = listOf(AI, NPU, AI_LITE, 0, 2, 4, 6, 8)

    fun scale(context: Context): Int = prefs(context).getInt("escala", AI)

    fun showFps(context: Context): Boolean = prefs(context).getBoolean("fps", false)

    fun setShowFps(context: Context, on: Boolean) = prefs(context).edit().putBoolean("fps", on).apply()

    fun setScale(context: Context, scale: Int) = prefs(context).edit().putInt("escala", scale).apply()

    fun scaleLabel(scale: Int): String = when (scale) {
        AI -> "IA 4x v2 (mejor calidad)"
        AI_LITE -> "IA 4x ligera (v1)"
        NPU -> "IA 4x v2 en NPU (prueba)"
        0 -> "Original (exacto, CPU)"
        8 -> "8x · 1920×1280 (máxima)"
        else -> "${scale}x · ${240 * scale}×${160 * scale}"
    }

    private fun prefs(context: Context) = context.getSharedPreferences("gbaia", Context.MODE_PRIVATE)
}
