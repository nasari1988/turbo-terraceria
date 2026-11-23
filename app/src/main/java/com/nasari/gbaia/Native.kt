package com.nasari.gbaia

import android.view.Surface

/** Puente con el núcleo de mGBA (app/src/main/cpp). */
object Native {
    init {
        System.loadLibrary("gbaia")
    }

    const val WIDTH = 240
    const val HEIGHT = 160
    const val AUDIO_RATE = 48000

    // Bits de botones del GBA
    const val A = 1 shl 0
    const val B = 1 shl 1
    const val SELECT = 1 shl 2
    const val START = 1 shl 3
    const val RIGHT = 1 shl 4
    const val LEFT = 1 shl 5
    const val UP = 1 shl 6
    const val DOWN = 1 shl 7
    const val R = 1 shl 8
    const val L = 1 shl 9

    /** Desde el hilo de la interfaz: la superficie donde se dibuja (o null). */
    @JvmStatic external fun setSurface(surface: Surface?)

    // ----- Solo desde el hilo del emulador -----
    /** Crea el contexto de GPU. Devuelve false si no hay GLES3 (se usa el respaldo con Canvas). */
    @JvmStatic external fun start(): Boolean
    @JvmStatic external fun stop()
    /** scale: 0 = original exacto (CPU), 1-8 = GPU a esa resolución. */
    @JvmStatic external fun load(rom: ByteArray, savePath: String?, scale: Int): Boolean
    @JvmStatic external fun idle()
    /** Corre un cuadro (y lo dibuja si present). Devuelve cuadros de audio estéreo. */
    @JvmStatic external fun runFrame(keys: Int, audio: ShortArray, present: Boolean): Int
    @JvmStatic external fun copyPixels(pixels: IntArray)
    @JvmStatic external fun setScale(scale: Int)
    @JvmStatic external fun getScale(): Int
    /** [ms de GPU de la IA (-1 = sin dato), ms de CPU del núcleo por cuadro]. */
    @JvmStatic external fun stats(): FloatArray
    /** Modo NPU: llena la entrada de la NPU con el cuadro actual (float [160,240,3]). */
    @JvmStatic external fun npuFillInput(input: java.nio.ByteBuffer)
    /** Modo NPU: compone la salida de la NPU en la GPU y la muestra. */
    @JvmStatic external fun npuPresent(output: java.nio.ByteBuffer): Boolean
    /** GPU, versión y extensiones (para el reporte). */
    @JvmStatic external fun glInfo(): String
    @JvmStatic external fun saveState(path: String): Boolean
    @JvmStatic external fun loadState(path: String): Boolean
}
