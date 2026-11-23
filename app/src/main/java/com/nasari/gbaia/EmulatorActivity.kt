package com.nasari.gbaia

import android.app.Activity
import android.app.AlertDialog
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
import android.widget.Toast
import java.io.File
import java.nio.IntBuffer

class EmulatorActivity : Activity() {
    private lateinit var game: GameView
    private lateinit var controls: ControlsView

    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var fastForward = false
    @Volatile private var padKeys = 0
    @Volatile private var axisKeys = 0
    private val coreLock = Any()

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
        setContentView(FrameLayout(this).apply {
            addView(game)
            addView(controls)
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
        val rom = try {
            Roms.read(this, uri)
        } catch (e: Exception) {
            fail("No se pudo leer el juego: ${e.message}")
            return
        }
        val savePath = File(saveDir, "$gameName.sav").absolutePath
        val loaded = synchronized(coreLock) { Native.load(rom, savePath) }
        if (!loaded) {
            fail("Este archivo no es un juego de GBA válido")
            return
        }

        val pixels = IntArray(Native.WIDTH * Native.HEIGHT)
        val audio = ShortArray(4096 * 2)
        val bitmap = Bitmap.createBitmap(Native.WIDTH, Native.HEIGHT, Bitmap.Config.ARGB_8888)
        val buffer = IntBuffer.wrap(pixels)
        val track = createAudio()
        track.play()

        var last = System.nanoTime()
        while (running) {
            if (paused) {
                Thread.sleep(16)
                last = System.nanoTime()
                continue
            }
            val keys = controls.keys or padKeys or axisKeys
            if (fastForward) {
                // ~3x de velocidad, sin sonido
                synchronized(coreLock) { repeat(3) { Native.runFrame(keys, pixels, audio) } }
                draw(bitmap, buffer)
                val wait = 16_666_667L - (System.nanoTime() - last)
                if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                last = System.nanoTime()
            } else {
                val n = synchronized(coreLock) { Native.runFrame(keys, pixels, audio) }
                draw(bitmap, buffer)
                // La escritura bloqueante del audio marca el ritmo (~59.7 cuadros/s)
                if (n > 0) track.write(audio, 0, n * 2)
                last = System.nanoTime()
            }
        }
        track.stop()
        track.release()
        synchronized(coreLock) { Native.unload() } // guarda la partida
    }

    private fun draw(bitmap: Bitmap, buffer: IntBuffer) {
        buffer.rewind()
        bitmap.copyPixelsFromBuffer(buffer)
        game.draw(bitmap)
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
            if (fastForward) "Velocidad normal" else "Avance rápido (3x)",
            if (controls.visibility == View.VISIBLE) "Ocultar controles" else "Mostrar controles",
            "Salir",
        )
        AlertDialog.Builder(this)
            .setTitle(gameName)
            .setItems(options) { _, which ->
                when (which) {
                    1 -> toast(if (synchronized(coreLock) { Native.saveState(stateFile) }) "Estado guardado" else "No se pudo guardar")
                    2 -> toast(if (synchronized(coreLock) { Native.loadState(stateFile) }) "Estado cargado" else "No hay estado guardado")
                    3 -> fastForward = !fastForward
                    4 -> controls.visibility = if (controls.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    5 -> finish()
                }
            }
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
