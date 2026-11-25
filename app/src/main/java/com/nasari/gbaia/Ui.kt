package com.nasari.gbaia

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.app.AlertDialog
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Colores y piezas de interfaz del launcher (como en las maquetas). */
object Ui {
    val BG = Color.rgb(0x11, 0x13, 0x18)
    val BG_DEEP = Color.rgb(0x0D, 0x0F, 0x13)
    val CARD = Color.rgb(0x19, 0x1C, 0x23)
    val CARD2 = Color.rgb(0x16, 0x19, 0x20)
    val LINE = Color.rgb(0x2A, 0x2E, 0x37)
    val TEXT = Color.rgb(0xEC, 0xEE, 0xF2)
    val TEXT2 = Color.rgb(0xC9, 0xCE, 0xD8)
    val MUTED = Color.rgb(0x9A, 0xA1, 0xB0)
    val OK = Color.rgb(0x3D, 0xDC, 0x97)
    val BAD = Color.rgb(0xFF, 0x9B, 0x8A)
    val ON_ACCENT = Color.rgb(0x1A, 0x13, 0x06)

    val ACCENTS = listOf("#F5A524", "#4FA3FF", "#3DDC97", "#FF6B6B")
    val ACCENT_NAMES = listOf("Ámbar", "Azul", "Verde", "Rojo")

    fun accent(context: Context): Int = Color.parseColor(ACCENTS.getOrElse(LauncherPrefs.accent(context)) { ACCENTS[0] })

    /** Fondo suave del acento (para pestañas y chips seleccionados). */
    fun accentSoft(context: Context): Int {
        val a = accent(context)
        return Color.rgb(
            (Color.red(a) * 0.17 + 0x11 * 0.83).toInt(),
            (Color.green(a) * 0.17 + 0x13 * 0.83).toInt(),
            (Color.blue(a) * 0.17 + 0x18 * 0.83).toInt(),
        )
    }

    fun accentLight(context: Context): Int {
        val a = accent(context)
        return Color.rgb((Color.red(a) + 255) / 2, (Color.green(a) + 255) / 2, (Color.blue(a) + 255) / 2)
    }

    fun dp(context: Context, v: Number): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()

    fun round(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            if (stroke > 0) setStroke(stroke, strokeColor)
        }

    fun ripple(bg: GradientDrawable): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(Color.argb(60, 255, 255, 255)), bg, null)

    fun text(context: Context, s: CharSequence, size: Float, color: Int = TEXT, bold: Boolean = false): TextView =
        TextView(context).apply {
            text = s
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            includeFontPadding = false
        }

    /** Botón: primary = relleno del acento; si no, gris con borde. */
    fun button(context: Context, label: String, primary: Boolean = false, onClick: () -> Unit): TextView {
        val r = dp(context, 12).toFloat()
        return text(context, label, 14f, if (primary) ON_ACCENT else TEXT, bold = primary).apply {
            gravity = Gravity.CENTER
            minHeight = dp(context, 44)
            setPadding(dp(context, 16), 0, dp(context, 16), 0)
            background = ripple(if (primary) round(accent(context), r) else round(CARD, r, dp(context, 1), LINE))
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    /** Fila de ajustes: título, detalle y una vista a la derecha. */
    fun row(context: Context, title: String, detail: String?, right: View?): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 14), dp(context, 10), dp(context, 14), dp(context, 10))
            background = round(CARD2, dp(context, 12).toFloat())
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(context, title, 14f, TEXT, bold = true))
                if (detail != null) addView(text(context, detail, 12f, MUTED).apply { setPadding(0, dp(context, 3), 0, 0) })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (right != null) addView(right)
        }

    fun label(context: Context, s: String): TextView =
        text(context, s.uppercase(), 12f, MUTED).apply {
            letterSpacing = 0.08f
            setPadding(0, dp(context, 8), 0, dp(context, 4))
        }

    /**
     * Menú en cuadrícula de 2 columnas dentro de un AlertDialog (cabe en horizontal sin desplazarse).
     * Se ejecuta primero la acción y luego se cierra el diálogo, para que el onDismiss vea el estado final.
     */
    fun showGridMenu(context: Context, title: String, labels: List<String>, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
        var dlg: AlertDialog? = null
        val grid = GridLayout(context).apply {
            columnCount = 2
            setPadding(dp(context, 8), dp(context, 4), dp(context, 8), dp(context, 4))
        }
        labels.forEachIndexed { i, label ->
            val b = button(context, label) {
                onPick(i)
                dlg?.dismiss()
            }
            b.minHeight = dp(context, 48)
            val p = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f))
            p.width = 0
            p.height = dp(context, 48)
            p.setMargins(dp(context, 4), dp(context, 3), dp(context, 4), dp(context, 3))
            grid.addView(b, p)
        }
        val scroll = ScrollView(context).apply { addView(grid) }
        val d = AlertDialog.Builder(context)
            .setTitle(title)
            .setView(scroll)
            .setOnDismissListener { onDismiss() }
            .create()
        dlg = d
        d.show()
        d.window?.setLayout((context.resources.displayMetrics.widthPixels * 0.7f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
}
