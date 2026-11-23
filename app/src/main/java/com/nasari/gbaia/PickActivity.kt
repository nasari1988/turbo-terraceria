package com.nasari.gbaia

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.ImageView
import android.widget.Toast

/** Base de las pantallas del launcher: elegir imágenes y carpetas, pantalla completa. */
abstract class PickActivity : Activity() {
    private var onImage: ((Uri) -> Unit)? = null
    private var onFolder: ((String?) -> Unit)? = null
    private var onFile: ((Uri) -> Unit)? = null

    fun pickImage(cb: (Uri) -> Unit) {
        onImage = cb
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }, 41)
    }

    fun pickFolder(cb: (String?) -> Unit) {
        onFolder = cb
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 42)
    }

    fun pickFile(cb: (Uri) -> Unit) {
        onFile = cb
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }, 43)
    }

    @Deprecated("API de resultados clásica (sin AndroidX)")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            41 -> onImage?.invoke(uri)
            42 -> onFolder?.invoke(treeToPath(uri))
            43 -> {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: SecurityException) {
                }
                onFile?.invoke(uri)
            }
        }
    }

    /** Carpeta elegida (content://…tree/primary:Juegos) a ruta real (/storage/emulated/0/Juegos). */
    private fun treeToPath(uri: Uri): String? {
        val id = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (_: Exception) {
            return null
        }
        val vol = id.substringBefore(':')
        val rel = id.substringAfter(':', "")
        val base = if (vol.equals("primary", true)) Environment.getExternalStorageDirectory().absolutePath else "/storage/$vol"
        return if (rel.isEmpty()) base else "$base/$rel"
    }

    fun hasFilesAccess(): Boolean = Environment.isExternalStorageManager()

    fun askFilesAccess() {
        try {
            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    fun dp(v: Number) = Ui.dp(this, v)

    fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    fun iconButton(icon: Icon.Kind, desc: String, onClick: () -> Unit): ImageView = ImageView(this).apply {
        setImageDrawable(Icon(icon, Ui.TEXT, dp(1).toFloat()))
        contentDescription = desc
        val p = dp(12)
        setPadding(p, p, p, p)
        background = Ui.ripple(Ui.round(Ui.CARD, dp(12).toFloat(), dp(1), Ui.LINE))
        isFocusable = true
        setOnClickListener { onClick() }
    }
}

/** Íconos de línea dibujados a mano (sin recursos ni emojis). */
class Icon(private val kind: Kind, color: Int, private val px: Float) : Drawable() {
    enum class Kind { BACK, NEXT, SEARCH, GEAR, DOTS, PLAY, STAR, FOLDER }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * px
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun getIntrinsicWidth() = (20 * px).toInt()
    override fun getIntrinsicHeight() = (20 * px).toInt()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val s = minOf(b.width(), b.height()) / 24f
        canvas.save()
        canvas.translate(b.left + (b.width() - 24 * s) / 2, b.top + (b.height() - 24 * s) / 2)
        canvas.scale(s, s)
        paint.strokeWidth = 2.2f
        val p = Path()
        when (kind) {
            Kind.BACK -> { p.moveTo(15f, 18f); p.lineTo(9f, 12f); p.lineTo(15f, 6f) }
            Kind.NEXT -> { p.moveTo(9f, 18f); p.lineTo(15f, 12f); p.lineTo(9f, 6f) }
            Kind.SEARCH -> { p.addCircle(11f, 11f, 7f, Path.Direction.CW); p.moveTo(20f, 20f); p.lineTo(16f, 16f) }
            Kind.GEAR -> {
                p.addCircle(12f, 12f, 3.2f, Path.Direction.CW)
                p.addCircle(12f, 12f, 7.2f, Path.Direction.CW)
                for (k in 0 until 8) {
                    val a = Math.PI / 4 * k
                    p.moveTo((12 + 7.2 * Math.cos(a)).toFloat(), (12 + 7.2 * Math.sin(a)).toFloat())
                    p.lineTo((12 + 10 * Math.cos(a)).toFloat(), (12 + 10 * Math.sin(a)).toFloat())
                }
            }
            Kind.DOTS -> {
                paint.style = Paint.Style.FILL
                canvas.drawCircle(5f, 12f, 2f, paint); canvas.drawCircle(12f, 12f, 2f, paint); canvas.drawCircle(19f, 12f, 2f, paint)
                paint.style = Paint.Style.STROKE
            }
            Kind.PLAY -> { paint.style = Paint.Style.FILL; p.moveTo(7f, 4f); p.lineTo(20f, 12f); p.lineTo(7f, 20f); p.close() }
            Kind.STAR -> {
                for (k in 0 until 10) {
                    val a = Math.PI / 5 * k - Math.PI / 2
                    val r = if (k % 2 == 0) 9.5 else 4.2
                    val x = (12 + r * Math.cos(a)).toFloat()
                    val y = (12.5 + r * Math.sin(a)).toFloat()
                    if (k == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                p.close()
            }
            Kind.FOLDER -> {
                p.moveTo(3f, 7f); p.lineTo(9f, 7f); p.lineTo(11f, 9f); p.lineTo(21f, 9f); p.lineTo(21f, 19f); p.lineTo(3f, 19f); p.close()
            }
        }
        canvas.drawPath(p, paint)
        paint.style = Paint.Style.STROKE
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Requerido por Drawable")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
