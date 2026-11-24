package com.nasari.gbaia

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * Superficie del juego. Normalmente dibuja la GPU desde el código nativo (EGL).
 * Si el celular no tuviera GLES3, se usa [drawFallback] con Canvas.
 */
class GameView(
    context: Context,
    /** A quién se entrega la superficie (GBA por defecto; el SNES usa la suya). */
    private val onSurface: (android.view.Surface?) -> Unit = { Native.setSurface(it) },
) : SurfaceView(context), SurfaceHolder.Callback {
    /** true cuando el nativo dibuja con GPU; entonces Canvas no debe tocar la superficie. */
    @Volatile var gpu = true

    @Volatile private var ready = false
    private val paint = Paint().apply { isFilterBitmap = false }
    private val dst = Rect()
    private val lock = Any()

    init {
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        ready = true
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        val scale = maxOf(1, minOf(width / Native.WIDTH, height / Native.HEIGHT))
        val w = Native.WIDTH * scale
        val h = Native.HEIGHT * scale
        val x = (width - w) / 2
        val y = (height - h) / 2
        synchronized(dst) { dst.set(x, y, x + w, y + h) }
        if (gpu) onSurface(holder.surface)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (gpu) onSurface(null)
        synchronized(lock) { ready = false }
    }

    /** Respaldo sin GPU. Se llama desde el hilo del emulador. */
    fun drawFallback(frame: Bitmap) {
        synchronized(lock) {
            if (!ready) return
            try {
                val canvas = holder.lockHardwareCanvas() ?: return
                canvas.drawColor(Color.BLACK)
                synchronized(dst) { canvas.drawBitmap(frame, null, dst, paint) }
                holder.unlockCanvasAndPost(canvas)
            } catch (e: Exception) {
                // la superficie se destruyó a medio dibujo; se ignora este cuadro
            }
        }
    }
}
