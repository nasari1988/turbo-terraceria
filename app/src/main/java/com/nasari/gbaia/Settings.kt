package com.nasari.gbaia

import android.content.Context

/** Ajustes guardados de la app. */
object Settings {
    /** Modo IA (coincide con GBA_SCALE_AI en el código nativo). */
    const val AI = 100

    /** IA, original exacto (CPU) y resolución interna en la GPU. */
    val SCALES = listOf(AI, 0, 2, 4, 6, 8)

    fun scale(context: Context): Int = prefs(context).getInt("escala", AI)

    fun showFps(context: Context): Boolean = prefs(context).getBoolean("fps", false)

    fun setShowFps(context: Context, on: Boolean) = prefs(context).edit().putBoolean("fps", on).apply()

    fun setScale(context: Context, scale: Int) = prefs(context).edit().putInt("escala", scale).apply()

    fun scaleLabel(scale: Int): String = when (scale) {
        AI -> "IA 4x (mejora de imagen)"
        0 -> "Original (exacto, CPU)"
        8 -> "8x · 1920×1280 (máxima)"
        else -> "${scale}x · ${240 * scale}×${160 * scale}"
    }

    private fun prefs(context: Context) = context.getSharedPreferences("gbaia", Context.MODE_PRIVATE)
}
