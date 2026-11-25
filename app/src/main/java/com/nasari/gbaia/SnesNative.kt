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
    // ---------- IA en NPU (LiteRT + QNN en Kotlin; la GPU arma la imagen) ----------
    /** En el hilo del emulador: tamaño de entrada del modelo (256x224). */
    @JvmStatic external fun npuSetSize(w: Int, h: Int)
    /**
     * Copia el último cuadro (RGBA) a [frame] si mide 256 de ancho y 216 o 224 de alto; devuelve el alto.
     * Devuelve 0 si no sirve para la IA (Mode 7 HD, alta resolución, entrelazado).
     */
    @JvmStatic external fun copyFrameNpu(frame: java.nio.ByteBuffer): Int
    /** Llena la entrada de la NPU con [frame] (de [frameH] filas); las filas que sobran repiten la última. */
    @JvmStatic external fun npuFillInputFrom(input: java.nio.ByteBuffer, type: Int, scale: Float, zp: Int, frame: java.nio.ByteBuffer, frameH: Int)
    /** Arma y presenta la imagen 4x con la salida de la NPU y su cuadro original. */
    @JvmStatic external fun npuPresent(output: java.nio.ByteBuffer, type: Int, scale: Float, zp: Int, frame: java.nio.ByteBuffer, frameH: Int): Boolean
    /** Presenta el último cuadro tal cual, sin correr el núcleo. */
    @JvmStatic external fun presentPlain()
    /** ms de GPU del último armado de la IA (-1 = sin dato). */
    @JvmStatic external fun npuGpuMs(): Float
    @JvmStatic external fun emuMs(): Float
    @JvmStatic external fun sampleRate(): Double
    /** Cuadros por segundo de la consola: ~60.1 (NTSC) o ~50 (PAL, juegos europeos). */
    @JvmStatic external fun fps(): Double
    @JvmStatic external fun saveState(path: String): Boolean
    @JvmStatic external fun loadState(path: String): Boolean
    @JvmStatic external fun saveSram(): Boolean
    @JvmStatic external fun glInfo(): String
}
