package com.nasari.gbaia

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.hypot

/** Controles táctiles semitransparentes con multitáctil y cruceta de 8 direcciones. */
class ControlsView(context: Context, private val snes: Boolean = false) : View(context) {
    // Bits de cada botón según la consola
    private val kUp = if (snes) SnesNative.UP else Native.UP
    private val kDown = if (snes) SnesNative.DOWN else Native.DOWN
    private val kLeft = if (snes) SnesNative.LEFT else Native.LEFT
    private val kRight = if (snes) SnesNative.RIGHT else Native.RIGHT

    @Volatile var keys = 0
        private set

    private class Btn(val key: Int, val label: String) {
        var cx = 0f
        var cy = 0f
        var r = 0f
        val rect = RectF() // para L, R, Start, Select
        var round = true
    }

    private val a = Btn(if (snes) SnesNative.A else Native.A, "A")
    private val b = Btn(if (snes) SnesNative.B else Native.B, "B")
    private val bx = Btn(SnesNative.X, "X")
    private val by = Btn(SnesNative.Y, "Y")
    private val l = Btn(if (snes) SnesNative.L else Native.L, "L").apply { round = false }
    private val r = Btn(if (snes) SnesNative.R else Native.R, "R").apply { round = false }
    private val select = Btn(if (snes) SnesNative.SELECT else Native.SELECT, "SELECT").apply { round = false }
    private val start = Btn(if (snes) SnesNative.START else Native.START, "START").apply { round = false }
    private val buttons = if (snes) listOf(a, b, bx, by, l, r, select, start) else listOf(a, b, l, r, select, start)

    private var padX = 0f
    private var padY = 0f
    private var padR = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(140, 255, 255, 255)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 255, 255, 255)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val u = h / 9f
        stroke.strokeWidth = u * 0.04f
        padR = u * 1.5f
        padX = u * 0.4f + padR
        padY = h - u * 0.5f - padR

        if (snes) {
            // Rombo como en el control de SNES: X arriba, A derecha, B abajo, Y izquierda
            val cx = w - u * 2.3f
            val cy = h - u * 2.6f
            val d = u * 1.15f
            for (btn in listOf(a, b, bx, by)) btn.r = u * 0.6f
            bx.cx = cx; bx.cy = cy - d
            a.cx = cx + d; a.cy = cy
            b.cx = cx; b.cy = cy + d
            by.cx = cx - d; by.cy = cy
        } else {
            a.r = u * 0.72f; a.cx = w - u * 1.2f; a.cy = h - u * 3.0f
            b.r = u * 0.72f; b.cx = w - u * 2.9f; b.cy = h - u * 1.9f
        }

        l.rect.set(u * 0.3f, u * 0.3f, u * 2.9f, u * 1.2f)
        r.rect.set(w - u * 2.9f, u * 0.3f, w - u * 0.3f, u * 1.2f)
        select.rect.set(w / 2f - u * 2.4f, h - u * 0.85f, w / 2f - u * 0.4f, h - u * 0.2f)
        start.rect.set(w / 2f + u * 0.4f, h - u * 0.85f, w / 2f + u * 2.4f, h - u * 0.2f)
    }

    private fun hit(btn: Btn, x: Float, y: Float): Boolean =
        if (btn.round) hypot(x - btn.cx, y - btn.cy) < btn.r * 1.3f
        else {
            val m = btn.rect.height() * 0.3f
            x > btn.rect.left - m && x < btn.rect.right + m && y > btn.rect.top - m && y < btn.rect.bottom + m
        }

    private fun pad(x: Float, y: Float): Int {
        val dx = x - padX
        val dy = y - padY
        val d = hypot(dx, dy)
        if (d > padR * 1.5f || d < padR * 0.2f) return 0
        // 8 sectores de 45°
        val ang = Math.toDegrees(atan2(-dy, dx).toDouble()).let { if (it < 0) it + 360 else it }
        return when (((ang + 22.5) / 45).toInt() % 8) {
            0 -> kRight
            1 -> kRight or kUp
            2 -> kUp
            3 -> kLeft or kUp
            4 -> kLeft
            5 -> kLeft or kDown
            6 -> kDown
            else -> kRight or kDown
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        var k = 0
        val up = e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL
        if (!up) {
            for (i in 0 until e.pointerCount) {
                if (e.actionMasked == MotionEvent.ACTION_POINTER_UP && i == e.actionIndex) continue
                val x = e.getX(i)
                val y = e.getY(i)
                k = k or pad(x, y)
                for (btn in buttons) if (hit(btn, x, y)) k = k or btn.key
            }
        }
        if (k != keys) {
            if ((k and keys.inv()) != 0) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            keys = k
            invalidate()
        }
        return true
    }

    fun clear() {
        keys = 0
        invalidate()
    }

    private fun color(pressed: Boolean) =
        if (pressed) Color.argb(120, 255, 255, 255) else Color.argb(45, 255, 255, 255)

    override fun onDraw(c: Canvas) {
        val k = keys
        // Cruceta
        fill.color = color(false)
        c.drawCircle(padX, padY, padR, fill)
        c.drawCircle(padX, padY, padR, stroke)
        val arm = padR * 0.62f
        val dirs = listOf(
            Triple(kUp, 0f, -arm), Triple(kDown, 0f, arm),
            Triple(kLeft, -arm, 0f), Triple(kRight, arm, 0f),
        )
        for ((key, dx, dy) in dirs) {
            fill.color = color((k and key) != 0)
            c.drawCircle(padX + dx, padY + dy, padR * 0.3f, fill)
        }
        // Botones
        for (btn in buttons) {
            fill.color = color((k and btn.key) != 0)
            if (btn.round) {
                c.drawCircle(btn.cx, btn.cy, btn.r, fill)
                c.drawCircle(btn.cx, btn.cy, btn.r, stroke)
                text.textSize = btn.r * 0.8f
                c.drawText(btn.label, btn.cx, btn.cy + text.textSize * 0.35f, text)
            } else {
                val rr = btn.rect.height() / 2
                c.drawRoundRect(btn.rect, rr, rr, fill)
                c.drawRoundRect(btn.rect, rr, rr, stroke)
                text.textSize = btn.rect.height() * (if (btn.label.length > 1) 0.42f else 0.6f)
                c.drawText(btn.label, btn.rect.centerX(), btn.rect.centerY() + text.textSize * 0.35f, text)
            }
        }
    }
}
