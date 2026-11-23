package com.nasari.gbaia

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.graphics.Color
import android.view.Gravity
import android.widget.Toast
import java.io.File
import java.nio.IntBuffer

class EmulatorActivity : Activity() {
    private lateinit var game: GameView
    private lateinit var controls: ControlsView
    private lateinit var fpsView: TextView

    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var fastForward = false
    @Volatile private var padKeys = 0
    @Volatile private var axisKeys = 0
    @Volatile private var gpu = true
    /** Modo de imagen actual (incluye NPU, que el núcleo no conoce). Solo se cambia en el hilo del emulador. */
    @Volatile private var mode = 0
    private var npu: NpuUpscaler? = null
    // La NPU corre en su propio hilo, un cuadro detrás del emulador
    private val npuExec = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var npuPending: java.util.concurrent.Future<*>? = null
    private var npuPendingSlot = 0
    private var npuSlot = 0
    private var npuWaitMs = -1f
    private val usage by lazy { Usage(this) }
    /** Tareas que deben correr en el hilo del emulador (ahí vive el contexto de GPU). */
    private val tasks = java.util.concurrent.ConcurrentLinkedQueue<() -> Unit>()

    private lateinit var gameName: String
    private val saveDir by lazy { File(filesDir, "saves").apply { mkdirs() } }
    private val stateDir by lazy { File(filesDir, "states").apply { mkdirs() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        game = GameView(this)
        controls = ControlsView(this)
        game.setOnTouchListener { _, _ -> showTouchControls(); false }
        fpsView = TextView(this).apply {
            setTextColor(Color.YELLOW)
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
            textSize = 14f
            gravity = Gravity.CENTER
            visibility = if (Settings.showFps(context)) View.VISIBLE else View.GONE
        }
        setContentView(FrameLayout(this).apply {
            addView(game)
            addView(controls)
            addView(fpsView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ))
        })
        hideSystemBars()

        val uri = intent?.data
        if (uri == null) {
            finish()
            return
        }
        startGame(uri)
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun startGame(uri: Uri) {
        gameName = Roms.safeName(Roms.displayName(this, uri))
        Recents.add(this, uri, Roms.displayName(this, uri))
        running = true
        thread = Thread({ emulate(uri) }, "emulador").apply { start() }
    }

    private fun emulate(uri: Uri) {
        gpu = Native.start()
        game.gpu = gpu
        Diag.glInfo = Native.glInfo()
        Diag.usageSources = usage.sources()
        Diag.log("inicio: $gameName · GPU ${if (gpu) "ok" else "no disponible"}")
        try {
            runGame(uri)
        } catch (e: Throwable) {
            Diag.log("error: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        } finally {
            dropNpu()
            npuExec.shutdown()
            Native.stop() // guarda la partida y libera la GPU
        }
    }

    private fun runGame(uri: Uri) {
        val rom = try {
            Roms.read(this, uri)
        } catch (e: Exception) {
            fail("No se pudo leer el juego: ${e.message}")
            return
        }
        val savePath = File(saveDir, "$gameName.sav").absolutePath
        val wanted = if (gpu) Settings.scale(this) else 0
        if (!Native.load(rom, savePath, when (wanted) { Settings.NPU -> 0; Settings.HYBRID -> 8; else -> wanted })) {
            fail("Este archivo no es un juego de GBA válido")
            return
        }
        mode = Native.getScale()
        if (usesNpu(wanted)) applyMode(wanted)?.let { msg -> runOnUiThread { toast(msg) } }
        Diag.log("modo inicial: ${Settings.scaleLabel(mode)}")

        val audio = ShortArray(4096 * 2)
        // Respaldo sin GPU
        val pixels = IntArray(Native.WIDTH * Native.HEIGHT)
        val bitmap = Bitmap.createBitmap(Native.WIDTH, Native.HEIGHT, Bitmap.Config.ARGB_8888)
        val buffer = IntBuffer.wrap(pixels)
        val track = createAudio()
        track.play()

        var last = System.nanoTime()
        var frames = 0
        var fpsStart = System.nanoTime()
        var slowSeconds = 0
        var lastScale = mode
        while (running) {
            val now = System.nanoTime()
            if (now - fpsStart >= 1_000_000_000L) {
                val fps = frames * 1e9 / (now - fpsStart)
                val current = mode
                val label = Settings.scaleLabel(current)
                // Respaldo automático: si la IA no va a velocidad completa, bajar a un modo más ligero
                if (current != lastScale) {
                    slowSeconds = -2 // dar tiempo a que compile los shaders
                    lastScale = current
                }
                val measuring = !paused && !fastForward &&
                    (current == Settings.AI || current == Settings.AI_LITE || usesNpu(current))
                slowSeconds = if (measuring && fps < 50) slowSeconds + 1 else minOf(slowSeconds, 0)
                val npuModel = npu?.modelName
                if (slowSeconds >= 3 && usesNpu(current) && npuModel != null && npuModel != NpuUpscaler.MODELS.last { it.endsWith("a8w8") }) {
                    // Un modelo de NPU más ligero antes de rendirse con la NPU
                    Diag.log("respaldo automático: $npuModel lento en NPU (%.1f FPS, NPU %.1f ms, espera %.1f ms) -> modelo más ligero".format(fps, npu?.ms ?: -1f, npuWaitMs))
                    dropNpu()
                    Settings.addNpuFailed(this, listOf(npuModel))
                    val msg = applyMode(current)
                    runOnUiThread { toast("La IA en NPU iba lenta: uso un modelo más ligero" + if (msg != null) " · $msg" else "") }
                    slowSeconds = -2
                    lastScale = mode
                } else if (slowSeconds >= 3) {
                    val fallback = when (current) {
                        Settings.NPU -> Settings.AI
                        Settings.HYBRID -> 8
                        Settings.AI -> Settings.AI_LITE
                        else -> 8
                    }
                    applyMode(fallback)
                    Settings.setScale(this, fallback)
                    val msg = "La imagen iba lenta (%.0f FPS): cambié a %s".format(fps, Settings.scaleLabel(fallback))
                    Diag.log("respaldo automático: %s a %.1f FPS -> %s".format(Settings.scaleLabel(current), fps, Settings.scaleLabel(fallback)))
                    runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
                    slowSeconds = -2
                    lastScale = fallback
                }
                val st = Native.stats()
                val ai = current == Settings.AI || current == Settings.AI_LITE
                val detail = buildString {
                    append("%.1f FPS · %s".format(fps, label))
                    append("\nnúcleo %.1f ms".format(st[1]))
                    if (ai) append(if (st[0] >= 0) " · IA en GPU %.1f ms (de 16.7)".format(st[0]) else " · IA en GPU: sin medición")
                    if (usesNpu(current)) npu?.let {
                        append(" · NPU %.1f ms (%s, espera %.1f ms)".format(it.ms, it.modelName.removePrefix("student_"), npuWaitMs))
                    }
                    if (current == Settings.HYBRID) {
                        val h = Native.hybridInfo()
                        val modes = (0..5).filter { (h[1] shr it) and 1 != 0 }.joinToString("+")
                        append("\n")
                        append(if (h[0] > 0) "Mode 7 en ${h[0]}/160 líneas → 8x" else "sin Mode 7 → todo IA (GPU a 1x)")
                        append(" · modos $modes · GPU ${h[2]}x")
                    }
                    // Uso de procesador, GPU, NPU y RAM
                    append("\n")
                    append(usage.line(if (ai || usesNpu(current)) st[0] else -1f, if (usesNpu(current)) npu?.ms ?: -1f else -1f))
                }
                // En pausa (menú abierto) se conserva lo último medido jugando
                if (!paused && frames > 0) Diag.lastStats = detail.replace('\n', ' · ')
                runOnUiThread { fpsView.text = detail }
                frames = 0
                fpsStart = now
            }
            while (true) tasks.poll()?.invoke() ?: break
            if (paused) {
                Native.idle()
                Thread.sleep(16)
                last = System.nanoTime()
                continue
            }
            val keys = controls.keys or padKeys or axisKeys
            if (fastForward) {
                // ~3x de velocidad, sin sonido; solo se dibuja el último cuadro
                step(keys, audio, false)
                step(keys, audio, false)
                step(keys, audio, true)
                frames += 3
                if (!gpu) drawFallback(bitmap, pixels, buffer)
                val wait = 16_666_667L - (System.nanoTime() - last)
                if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                last = System.nanoTime()
            } else {
                val n = step(keys, audio, true)
                frames++
                if (!gpu) drawFallback(bitmap, pixels, buffer)
                // La escritura bloqueante del audio marca el ritmo (~59.7 cuadros/s)
                if (n > 0) track.write(audio, 0, n * 2)
                last = System.nanoTime()
            }
        }
        track.stop()
        track.release()
    }

    private fun usesNpu(m: Int) = m == Settings.NPU || m == Settings.HYBRID

    /** Un cuadro de emulación; con present lo dibuja según el modo (GPU, IA, NPU o híbrido). */
    private fun step(keys: Int, audio: ShortArray, present: Boolean): Int {
        val up = npu
        if (!usesNpu(mode) || up == null) return Native.runFrame(keys, audio, present && gpu)
        val hybrid = mode == Settings.HYBRID
        val n = Native.runFrame(keys, audio, false)
        if (present) {
            try {
                // Cuadro N: preparar su entrada y mandarlo a la NPU...
                val slot = npuSlot
                npuSlot = 1 - slot
                if (hybrid) {
                    // La GPU ya lo dibujó a 8x: guardar 8x + máscara y sacar la versión 1x para la NPU
                    Native.hybridCapture(slot, up.frames[slot])
                    Native.npuFillInputFrom(up.inputs[slot], up.inType, up.inScale, up.inZero, up.frames[slot])
                } else {
                    Native.npuFillInput(up.inputs[slot], up.inType, up.inScale, up.inZero)
                    Native.copyFrame(up.frames[slot])
                }
                val prev = npuPending
                val prevSlot = npuPendingSlot
                npuPending = npuExec.submit { up.run(slot) }
                npuPendingSlot = slot
                // ...y mostrar el cuadro N-1, que la NPU procesó mientras el emulador hacía este
                if (prev != null) {
                    val t0 = System.nanoTime()
                    prev.get()
                    val w = (System.nanoTime() - t0) / 1e6f
                    npuWaitMs = if (npuWaitMs < 0) w else npuWaitMs * 0.9f + w * 0.1f
                    if (hybrid) Native.hybridPresent(prevSlot, up.outputs[prevSlot], up.outType, up.outScale, up.outZero, up.frames[prevSlot])
                    else Native.npuPresent(up.outputs[prevSlot], up.outType, up.outScale, up.outZero, up.frames[prevSlot])
                }
            } catch (e: Throwable) {
                val cause = (e as? java.util.concurrent.ExecutionException)?.cause ?: e
                Diag.npuStatus = "falló al correr: ${cause.javaClass.simpleName}: ${cause.message}"
                Diag.log("NPU falló al correr: ${cause.message}")
                dropNpu()
                val next = if (hybrid) 8 else Settings.AI
                applyMode(next)
                Settings.setScale(this, next)
                runOnUiThread { toast("La NPU falló; sigo con " + Settings.scaleLabel(next)) }
            }
        }
        return n
    }

    /** Espera el trabajo pendiente de la NPU y la libera. */
    private fun dropNpu() {
        try {
            npuPending?.get()
        } catch (_: Throwable) {
        }
        npuPending = null
        npu?.close()
        npu = null
        npuWaitMs = -1f
    }

    /** Cambia el modo de imagen (hilo del emulador). Devuelve un mensaje para mostrar. */
    private fun applyMode(s: Int): String? {
        if (usesNpu(s)) {
            val up = npu ?: run {
                val notes = mutableListOf<String>()
                val t0 = System.nanoTime()
                val failed = mutableSetOf<String>()
                val created = try {
                    NpuUpscaler.create(this, notes, Settings.npuFailed(this), failed)
                } catch (e: Throwable) {
                    notes += "error inesperado: ${e.javaClass.simpleName}: ${e.message}"
                    null
                }
                notes.forEach { Diag.log("NPU · $it") }
                Settings.addNpuFailed(this, failed)
                Diag.npuStatus = if (created != null)
                    "lista con ${created.modelName} (QNN HTP) · %.1f ms por cuadro · preparar todo %.0f ms".format(created.ms, (System.nanoTime() - t0) / 1e6)
                else "no se pudo usar (ver registro)"
                npu = created
                created
            }
            if (up == null) {
                val next = if (s == Settings.HYBRID) 8 else Settings.AI
                Native.hybridSet(false)
                Native.setScale(next)
                mode = Native.getScale()
                Settings.setScale(this, next)
                return "La NPU no se pudo usar (ver reporte); sigo con " + Settings.scaleLabel(next)
            }
            try {
                npuPending?.get()
            } catch (_: Throwable) {
            }
            npuPending = null
            if (s == Settings.HYBRID) {
                // GPU a 8x con máscara de capas Mode 7; la NPU mejora el resto
                Native.setScale(8)
                if (!Native.hybridSet(true)) {
                    Diag.log("híbrido: no se pudo activar la máscara; queda 8x")
                    mode = Native.getScale()
                    return "No se pudo activar el modo híbrido; queda 8x"
                }
                Native.hybridOptions(Settings.showMask(this), true)
                mode = Settings.HYBRID
            } else {
                Native.hybridSet(false)
                Native.setScale(0) // la NPU trabaja sobre el cuadro exacto
                mode = Settings.NPU
            }
            Diag.log("modo -> ${Settings.scaleLabel(mode)}")
            return Settings.scaleLabel(mode)
        }
        try {
            npuPending?.get() // no dejar trabajo de la NPU a medias
        } catch (_: Throwable) {
        }
        npuPending = null
        Native.hybridSet(false)
        Native.setScale(s)
        mode = Native.getScale()
        Diag.log("modo -> ${Settings.scaleLabel(mode)}")
        return "Resolución: " + Settings.scaleLabel(mode)
    }

    private fun drawFallback(bitmap: Bitmap, pixels: IntArray, buffer: IntBuffer) {
        Native.copyPixels(pixels)
        buffer.rewind()
        bitmap.copyPixelsFromBuffer(buffer)
        game.drawFallback(bitmap)
    }

    /** Corre [task] en el hilo del emulador y muestra el resultado. */
    private fun onEmu(task: () -> String?) {
        tasks.add {
            val msg = task()
            if (msg != null) runOnUiThread { toast(msg) }
        }
    }

    private fun createAudio(): AudioTrack {
        val min = AudioTrack.getMinBufferSize(
            Native.AUDIO_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(Native.AUDIO_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(min, 804 * 4 * 4)) // ~4 cuadros
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

    // ---------- Menú (botón Atrás o Select+Start) ----------

    @Deprecated("Se usa el botón Atrás para abrir el menú")
    override fun onBackPressed() = showMenu()

    private fun showMenu() {
        if (isFinishing) return
        paused = true
        controls.clear()
        val stateFile = File(stateDir, "$gameName.ss1").absolutePath
        val options = arrayOf(
            "Continuar",
            "Guardar estado",
            "Cargar estado",
            "Resolución: " + Settings.scaleLabel(if (gpu) Settings.scale(this) else 0),
            if (fastForward) "Velocidad normal" else "Avance rápido (3x)",
            if (controls.visibility == View.VISIBLE) "Ocultar controles" else "Mostrar controles",
            if (fpsView.visibility == View.VISIBLE) "Ocultar FPS" else "Mostrar FPS",
            if (Settings.showMask(this)) "Ocultar zonas 8x (híbrido)" else "Ver zonas 8x (híbrido)",
            "Generar reporte",
            "Salir",
        )
        var keepPaused = false
        AlertDialog.Builder(this)
            .setTitle(gameName)
            .setItems(options) { _, which ->
                when (which) {
                    1 -> onEmu { if (Native.saveState(stateFile)) "Estado guardado" else "No se pudo guardar" }
                    2 -> onEmu { if (Native.loadState(stateFile)) "Estado cargado" else "No hay estado guardado" }
                    3 -> {
                        keepPaused = true
                        showScaleMenu()
                    }
                    4 -> fastForward = !fastForward
                    5 -> controls.visibility = if (controls.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    6 -> {
                        val show = fpsView.visibility != View.VISIBLE
                        fpsView.visibility = if (show) View.VISIBLE else View.GONE
                        Settings.setShowFps(this, show)
                    }
                    7 -> {
                        val show = !Settings.showMask(this)
                        Settings.setShowMask(this, show)
                        onEmu {
                            Native.hybridOptions(show, true)
                            if (mode != Settings.HYBRID) "Solo se ve en el modo híbrido" else if (show) "Zonas 8x en rosa" else "Zonas 8x ocultas"
                        }
                    }
                    8 -> {
                        keepPaused = true
                        showReport()
                    }
                    9 -> finish()
                }
            }
            .setOnDismissListener {
                if (!keepPaused) {
                    paused = false
                    hideSystemBars()
                }
            }
            .show()
    }

    private fun showScaleMenu() {
        if (!gpu) {
            toast("Este celular no tiene GPU compatible (GLES3)")
            paused = false
            return
        }
        val values = Settings.SCALES
        val current = values.indexOf(Settings.scale(this)).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Resolución")
            .setSingleChoiceItems(values.map { Settings.scaleLabel(it) }.toTypedArray(), current) { d, which ->
                val s = values[which]
                Settings.setScale(this, s)
                onEmu { applyMode(s) }
                d.dismiss()
            }
            .setOnDismissListener {
                paused = false
                hideSystemBars()
            }
            .show()
    }

    private fun showReport() {
        val text = Diag.report(this, gameName, Settings.scaleLabel(mode))
        AlertDialog.Builder(this)
            .setTitle("Reporte")
            .setMessage(text)
            .setPositiveButton("Compartir") { _, _ ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }, "Enviar reporte"))
            }
            .setNeutralButton("Copiar") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Reporte GBA IA", text))
                toast("Reporte copiado")
            }
            .setNegativeButton("Cerrar", null)
            .setOnDismissListener {
                paused = false
                hideSystemBars()
            }
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ---------- Control físico (Bluetooth / USB) ----------

    private fun keyFor(code: Int): Int = when (code) {
        KeyEvent.KEYCODE_BUTTON_A -> Native.A
        KeyEvent.KEYCODE_BUTTON_B -> Native.B
        KeyEvent.KEYCODE_BUTTON_X -> Native.B
        KeyEvent.KEYCODE_BUTTON_Y -> Native.A
        KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_L2 -> Native.L
        KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_R2 -> Native.R
        KeyEvent.KEYCODE_BUTTON_START -> Native.START
        KeyEvent.KEYCODE_BUTTON_SELECT -> Native.SELECT
        KeyEvent.KEYCODE_DPAD_UP -> Native.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> Native.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> Native.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> Native.RIGHT
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
        // Select + Start abre el menú
        if ((padKeys and (Native.SELECT or Native.START)) == (Native.SELECT or Native.START)) {
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
            if (x < -0.5f) k = k or Native.LEFT
            if (x > 0.5f) k = k or Native.RIGHT
            if (y < -0.5f) k = k or Native.UP
            if (y > 0.5f) k = k or Native.DOWN
            if (event.getAxisValue(MotionEvent.AXIS_LTRIGGER) > 0.5f) k = k or Native.L
            if (event.getAxisValue(MotionEvent.AXIS_RTRIGGER) > 0.5f) k = k or Native.R
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
    }

    override fun onResume() {
        super.onResume()
        paused = false
        hideSystemBars()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        // Un launcher pidió otro juego: se cierra el actual (guardando) y se abre el nuevo
        setIntent(intent)
        recreate()
    }

    override fun onDestroy() {
        running = false
        thread?.join(2000)
        super.onDestroy()
    }
}
