package com.nasari.gbaia

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

    @JvmStatic external fun load(rom: ByteArray, savePath: String?): Boolean
    @JvmStatic external fun unload()
    /** Corre un cuadro. Devuelve cuántos cuadros de audio estéreo dejó en [audio]. */
    @JvmStatic external fun runFrame(keys: Int, pixels: IntArray, audio: ShortArray): Int
    @JvmStatic external fun saveState(path: String): Boolean
    @JvmStatic external fun loadState(path: String): Boolean
}
