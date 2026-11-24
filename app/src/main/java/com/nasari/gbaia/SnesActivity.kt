package com.nasari.gbaia

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File

/** Emulador de Super Nintendo integrado (bsnes-hd): Mode 7 en HD y pantalla ancha. */
class SnesActivity : Activity() {
    companion object {
        const val EXTRA_PATH = "ruta"
        const val EXTRA_STATE = "estado"

        fun stateFile(c: android.content.Context, fileName: String, slot: String): File {
            val dir = File(c.filesDir, "states_snes").apply { mkdirs() }
            val base = Roms.safeName(fileName)
            return File(dir, if (slot == "auto") "$base.auto" else "$base.ss$slot")
        }

        /** Escala del Mode 7 en HD: "disable", "2x"… "8x". */
        fun mode7(c: android.content.Context): String =
            c.getSharedPreferences("snes", android.content.Context.MODE_PRIVATE).getString("mode7", "3x") ?: "3x"
        fun setMode7(c: android.content.Context, v: String) =
            c.getSharedPreferences("snes", android.content.Context.MODE_PRIVATE).edit().putString("mode7", v).apply()
        /** Pantalla ancha en escenas de Mode 7: "16:9", "none"… */
        fun wide(c: android.content.Context): String =
            c.getSharedPreferences("snes", android.content.Context.MODE_PRIVATE).getString("ancho", "16:9") ?: "16:9"
        fun setWide(c: android.content.Context, v: String) =
            c.getSharedPreferences("snes", android.content.Context.MODE_PRIVATE).edit().putString("ancho", v).apply()

        val MODE7_VALUES = listOf("disable", "2x", "3x", "4x", "6x", "8x")
        val MODE7_LABELS = listOf("Original (sin HD)", "HD 2x", "HD 3x (recomendado)", "HD 4x", "HD 6x (pesado)", "HD 8x (muy pesado)")
    }

    private lateinit var game: GameView
    private lateinit var controls: ControlsView
    private lateinit var fpsView: TextView

    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var fastForward = false
    @Volatile private var padKeys = 0
    @Volatile private var axisKeys = 0
    @Volatile private var loaded = false
    private val tasks = java.util.concurrent.ConcurrentLinkedQueue<() -> Unit>()
    private val usage by lazy { Usage(this) }

    private lateinit var romPath: String
    private lateinit var fileName: String
    private lateinit var gameKey: String
    private var playStart = 0L
    private var playMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES

        game = GameView(this) { SnesNative.setSurface(it) }
        controls = ControlsView(this, snes = true)
        game.setOnTouchListener { _, _ -> showTouchControls(); false }
        fpsView = TextView(this).apply {
            setTextColor(Color.YELLOW)
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
            textSize = 14f
            gravity = Gravity.CENTER
            visibility = if (Settings.showFps(context)) View.VISIBLE else View.GONE
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(game)
            addView(controls)
            addView(fpsView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ))
        })
        hideSystemBars()

        val uri = intent?.data
        val path = intent.getStringExtra(EXTRA_PATH) ?: uri?.path?.takeIf { uri.scheme == "file" }
        if (path == null) {
            // Archivo abierto desde fuera (content://): se copia a caché porque el núcleo necesita una ruta
            if (uri == null) { finish(); return }
            val name = Roms.displayName(this, uri)
            val tmp = File(cacheDir, "snes_" + Roms.safeName(name))
            try {
                contentResolver.openInputStream(uri)?.use { i -> tmp.outputStream().use { i.copyTo(it) } }
            } catch (e: Exception) {
                toast("No se pudo leer el juego"); finish(); return
            }
            romPath = tmp.absolutePath
            fileName = name
            gameKey = uri.toString()
        } else {
            romPath = path
            fileName = File(path).name
            gameKey = path
        }
        playStart = System.currentTimeMillis()
        running = true
        thread = Thread({ emulate() }, "snes").apply { start() }
    }

    private fun hideSystemBars() {
        window.setDecorFitsSystemWindows(false)
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun emulate() {
        val sys = File(filesDir, "snes_bios").apply { mkdirs() }
        val saves = File(filesDir, "saves_snes").apply { mkdirs() }
        val gl = SnesNative.start(sys.absolutePath, saves.absolutePath)
        Diag.glInfo = SnesNative.glInfo()
        Diag.usageSources = usage.sources()
        Diag.log("inicio SNES: $fileName · GPU ${if (gl) "ok" else "no disponible"}")
        try {
            if (!gl) { fail("Este celular no tiene GLES3"); return }
            applyOptions()
            SnesNative.setOption("bsnes_ppu_fast", "ON")
            val sram = File(saves, Roms.safeName(fileName) + ".srm").absolutePath
            if (!SnesNative.load(romPath, sram)) { fail("Este archivo no es un juego de SNES válido"); return }
            loaded = true
            intent.getStringExtra(EXTRA_STATE)?.let { slot ->
                val ok = SnesNative.loadState(stateFile(this, fileName, slot).absolutePath)
                runOnUiThread { toast(if (!ok) "No se pudo cargar la partida" else if (slot == "auto") "Continuando donde te quedaste" else "Ranura $slot cargada") }
            }
            loop()
        } catch (e: Throwable) {
            Diag.log("error: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        } finally {
            if (loaded) SnesNative.saveState(stateFile(this, fileName, "auto").absolutePath)
            SnesNative.stop()
        }
    }

    private fun applyOptions() {
        val m7 = mode7(this)
        SnesNative.setOption("bsnes_mode7_scale", m7)
        val w = wide(this)
        SnesNative.setOption("bsnes_mode7_wsMode", if (w == "none" || m7 == "disable") "none" else "Mode 7")
        SnesNative.setOption("bsnes_mode7_widescreen", w)
    }

    private fun loop() {
        val rate = SnesNative.sampleRate().toInt().takeIf { it > 0 } ?: 48000
        val audio = ShortArray(4096 * 2)
        val track = createAudio(rate)
        track.play()
        var frames = 0
        var fpsStart = System.nanoTime()
        var last = System.nanoTime()
        var slowSeconds = -2
        while (running) {
            val now = System.nanoTime()
            if (now - fpsStart >= 1_000_000_000L) {
                val fps = frames * 1e9 / (now - fpsStart)
                // Respaldo automático: el Mode 7 HD se dibuja en el procesador; si no da 60, se baja un nivel
                slowSeconds = if (!paused && !fastForward && fps < 55) slowSeconds + 1 else minOf(slowSeconds, 0)
                val idx = MODE7_VALUES.indexOf(mode7(this))
                if (slowSeconds >= 3 && idx > 1) {
                    setMode7(this, MODE7_VALUES[idx - 1])
                    applyOptions()
                    val msg = "Iba lento (%.0f FPS): Mode 7 baja a %s".format(fps, MODE7_LABELS[idx - 1])
                    Diag.log(msg)
                    runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
                    slowSeconds = -2
                }
                val size = SnesNative.frameSize()
                val detail = buildString {
                    append("%.1f FPS · SNES · Mode 7 %s".format(fps, MODE7_LABELS[MODE7_VALUES.indexOf(mode7(this@SnesActivity)).coerceAtLeast(0)]))
                    append("\nnúcleo %.1f ms · cuadro %d×%d".format(SnesNative.emuMs(), size[0], size[1]))
                    append("\n")
                    append(usage.line(-1f, -1f))
                }
                if (!paused && frames > 0) Diag.lastStats = detail.replace("\n", " · ")
                runOnUiThread { fpsView.text = detail }
                frames = 0
                fpsStart = now
            }
            while (true) tasks.poll()?.invoke() ?: break
            if (paused) {
                SnesNative.idle()
                Thread.sleep(16)
                last = System.nanoTime()
                continue
            }
            val keys = controls.keys or padKeys or axisKeys
            if (fastForward) {
                SnesNative.runFrame(keys, audio, false)
                SnesNative.runFrame(keys, audio, false)
                SnesNative.runFrame(keys, audio, true)
                frames += 3
                val wait = 16_666_667L - (System.nanoTime() - last)
                if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                last = System.nanoTime()
            } else {
                val n = SnesNative.runFrame(keys, audio, true)
                frames++
                // La escritura bloqueante del audio marca el ritmo (~60.1 cuadros/s)
                if (n > 0) track.write(audio, 0, n * 2)
                last = System.nanoTime()
            }
        }
        track.stop()
        track.release()
    }

    private fun createAudio(rate: Int): AudioTrack {
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(maxOf(min, rate / 60 * 4 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
    }

    private fun fail(msg: String) {
        running = false
        runOnUiThread {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun onEmu(task: () -> String?) {
        tasks.add {
            val msg = task()
            if (msg != null) runOnUiThread { toast(msg) }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ---------- Menú ----------

    @Deprecated("Atrás abre el menú")
    override fun onBackPressed() = showMenu()

    private fun showMenu() {
        paused = true
        var keepPaused = false
        val options = arrayOf(
            "Continuar",
            "Guardar estado",
            "Cargar estado",
            "Mode 7: " + MODE7_LABELS[MODE7_VALUES.indexOf(mode7(this)).coerceAtLeast(0)],
            "Pantalla ancha en Mode 7: " + if (wide(this) == "none") "no" else "sí",
            if (fastForward) "Velocidad normal" else "Avance rápido",
            if (controls.visibility == View.VISIBLE) "Ocultar controles" else "Mostrar controles",
            if (fpsView.visibility == View.VISIBLE) "Ocultar FPS" else "Mostrar FPS",
            "Generar reporte",
            "Usar como fondo del juego",
            "Salir",
        )
        AlertDialog.Builder(this)
            .setTitle(fileName)
            .setItems(options) { _, which ->
                when (which) {
                    1, 2 -> { keepPaused = true; showSlots(save = which == 1) }
                    3 -> {
                        keepPaused = true
                        AlertDialog.Builder(this).setTitle("Mode 7 en HD")
                            .setSingleChoiceItems(MODE7_LABELS.toTypedArray(), MODE7_VALUES.indexOf(mode7(this))) { d, w ->
                                setMode7(this, MODE7_VALUES[w])
                                onEmu { applyOptions(); "Mode 7: ${MODE7_LABELS[w]}" }
                                d.dismiss()
                            }
                            .setOnDismissListener { paused = false; hideSystemBars() }
                            .show()
                    }
                    4 -> {
                        setWide(this, if (wide(this) == "none") "16:9" else "none")
                        onEmu { applyOptions(); if (wide(this) == "none") "Pantalla ancha apagada" else "Pantalla ancha 16:9 en escenas de Mode 7" }
                    }
                    5 -> fastForward = !fastForward
                    6 -> if (controls.visibility == View.VISIBLE) { controls.clear(); controls.visibility = View.GONE } else controls.visibility = View.VISIBLE
                    7 -> {
                        val show = fpsView.visibility != View.VISIBLE
                        fpsView.visibility = if (show) View.VISIBLE else View.GONE
                        Settings.setShowFps(this, show)
                    }
                    8 -> { keepPaused = true; showReport() }
                    9 -> onEmu {
                        val px = SnesNative.copyFrame() ?: return@onEmu "No hay imagen todavía"
                        val w = px[0]
                        val h = px[1]
                        val bmp = Bitmap.createBitmap(px, 2, w, w, h, Bitmap.Config.ARGB_8888)
                        val big = if (w < 1000) Bitmap.createScaledBitmap(bmp, w * 4, h * 4, false) else bmp
                        if (Wallpapers.setBitmap(Wallpapers.gameFileFor(this, gameKey), big)) "Listo: es el fondo de este juego" else "No se pudo guardar el fondo"
                    }
                    10 -> finish()
                }
            }
            .setOnDismissListener { if (!keepPaused) { paused = false; hideSystemBars() } }
            .show()
    }

    private fun showSlots(save: Boolean) {
        val slots = if (save) listOf("1", "2", "3") else listOf("auto", "1", "2", "3")
        val labels = slots.map { slot ->
            val f = stateFile(this, fileName, slot)
            (if (slot == "auto") "Automática" else "Ranura $slot") + " · " + if (f.exists()) GameDb.whenText(f.lastModified()) else "vacía"
        }
        AlertDialog.Builder(this)
            .setTitle(if (save) "Guardar en…" else "Cargar…")
            .setItems(labels.toTypedArray()) { _, w ->
                val f = stateFile(this, fileName, slots[w]).absolutePath
                onEmu {
                    if (save) { if (SnesNative.saveState(f)) "Guardado en ranura ${slots[w]}" else "No se pudo guardar" }
                    else { if (SnesNative.loadState(f)) "Partida cargada" else "Esa ranura está vacía" }
                }
            }
            .setOnDismissListener { paused = false; hideSystemBars() }
            .show()
    }

    private fun showReport() {
        val text = buildString {
            appendLine("=== Reporte SNES IA ===")
            appendLine("Equipo: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}")
            appendLine("Chip: ${android.os.Build.SOC_MODEL}")
            appendLine("GPU: ${Diag.glInfo}")
            appendLine("Juego: $fileName")
            appendLine("Mode 7: ${mode7(this@SnesActivity)} · ancho: ${wide(this@SnesActivity)}")
            appendLine("Rendimiento: ${Diag.lastStats}")
        }
        AlertDialog.Builder(this)
            .setTitle("Reporte")
            .setMessage(text)
            .setPositiveButton("Copiar") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Reporte SNES IA", text))
                toast("Reporte copiado")
            }
            .setNegativeButton("Cerrar", null)
            .setOnDismissListener { paused = false; hideSystemBars() }
            .show()
    }

    // ---------- Control físico ----------

    private fun keyFor(code: Int): Int = when (code) {
        KeyEvent.KEYCODE_BUTTON_A -> SnesNative.B   // abajo en el control = B del SNES
        KeyEvent.KEYCODE_BUTTON_B -> SnesNative.A   // derecha = A
        KeyEvent.KEYCODE_BUTTON_X -> SnesNative.Y   // izquierda = Y
        KeyEvent.KEYCODE_BUTTON_Y -> SnesNative.X   // arriba = X
        KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_L2 -> SnesNative.L
        KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_R2 -> SnesNative.R
        KeyEvent.KEYCODE_BUTTON_START -> SnesNative.START
        KeyEvent.KEYCODE_BUTTON_SELECT -> SnesNative.SELECT
        KeyEvent.KEYCODE_DPAD_UP -> SnesNative.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> SnesNative.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> SnesNative.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> SnesNative.RIGHT
        else -> 0
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_MODE && event.action == KeyEvent.ACTION_UP) {
            showMenu()
            return true
        }
        val key = keyFor(event.keyCode)
        if (key == 0 || paused) return super.dispatchKeyEvent(event)
        hideTouchControls()
        padKeys = when (event.action) {
            KeyEvent.ACTION_DOWN -> padKeys or key
            KeyEvent.ACTION_UP -> padKeys and key.inv()
            else -> padKeys
        }
        if ((padKeys and (SnesNative.SELECT or SnesNative.START)) == (SnesNative.SELECT or SnesNative.START)) {
            padKeys = 0
            showMenu()
        }
        return true
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_JOYSTICK) && event.action == MotionEvent.ACTION_MOVE) {
            val x = event.getAxisValue(MotionEvent.AXIS_X) + event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val y = event.getAxisValue(MotionEvent.AXIS_Y) + event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            var k = 0
            if (x < -0.5f) k = k or SnesNative.LEFT
            if (x > 0.5f) k = k or SnesNative.RIGHT
            if (y < -0.5f) k = k or SnesNative.UP
            if (y > 0.5f) k = k or SnesNative.DOWN
            if (event.getAxisValue(MotionEvent.AXIS_LTRIGGER) > 0.5f) k = k or SnesNative.L
            if (event.getAxisValue(MotionEvent.AXIS_RTRIGGER) > 0.5f) k = k or SnesNative.R
            if (k != 0) hideTouchControls()
            axisKeys = k
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    private fun hideTouchControls() {
        if (controls.visibility == View.VISIBLE) runOnUiThread {
            controls.clear()
            controls.visibility = View.GONE
        }
    }

    private fun showTouchControls() {
        if (controls.visibility != View.VISIBLE) controls.visibility = View.VISIBLE
    }

    // ---------- Ciclo de vida ----------

    override fun onPause() {
        super.onPause()
        paused = true
        if (playStart > 0) playMs += System.currentTimeMillis() - playStart
        playStart = 0
    }

    override fun onResume() {
        super.onResume()
        playStart = System.currentTimeMillis()
        paused = false
        hideSystemBars()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }

    override fun onDestroy() {
        running = false
        thread?.join(3000)
        if (::gameKey.isInitialized) {
            if (playStart > 0) playMs += System.currentTimeMillis() - playStart
            GameDb.addPlayTime(this, gameKey, playMs)
        }
        super.onDestroy()
    }
}
