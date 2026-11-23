package com.nasari.gbaia

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * Dibuja la pantalla del GBA con escalado entero (en el Poco F7: 8x = 1920x1280)
 * y píxeles nítidos. Aquí entrará después el escalado con IA por capas.
 */
class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {
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
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        synchronized(lock) { ready = false }
    }

    /** Se llama desde el hilo del emulador. */
    fun draw(frame: Bitmap) {
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
