package com.nasari.gbaia

import android.content.Context

/** Ajustes guardados de la app. */
object Settings {
    /** Modos IA (coinciden con GBA_SCALE_AI y GBA_SCALE_AI_LITE en el código nativo). */
    const val AI = 100
    const val AI_LITE = 101
    /** IA v2 calculada en la NPU Hexagon (solo en Kotlin; el núcleo va en modo 0). */
    const val NPU = 110
    /** GPU a 8x en las capas Mode 7 + IA en la NPU para el pixel art. */
    const val HYBRID = 120

    /** IA, original exacto (CPU) y resolución interna en la GPU. */
    val SCALES = listOf(HYBRID, NPU, AI, AI_LITE, 0, 2, 4, 6, 8)

    /** Por defecto el híbrido automático (8x solo con Mode 7, IA en NPU en el resto). */
    fun scale(context: Context): Int = prefs(context).getInt("escala", HYBRID)

    /** Modelos de NPU que ya fallaron en este celular: no se vuelven a probar (arranque rápido). */
    fun npuFailed(context: Context): Set<String> = prefs(context).getStringSet("npu_fallidos", emptySet())!!.toSet()

    fun addNpuFailed(context: Context, models: Collection<String>) {
        if (models.isEmpty()) return
        prefs(context).edit().putStringSet("npu_fallidos", npuFailed(context) + models).apply()
    }

    fun showFps(context: Context): Boolean = prefs(context).getBoolean("fps", false)

    fun setShowFps(context: Context, on: Boolean) = prefs(context).edit().putBoolean("fps", on).apply()

    /** Híbrido: teñir de rosa las zonas que van a 8x (para revisar la máscara). */
    fun showMask(context: Context): Boolean = prefs(context).getBoolean("mascara", false)

    fun setShowMask(context: Context, on: Boolean) = prefs(context).edit().putBoolean("mascara", on).apply()

    fun setScale(context: Context, scale: Int) = prefs(context).edit().putInt("escala", scale).apply()

    fun scaleLabel(scale: Int): String = when (scale) {
        AI -> "IA 4x v2 (mejor calidad)"
        AI_LITE -> "IA 4x ligera (v1)"
        NPU -> "IA 4x en NPU"
        HYBRID -> "Híbrido: 8x en Mode 7 + IA en NPU (recomendada)"
        0 -> "Original (exacto, CPU)"
        8 -> "8x · 1920×1280 (máxima)"
        else -> "${scale}x · ${240 * scale}×${160 * scale}"
    }

    private fun prefs(context: Context) = context.getSharedPreferences("gbaia", Context.MODE_PRIVATE)
}
