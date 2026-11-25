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
            c.getSharedPreferences("snes", android.content.Context.MODE_PRIVATE).getString("mode7_v2", "3x") ?: "3x"
        fun setMode7(c: android.content.Context, v: String) =
            c.getSharedPreferences("snes", android.content.Context.MODE_PRIVATE).edit().putString("mode7_v2", v).apply()
        /** Pantalla ancha en escenas de Mode 7: "16:9", "none"… */
        fun wide(c: android.content.Context): String =
            c.getSharedPreferences("snes", android.content.Context.MODE_PRIVATE).getString("ancho", "16:9") ?: "16:9"
        fun setWide(c: android.content.Context, v: String) =
            c.getSharedPreferences("snes", android.content.Context.MODE_PRIVATE).edit().putString("ancho", v).apply()

        /** IA 4x en la NPU (Hexagon) para los cuadros normales de 256x224; el Mode 7 HD no la usa. */
        fun ia(c: android.content.Context): Boolean =
            c.getSharedPreferences("snes", android.content.Context.MODE_PRIVATE).getBoolean("ia", true)
        fun setIa(c: android.content.Context, v: Boolean) =
            c.getSharedPreferences("snes", android.content.Context.MODE_PRIVATE).edit().putBoolean("ia", v).apply()

        private const val KIND_PLAIN = 0
        private const val KIND_NPU = 1
        private const val KIND_HD = 2

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

    /** Escala de Mode 7 bajada solo en esta partida por el respaldo automático (null = la elegida). */
    @Volatile private var sessionMode7: String? = null
    private fun currentMode7() = sessionMode7 ?: mode7(this)

    // IA en NPU (todo esto se toca solo desde el hilo del emulador, salvo lecturas para el reporte)
    @Volatile private var npu: NpuUpscaler? = null
    private val npuExec = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var npuPending: java.util.concurrent.Future<*>? = null
    private var npuPendingSlot = 0
    private var npuSlot = 0
    private val npuFrameH = IntArray(2)
    @Volatile private var npuWaitMs = -1f
    @Volatile private var iaWhy = "todavía no se inicia"
    /** Qué tipo de imagen se presentó en el último cuadro: normal, con IA o Mode 7 HD. */
    @Volatile private var lastKind = KIND_PLAIN

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
            if (ia(this)) {
                startNpu()?.let { msg -> runOnUiThread { toast(msg) } }
            } else {
                iaWhy = "desactivada en el menú"
            }
            loop()
        } catch (e: Throwable) {
            Diag.log("error: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        } finally {
            if (loaded) SnesNative.saveState(stateFile(this, fileName, "auto").absolutePath)
            dropNpu()
            npuExec.shutdown()
            SnesNative.stop()
        }
    }

    /** Crea el modelo de la NPU (hilo del emulador). Devuelve un mensaje, o null si todo bien. */
    private fun startNpu(): String? {
        val notes = mutableListOf<String>()
        val failed = mutableSetOf<String>()
        val t0 = System.nanoTime()
        SnesNative.npuSetSize(256, 224)
        var created = try {
            NpuUpscaler.create(this, notes, Settings.npuFailed(this), failed, NpuUpscaler.MODELS_SNES, 256 * 224 * 4)
        } catch (e: Throwable) {
            notes += "error inesperado: ${e.javaClass.simpleName}: ${e.message}"
            null
        }
        // Protección: si el modelo no es de 256x224 el JNI escribiría fuera de los búferes
        if (created != null) {
            val bytes = when (created.inType) { 2 -> 2; 0 -> 4; else -> 1 }
            val obytes = when (created.outType) { 2 -> 2; 0 -> 4; else -> 1 }
            if (created.inputs[0].capacity() != 256 * 224 * 3 * bytes || created.outputs[0].capacity() != 256 * 224 * 48 * obytes) {
                notes += "NO SIRVE ${created.modelName}: los tensores no son de 256x224 (entrada ${created.inputs[0].capacity()} B, salida ${created.outputs[0].capacity()} B)"
                failed += created.modelName
                created.close()
                created = null
            }
        }
        notes.forEach { Diag.log("SNES IA · $it") }
        Settings.addNpuFailed(this, failed)
        npu = created
        npuWaitMs = -1f
        npuPending = null
        npuSlot = 0
        return if (created != null) {
            iaWhy = ""
            Diag.log("SNES IA lista con ${created.modelName} · %.1f ms por cuadro · preparar todo %.0f ms".format(created.ms, (System.nanoTime() - t0) / 1e6))
            null
        } else {
            iaWhy = "no se pudo usar la NPU, ver registro"
            Diag.log("SNES IA: no se pudo usar la NPU; sigue sin IA")
            "La IA en NPU no se pudo usar; sigo sin IA"
        }
    }

    /** Espera lo que la NPU tenga a medias (hilo del emulador). */
    private fun waitNpu() {
        try {
            npuPending?.get()
        } catch (_: Throwable) {
        }
        npuPending = null
    }

    /** Descarta lo pendiente y libera la NPU. */
    private fun dropNpu() {
        waitNpu()
        npu?.close()
        npu = null
        npuWaitMs = -1f
    }

    /** Un cuadro de emulación; con present lo dibuja (con IA si se puede, o tal cual). */
    private fun stepFrame(keys: Int, audio: ShortArray, present: Boolean): Int {
        val up = npu
        if (up == null) {
            lastKind = KIND_PLAIN
            return SnesNative.runFrame(keys, audio, present)
        }
        val n = SnesNative.runFrame(keys, audio, false)
        if (present) presentNpu(up)
        return n
    }

    private fun presentPlain(kind: Int) {
        lastKind = kind
        SnesNative.presentPlain()
    }

    private fun presentNpu(up: NpuUpscaler) {
        val slot = npuSlot
        val h = SnesNative.copyFrameNpu(up.frames[slot])
        if (h == 0) {
            // Cuadro HD de Mode 7 (u otro tamaño): se descarta lo pendiente y se muestra tal cual
            waitNpu()
            presentPlain(KIND_HD)
            return
        }
        try {
            // Cuadro N: preparar su entrada y mandarlo a la NPU...
            npuSlot = 1 - slot
            npuFrameH[slot] = h
            SnesNative.npuFillInputFrom(up.inputs[slot], up.inType, up.inScale, up.inZero, up.frames[slot], h)
            val prev = npuPending
            val prevSlot = npuPendingSlot
            npuPending = npuExec.submit { up.run(slot) }
            npuPendingSlot = slot
            // ...y mostrar el cuadro N-1, que la NPU procesó mientras el emulador hacía este
            if (prev == null) {
                presentPlain(KIND_PLAIN) // primer cuadro tras un cambio: sin IA
                return
            }
            val t0 = System.nanoTime()
            prev.get()
            val w = (System.nanoTime() - t0) / 1e6f
            npuWaitMs = if (npuWaitMs < 0) w else npuWaitMs * 0.9f + w * 0.1f
            if (SnesNative.npuPresent(up.outputs[prevSlot], up.outType, up.outScale, up.outZero, up.frames[prevSlot], npuFrameH[prevSlot])) {
                lastKind = KIND_NPU
            } else {
                presentPlain(KIND_PLAIN)
            }
        } catch (e: Throwable) {
            val cause = (e as? java.util.concurrent.ExecutionException)?.cause ?: e
            Diag.log("SNES IA: la NPU falló al correr: ${cause.javaClass.simpleName}: ${cause.message}")
            iaWhy = "falló al correr: ${cause.javaClass.simpleName}"
            dropNpu()
            presentPlain(KIND_PLAIN)
            runOnUiThread { toast("La NPU falló; sigo sin IA") }
        }
    }

    /** Aplica la preferencia "ia" jugando (hilo del emulador). */
    private fun applyIa(): String {
        if (!ia(this)) {
            dropNpu()
            iaWhy = "desactivada en el menú"
            lastKind = KIND_PLAIN
            return "IA en NPU apagada"
        }
        if (npu != null) return "IA en NPU activada"
        return startNpu() ?: "IA en NPU activada"
    }

    private fun applyOptions() {
        val m7 = currentMode7()
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
        // Los juegos europeos (PAL) corren a 50 cuadros/s: eso no es ir lento
        val target = SnesNative.fps().takeIf { it > 10 } ?: 60.0
        val region = if (target < 55) "PAL 50 Hz" else "NTSC 60 Hz"
        val boost = PerfBoost(this, target)
        Diag.log("hilo SNES: prioridad alta, PerformanceHint ${if (boost.active) "sí" else "no disponible"}")
        while (running) {
            val now = System.nanoTime()
            if (now - fpsStart >= 1_000_000_000L) {
                val fps = frames * 1e9 / (now - fpsStart)
                // Respaldo automático: el Mode 7 HD se dibuja en el procesador; si no da 60, se baja un nivel
                slowSeconds = if (!paused && !fastForward && fps < target * 0.92) slowSeconds + 1 else minOf(slowSeconds, 0)
                val idx = MODE7_VALUES.indexOf(currentMode7())
                if (slowSeconds >= 3 && idx > 1) {
                    // Solo para esta partida: la opción guardada no cambia
                    sessionMode7 = MODE7_VALUES[idx - 1]
                    applyOptions()
                    val msg = "Iba lento (%.0f FPS): Mode 7 baja a %s".format(fps, MODE7_LABELS[idx - 1])
                    Diag.log(msg)
                    runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
                    slowSeconds = -2
                }
                val size = SnesNative.frameSize()
                val detail = buildString {
                    append("%.1f FPS (%s) · Mode 7 %s".format(fps, region, MODE7_LABELS[MODE7_VALUES.indexOf(currentMode7()).coerceAtLeast(0)].substringBefore(" (")))
                    append("\nnúcleo %.1f ms · cuadro %d×%d · %s".format(SnesNative.emuMs(), size[0], size[1], boost.where()))
                    when (lastKind) {
                        KIND_NPU -> append(" · NPU %.1f ms (espera %.1f ms)".format(npu?.ms ?: -1f, npuWaitMs))
                        KIND_HD -> append(" · Mode 7 HD")
                    }
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
            val work0 = System.nanoTime()
            if (fastForward) {
                stepFrame(keys, audio, false)
                stepFrame(keys, audio, false)
                stepFrame(keys, audio, true)
                frames += 3
                val wait = 16_666_667L - (System.nanoTime() - last)
                if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                last = System.nanoTime()
            } else {
                val n = stepFrame(keys, audio, true)
                boost.report(System.nanoTime() - work0)
                frames++
                // La escritura bloqueante del audio marca el ritmo (~60.1 cuadros/s)
                if (n > 0) track.write(audio, 0, n * 2)
                last = System.nanoTime()
            }
        }
        boost.close()
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
            "IA en NPU: " + if (ia(this)) "sí" else "no",
            "Salir",
        )
        val onPick: (Int) -> Unit = { which ->
                when (which) {
                    1, 2 -> { keepPaused = true; showSlots(save = which == 1) }
                    3 -> {
                        keepPaused = true
                        AlertDialog.Builder(this).setTitle("Mode 7 en HD")
                            .setSingleChoiceItems(MODE7_LABELS.toTypedArray(), MODE7_VALUES.indexOf(mode7(this))) { d, w ->
                                setMode7(this, MODE7_VALUES[w])
                                sessionMode7 = null
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
                    10 -> {
                        setIa(this, !ia(this))
                        onEmu { applyIa() }
                    }
                    11 -> finish()
                }
            }
        Ui.showGridMenu(this, fileName, options.toList(), onDismiss = { if (!keepPaused) { paused = false; hideSystemBars() } }, onPick = onPick)
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
            val up = npu
            appendLine(
                if (up != null) "IA: ${up.modelName} · NPU %.1f ms (espera %.1f ms)".format(up.ms, npuWaitMs)
                else "IA: apagada / no disponible ($iaWhy)"
            )
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
