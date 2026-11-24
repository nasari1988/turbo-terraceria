package com.nasari.gbaia

import android.view.Surface

/** Puente al emulador de SNES (bsnes-hd con Mode 7 en HD) en libsnesia.so. */
object SnesNative {
    init {
        System.loadLibrary("snesia")
    }

    // Botones en el orden de libretro
    const val B = 1 shl 0
    const val Y = 1 shl 1
    const val SELECT = 1 shl 2
    const val START = 1 shl 3
    const val UP = 1 shl 4
    const val DOWN = 1 shl 5
    const val LEFT = 1 shl 6
    const val RIGHT = 1 shl 7
    const val A = 1 shl 8
    const val X = 1 shl 9
    const val L = 1 shl 10
    const val R = 1 shl 11

    @JvmStatic external fun setSurface(surface: Surface?)
    /** En el hilo del emulador: prepara la GPU. Devuelve si hay GLES3. */
    @JvmStatic external fun start(systemDir: String, saveDir: String): Boolean
    @JvmStatic external fun stop()
    /** Opción del núcleo, p. ej. ("bsnes_mode7_scale", "4x"); se puede cambiar jugando. */
    @JvmStatic external fun setOption(key: String, value: String)
    @JvmStatic external fun load(romPath: String, sramPath: String): Boolean
    @JvmStatic external fun idle()
    /** Un cuadro; devuelve cuántos cuadros de audio estéreo dejó en [audio]. */
    @JvmStatic external fun runFrame(keys: Int, audio: ShortArray, draw: Boolean): Int
    /** [ancho, alto] del último cuadro (con Mode 7 HD sale más grande). */
    @JvmStatic external fun frameSize(): IntArray
    /** [ancho, alto, píxeles ARGB…] del último cuadro. */
    @JvmStatic external fun copyFrame(): IntArray?
    @JvmStatic external fun emuMs(): Float
    @JvmStatic external fun sampleRate(): Double
    /** Cuadros por segundo de la consola: ~60.1 (NTSC) o ~50 (PAL, juegos europeos). */
    @JvmStatic external fun fps(): Double
    @JvmStatic external fun saveState(path: String): Boolean
    @JvmStatic external fun loadState(path: String): Boolean
    @JvmStatic external fun saveSram(): Boolean
    @JvmStatic external fun glInfo(): String
}
