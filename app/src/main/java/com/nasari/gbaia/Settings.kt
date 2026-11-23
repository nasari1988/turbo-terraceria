package com.nasari.gbaia

import android.content.Context

/** Ajustes guardados de la app. */
object Settings {
    /** 0 = original exacto (CPU). El resto: resolución interna en la GPU. */
    val SCALES = listOf(0, 2, 4, 6, 8)

    fun scale(context: Context): Int = prefs(context).getInt("escala", 8)

    fun setScale(context: Context, scale: Int) = prefs(context).edit().putInt("escala", scale).apply()

    fun scaleLabel(scale: Int): String = when (scale) {
        0 -> "Original (exacto, CPU)"
        8 -> "8x · 1920×1280 (máxima)"
        else -> "${scale}x · ${240 * scale}×${160 * scale}"
    }

    private fun prefs(context: Context) = context.getSharedPreferences("gbaia", Context.MODE_PRIVATE)
}
