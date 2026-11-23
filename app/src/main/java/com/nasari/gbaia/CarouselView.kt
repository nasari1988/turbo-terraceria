package com.nasari.gbaia

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.roundToInt

/** Carrusel grande: el elemento del centro va grande y con borde; los vecinos más chicos y tenues. */
class CarouselView(context: Context) : View(context) {
    class Item(
        val label: String,
        val sub: String = "",
        val color: Int = Ui.CARD,
        val bitmap: () -> Bitmap? = { null },
        val badge: Boolean = false,
    )

    var items: List<Item> = emptyList()
        set(v) {
            field = v
            if (pos > (v.size - 1).coerceAtLeast(0)) pos = (v.size - 1).coerceAtLeast(0).toFloat()
            invalidate()
        }

    /** Tamaño de la tarjeta central en dp; square = carátula cuadrada. */
    var cardW = 300f
    var cardH = 210f
    var accent = Color.WHITE
    var onSelected: (Int) -> Unit = {}
    var onActivate: (Int) -> Unit = {}

    private var pos = 0f
    private var anim: ValueAnimator? = null
    private var lastSelected = -1

    val selected: Int get() = pos.roundToInt().coerceIn(0, (items.size - 1).coerceAtLeast(0))

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.TEXT
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.MUTED; textAlign = Paint.Align.CENTER }
    private val star = Path()

    private val spacing get() = cardW * density * 0.78f

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            anim?.cancel()
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            pos = (pos + dx / spacing).coerceIn(-0.4f, items.size - 0.6f)
            invalidate()
            notifySelected()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            val target = (pos - vx / spacing * 0.25f).roundToInt()
            scrollTo(target)
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val d = (e.x - width / 2f) / spacing
            val idx = (pos + d).roundToInt()
            if (abs(e.x - width / 2f) < cardW * density / 2 && selected == pos.roundToInt()) onActivate(selected)
            else scrollTo(idx)
            return true
        }
    })

    init {
        isFocusable = true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (anim?.isRunning != true) scrollTo(pos.roundToInt())
        }
        return handled || super.onTouchEvent(event)
    }

    fun scrollTo(index: Int, animate: Boolean = true) {
        if (items.isEmpty()) return
        val t = index.coerceIn(0, items.size - 1).toFloat()
        anim?.cancel()
        if (!animate) {
            pos = t
            invalidate()
            notifySelected()
            return
        }
        anim = ValueAnimator.ofFloat(pos, t).apply {
            duration = (180 + 60 * abs(t - pos)).toLong().coerceAtMost(450)
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                pos = it.animatedValue as Float
                invalidate()
                notifySelected()
            }
            start()
        }
    }

    fun move(delta: Int) = scrollTo(selected + delta)

    private fun notifySelected() {
        val s = selected
        if (s != lastSelected) {
            lastSelected = s
            onSelected(s)
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (items.isEmpty()) return
        val cx = width / 2f
        val cy = height / 2f
        val first = (pos - 3).toInt().coerceAtLeast(0)
        val last = (pos + 3).toInt().coerceAtMost(items.size - 1)
        // Primero los lejanos, el del centro al final (encima)
        val order = (first..last).sortedByDescending { abs(it - pos) }
        for (i in order) {
            val d = i - pos
            val ad = abs(d)
            if (ad > 2.6f) continue
            val scale = (1f - 0.42f * ad.coerceAtMost(1f) - 0.1f * (ad - 1f).coerceIn(0f, 1.6f))
            val w = cardW * density * scale
            val h = cardH * density * scale
            // Los vecinos se juntan un poco al centro
            val x = cx + d * spacing * (1f - 0.12f * (ad - 1f).coerceIn(0f, 1.5f))
            val r = RectF(x - w / 2, cy - h / 2, x + w / 2, cy + h / 2)
            val alpha = (255 * (1f - 0.5f * ad.coerceAtMost(1.6f))).toInt().coerceIn(40, 255)
            drawCard(canvas, items[i], r, alpha, ad < 0.5f, scale)
        }
    }

    private fun drawCard(canvas: Canvas, item: Item, r: RectF, alpha: Int, center: Boolean, scale: Float) {
        val radius = 18f * density * scale
        fill.color = item.color
        fill.alpha = alpha
        canvas.drawRoundRect(r, radius, radius, fill)
        val bmp = item.bitmap()
        if (bmp != null) {
            // Centrado y recortado a la tarjeta
            val save = canvas.save()
            val clip = Path().apply { addRoundRect(r, radius, radius, Path.Direction.CW) }
            canvas.clipPath(clip)
            val sr = bmp.width.toFloat() / bmp.height
            val dr = r.width() / r.height()
            val src = if (sr > dr) {
                val w = (bmp.height * dr).toInt()
                Rect((bmp.width - w) / 2, 0, (bmp.width + w) / 2, bmp.height)
            } else {
                val h = (bmp.width / dr).toInt()
                Rect(0, (bmp.height - h) / 2, bmp.width, (bmp.height + h) / 2)
            }
            bmpPaint.alpha = alpha
            canvas.drawBitmap(bmp, src, r, bmpPaint)
            canvas.restoreToCount(save)
        } else {
            textPaint.alpha = alpha
            textPaint.textSize = (if (item.label.length > 5) 30f else 56f) * density * scale
            val fm = textPaint.fontMetrics
            val ty = r.centerY() - (fm.ascent + fm.descent) / 2 - if (item.sub.isNotEmpty()) 10f * density * scale else 0f
            canvas.drawText(item.label, r.centerX(), ty, textPaint)
            if (item.sub.isNotEmpty()) {
                subPaint.alpha = alpha
                subPaint.textSize = 14f * density * scale
                canvas.drawText(item.sub, r.centerX(), ty + 26f * density * scale, subPaint)
            }
        }
        if (center) {
            stroke.color = accent
            stroke.strokeWidth = 3f * density
            canvas.drawRoundRect(r, radius, radius, stroke)
        }
        if (item.badge) {
            // Estrella de favorito arriba a la derecha
            val s = 11f * density * scale
            val sx = r.right - s * 1.8f
            val sy = r.top + s * 1.8f
            star.reset()
            for (k in 0 until 10) {
                val ang = Math.PI / 5 * k - Math.PI / 2
                val rad = if (k % 2 == 0) s else s * 0.45f
                val px = (sx + rad * Math.cos(ang)).toFloat()
                val py = (sy + rad * Math.sin(ang)).toFloat()
                if (k == 0) star.moveTo(px, py) else star.lineTo(px, py)
            }
            star.close()
            fill.color = Color.rgb(0xFF, 0xD5, 0x8A)
            fill.alpha = alpha
            canvas.drawPath(star, fill)
        }
    }
}
