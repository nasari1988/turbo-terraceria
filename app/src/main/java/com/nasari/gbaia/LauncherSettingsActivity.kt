package com.nasari.gbaia

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.InputDevice
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.io.File
import kotlin.concurrent.thread

/** Ajustes del launcher: consolas y emuladores, carátulas, control, fondos y el emulador GBA IA. */
class LauncherSettingsActivity : PickActivity() {
    private enum class Section(val label: String) {
        SYSTEMS("Consolas y emuladores"), COVERS("Carátulas"), PAD("Control Bluetooth"),
        LOOK("Fondos y apariencia"), GBA("Emulador GBA IA"),
    }

    private enum class WpTab(val label: String) { APP("Toda la app"), SYSTEM("Por consola"), GAME("Por juego") }

    private var section = Section.SYSTEMS
    private var wpTab = WpTab.APP
    private lateinit var nav: LinearLayout
    private lateinit var body: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nav = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(14), dp(12), dp(14))
            setBackgroundColor(Ui.BG_DEEP)
        }
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(14), dp(22), dp(20))
        }
        setContentView(LinearLayout(this).apply {
            setBackgroundColor(Ui.BG)
            addView(ScrollView(context).apply { addView(nav) }, LinearLayout.LayoutParams(dp(230), MATCH_PARENT))
            addView(ScrollView(context).apply { addView(body) }, Ui.lp(0, MATCH_PARENT, 1f))
        })
        intent.getStringExtra("seccion")?.let { s -> Section.values().firstOrNull { it.name == s }?.let { section = it } }
        render()
        hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    private fun render() {
        nav.removeAllViews()
        nav.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(context).apply {
                setImageDrawable(Icon(Icon.Kind.BACK, Ui.TEXT, dp(1).toFloat()))
                contentDescription = "Volver"
                setPadding(dp(12), dp(12), dp(12), dp(12))
                isFocusable = true
                setOnClickListener { finish() }
            }, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(Ui.text(context, "Ajustes", 18f, Ui.TEXT, bold = true))
        }, Ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(6) })
        for (s in Section.values()) {
            val on = s == section
            nav.addView(Ui.text(this, s.label, 14f, if (on) Ui.accentLight(this) else Ui.TEXT2, bold = on).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), 0, dp(10), 0)
                background = Ui.ripple(Ui.round(if (on) Ui.accentSoft(context) else Color.TRANSPARENT, dp(10).toFloat()))
                isFocusable = true
                setOnClickListener { section = s; render() }
            }, Ui.lp(MATCH_PARENT, dp(46)).apply { bottomMargin = dp(4) })
        }
        body.removeAllViews()
        when (section) {
            Section.SYSTEMS -> systemsSection()
            Section.COVERS -> coversSection()
            Section.PAD -> padSection()
            Section.LOOK -> lookSection()
            Section.GBA -> gbaSection()
        }
    }

    private fun gap(v: View, top: Int = 8) = body.addView(v, Ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(top) })

    // ---------- Consolas y emuladores ----------

    private fun systemsSection() {
        body.addView(Ui.label(this, "Carpeta de juegos"))
        gap(Ui.row(this, LauncherPrefs.romRoot(this) ?: "Sin elegir", "Una subcarpeta por consola: gba, gbc, snes, psx, nds, psp…",
            Ui.button(this, "Cambiar", primary = true) {
                pickFolder { path ->
                    if (path == null || !File(path).isDirectory) toast("No pude usar esa carpeta")
                    else { LauncherPrefs.setRomRoot(this, path); rescan() }
                }
            }), 0)
        gap(Ui.button(this, "Volver a buscar juegos") { rescan() })

        body.addView(Ui.label(this, "Con qué se abre cada consola").apply { setPadding(0, dp(18), 0, dp(4)) })
        val counts = Library.bySystem(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.round(Ui.CARD2, dp(12).toFloat())
        }
        Systems.ALL.forEachIndexed { i, sys ->
            val l = Launch.launcherFor(this, sys)
            val chosenIdx = LauncherPrefs.launcherIndex(this, sys.id)
            val chosen = sys.launchers.getOrNull(chosenIdx)
            val missing = chosen != null && !Launch.isAvailable(this, chosen)
            val st = when {
                missing -> "${chosen!!.label} no instalado · uso ${l.label}"
                l == Launcher.Any -> "Ningún emulador conocido instalado"
                else -> "Listo"
            }
            box.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(8), dp(14), dp(8))
                if (i > 0) background = Ui.round(Color.TRANSPARENT, 0f)
                addView(Ui.text(context, sys.short, 14f, Ui.TEXT, bold = true), LinearLayout.LayoutParams(dp(52), WRAP_CONTENT))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(Ui.text(context, l.label, 13f, Ui.TEXT2))
                    addView(Ui.text(context, "${counts[sys.id]?.size ?: 0} juegos · $st", 11f, if (st == "Listo") Ui.OK else Ui.BAD).apply { setPadding(0, dp(3), 0, 0) })
                }, Ui.lp(0, WRAP_CONTENT, 1f))
                addView(Ui.button(context, "Cambiar") {
                    val opts = sys.launchers
                    AlertDialog.Builder(this@LauncherSettingsActivity)
                        .setTitle("Abrir ${sys.short} con")
                        .setSingleChoiceItems(opts.map { it.label + if (Launch.isAvailable(this@LauncherSettingsActivity, it)) "" else "  (no instalado)" }.toTypedArray(),
                            opts.indexOf(l)) { d, w ->
                            LauncherPrefs.setLauncherIndex(this@LauncherSettingsActivity, sys.id, w)
                            if (!Launch.isAvailable(this@LauncherSettingsActivity, opts[w])) toast("Instálalo para usarlo; mientras, uso otro")
                            d.dismiss()
                            render()
                        }.show()
                }, LinearLayout.LayoutParams(WRAP_CONTENT, dp(40)))
            })
            if (i < Systems.ALL.size - 1) box.addView(View(this).apply { setBackgroundColor(Color.rgb(0x23, 0x27, 0x31)) }, Ui.lp(MATCH_PARENT, 1))
        }
        gap(box, 0)
        gap(Ui.text(this, "RetroArch: instala los núcleos desde su menú (Actualizador en línea › Descargar núcleos). Los juegos de otras consolas se abren con la ruta del archivo; por eso el launcher pide acceso a todos los archivos.", 12f, Ui.MUTED).apply { setLineSpacing(0f, 1.25f) }, 10)
    }

    private fun rescan() {
        toast("Buscando juegos…")
        thread {
            val n = Library.scan(this).size
            runOnUiThread { toast("Encontré $n juegos"); render() }
        }
    }

    // ---------- Carátulas ----------

    private fun coversSection() {
        body.addView(Ui.label(this, "Carátulas"))
        val auto = LauncherPrefs.coversAuto(this)
        gap(Ui.row(this, "Bajarlas solas", "De la base libre de libretro (la misma de RetroArch y ES-DE), al ver cada juego",
            Ui.button(this, if (auto) "Activado" else "Apagado", primary = auto) {
                LauncherPrefs.setCoversAuto(this, !auto); render()
            }), 0)
        val progress = Ui.text(this, "", 13f, Ui.TEXT2)
        gap(Ui.row(this, "Bajar todas las que falten ahora", "Tarda según cuántos juegos tengas", Ui.button(this, "Bajar") {
            val games = Library.games(this)
            thread {
                val ok = Covers.downloadAll(this, games) { i, n -> runOnUiThread { progress.text = "Revisando $i de $n…" } }
                runOnUiThread { progress.text = "Listo: $ok carátulas nuevas" }
            }
        }))
        gap(progress)
        gap(Ui.row(this, "Borrar las bajadas", "Se vuelven a bajar al verlas. Las que elegiste de tu galería se quedan", Ui.button(this, "Borrar") {
            File(filesDir, "caratulas").listFiles()?.filter { it.name != "propias" }?.forEach { it.deleteRecursively() }
            File(cacheDir, ".").listFiles()?.filter { it.name.startsWith("indice_") }?.forEach { it.delete() }
            toast("Carátulas borradas")
        }))
        gap(Ui.text(this, "Si una carátula sale equivocada, en el juego: Opciones › Carátula › Elegir imagen de la galería.", 12f, Ui.MUTED), 12)
    }

    // ---------- Control ----------

    private fun padSection() {
        body.addView(Ui.label(this, "Controles conectados"))
        val pads = InputDevice.getDeviceIds().mapNotNull { InputDevice.getDevice(it) }
            .filter { (it.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD || (it.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK }
        if (pads.isEmpty()) gap(Ui.row(this, "Ninguno", "Conéctalo por Bluetooth en los ajustes del celular y vuelve aquí", null), 0)
        else pads.forEach { gap(Ui.row(this, it.name, "Listo para usar", null), 0) }
        body.addView(Ui.label(this, "Botones en el launcher").apply { setPadding(0, dp(18), 0, dp(4)) })
        for ((k, v) in listOf(
            "Cruceta ◀ ▶" to "Cambiar consola o juego", "A" to "Entrar / jugar", "B" to "Volver",
            "Y" to "Favorito (en juegos) · buscar (en consolas)", "Select" to "Opciones del juego",
            "L / R" to "Cambiar filtro: todos, favoritos, recientes", "Start" to "Ajustes",
        )) gap(Ui.row(this, k, v, null), 4)
        gap(Ui.text(this, "En el emulador GBA IA: Select + Start (o el botón central del control) abre el menú del juego.", 12f, Ui.MUTED), 12)
    }

    // ---------- Fondos y apariencia ----------

    private fun lookSection() {
        body.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(context).apply {
                setPadding(dp(4), dp(4), dp(4), dp(4))
                background = Ui.round(Ui.BG_DEEP, dp(12).toFloat())
                for (t in WpTab.values()) {
                    val on = t == wpTab
                    addView(Ui.text(context, t.label, 13f, if (on) Ui.accentLight(context) else Ui.TEXT2, bold = true).apply {
                        gravity = Gravity.CENTER
                        setPadding(dp(14), 0, dp(14), 0)
                        background = Ui.ripple(Ui.round(if (on) Ui.accentSoft(context) else Color.TRANSPARENT, dp(9).toFloat()))
                        isFocusable = true
                        setOnClickListener { wpTab = t; render() }
                    }, LinearLayout.LayoutParams(WRAP_CONTENT, dp(38)))
                }
            })
            addView(View(context), Ui.lp(0, 1, 1f))
            addView(Ui.text(context, "Se usa: juego › consola › app", 12f, Ui.MUTED))
        })
        when (wpTab) {
            WpTab.APP -> wpApp()
            WpTab.SYSTEM -> wpSystems()
            WpTab.GAME -> wpGames()
        }
    }

    private fun preview(f: File?, w: Int, h: Int): View {
        val bmp = f?.takeIf { it.exists() }?.let { Wallpapers.load(it, 800) }
        return if (bmp != null) ImageView(this).apply {
            setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            background = Ui.round(Ui.CARD, dp(10).toFloat())
            layoutParams = LinearLayout.LayoutParams(w, h)
        } else Ui.text(this, "Sin fondo", 12f, Ui.MUTED).apply {
            gravity = Gravity.CENTER
            background = Ui.round(Ui.CARD, dp(10).toFloat(), dp(1), Ui.LINE)
            layoutParams = LinearLayout.LayoutParams(w, h)
        }
    }

    private fun wpApp() {
        val f = Wallpapers.appFile(this)
        body.addView(LinearLayout(this).apply {
            setPadding(0, dp(12), 0, 0)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(preview(f, dp(250), dp(140)))
                addView(LinearLayout(context).apply {
                    setPadding(0, dp(8), 0, 0)
                    addView(Ui.button(context, "Elegir de la galería", primary = true) {
                        pickImage { uri -> if (Wallpapers.setFrom(this@LauncherSettingsActivity, uri, f)) render() else toast("No pude usar esa imagen") }
                    }, Ui.lp(0, dp(44), 1f))
                    if (f.exists()) addView(Ui.button(context, "Quitar") { Wallpapers.remove(f); render() },
                        LinearLayout.LayoutParams(WRAP_CONTENT, dp(44)).apply { leftMargin = dp(8) })
                })
            }, LinearLayout.LayoutParams(dp(250), WRAP_CONTENT))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(slider("Oscurecer", LauncherPrefs.dim(context), 90, { "$it%" }) { LauncherPrefs.setDim(this@LauncherSettingsActivity, it) })
                addView(slider("Desenfoque", LauncherPrefs.blur(context), 3, { listOf("Nada", "Bajo", "Medio", "Alto")[it] }) { LauncherPrefs.setBlur(this@LauncherSettingsActivity, it) },
                    Ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
                val folder = LauncherPrefs.randomFolder(context)
                addView(Ui.row(context, "Cambiar solo", if (folder == null) "Uno al azar de una carpeta cada vez que abres" else "Al azar de: $folder",
                    Ui.button(context, if (folder == null) "Elegir carpeta" else "Apagar") {
                        if (folder != null) { LauncherPrefs.setRandomFolder(this@LauncherSettingsActivity, null); render() }
                        else pickFolder { p -> if (p != null) { LauncherPrefs.setRandomFolder(this@LauncherSettingsActivity, p); Wallpapers.pickRandom(this@LauncherSettingsActivity); render() } }
                    }), Ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
            }, Ui.lp(0, WRAP_CONTENT, 1f).apply { leftMargin = dp(16) })
        })
        body.addView(Ui.label(this, "Color de acento").apply { setPadding(0, dp(18), 0, dp(4)) })
        body.addView(LinearLayout(this).apply {
            Ui.ACCENTS.forEachIndexed { i, hex ->
                val on = LauncherPrefs.accent(context) == i
                addView(Ui.text(context, Ui.ACCENT_NAMES[i], 13f, if (on) Ui.ON_ACCENT else Ui.TEXT, bold = on).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(16), 0, dp(16), 0)
                    background = Ui.ripple(Ui.round(if (on) Color.parseColor(hex) else Ui.CARD, dp(10).toFloat(), dp(2), Color.parseColor(hex)))
                    isFocusable = true
                    setOnClickListener { LauncherPrefs.setAccent(this@LauncherSettingsActivity, i); render() }
                }, LinearLayout.LayoutParams(WRAP_CONTENT, dp(42)).apply { rightMargin = dp(8) })
            }
        })
    }

    private fun slider(title: String, value: Int, max: Int, fmt: (Int) -> String, onChange: (Int) -> Unit): View {
        val v = Ui.text(this, fmt(value), 12f, Ui.MUTED)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(6))
            background = Ui.round(Ui.CARD2, dp(12).toFloat())
            addView(LinearLayout(context).apply {
                addView(Ui.text(context, title, 13f, Ui.TEXT, bold = true), Ui.lp(0, WRAP_CONTENT, 1f))
                addView(v)
            })
            addView(SeekBar(context).apply {
                this.max = max
                progress = value
                progressTintList = android.content.res.ColorStateList.valueOf(Ui.accent(context))
                thumbTintList = android.content.res.ColorStateList.valueOf(Ui.accent(context))
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { v.text = fmt(p); if (fromUser) onChange(p) }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }, Ui.lp(MATCH_PARENT, dp(36)))
        }
    }

    private fun wpSystems() {
        val counts = Library.bySystem(this)
        val list = Systems.ALL.sortedByDescending { counts[it.id]?.size ?: 0 }
        val grid = GridLayout(this).apply { columnCount = 3 }
        list.forEachIndexed { i, sys ->
            val f = Wallpapers.systemFile(this, sys.id)
            grid.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = Ui.round(Ui.CARD2, dp(12).toFloat())
                addView(preview(f, MATCH_PARENT, dp(64)))
                addView(LinearLayout(context).apply {
                    setPadding(0, dp(6), 0, dp(6))
                    addView(Ui.text(context, sys.short, 14f, Ui.TEXT, bold = true), Ui.lp(0, WRAP_CONTENT, 1f))
                    addView(Ui.text(context, if (f.exists()) "Propio" else "Usa el de la app", 11f, if (f.exists()) Ui.OK else Ui.MUTED))
                })
                addView(LinearLayout(context).apply {
                    addView(Ui.button(context, "Cambiar") {
                        pickImage { uri -> if (Wallpapers.setFrom(this@LauncherSettingsActivity, uri, f)) render() else toast("No pude usar esa imagen") }
                    }, Ui.lp(0, dp(36), 1f))
                    if (f.exists()) addView(Ui.button(context, "Quitar") { Wallpapers.remove(f); render() },
                        LinearLayout.LayoutParams(WRAP_CONTENT, dp(36)).apply { leftMargin = dp(6) })
                })
            }, GridLayout.LayoutParams(GridLayout.spec(i / 3), GridLayout.spec(i % 3, 1f)).apply {
                width = 0
                setMargins(0, dp(10), if (i % 3 < 2) dp(10) else 0, 0)
            })
        }
        body.addView(grid, Ui.lp(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun wpGames() {
        val games = Library.games(this)
        val with = games.filter { Wallpapers.gameFile(this, it).exists() }
        body.addView(LinearLayout(this).apply {
            setPadding(0, dp(12), 0, dp(4))
            addView(Ui.button(context, "+ Agregar fondo a un juego", primary = true) { chooseGameForWallpaper(games) })
        })
        if (with.isEmpty()) gap(Ui.text(this, "Ningún juego tiene fondo propio todavía. Toma una captura dentro del juego (menú › \"Usar como fondo del juego\") o elige una imagen.", 13f, Ui.MUTED).apply { setLineSpacing(0f, 1.25f) })
        for (g in with) {
            val f = Wallpapers.gameFile(this, g)
            gap(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = Ui.round(Ui.CARD2, dp(12).toFloat())
                addView(preview(f, dp(96), dp(48)))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), 0, 0, 0)
                    addView(Ui.text(context, g.title, 14f, Ui.TEXT, bold = true))
                    addView(Ui.text(context, Systems.byId(g.system)?.short ?: "", 12f, Ui.MUTED).apply { setPadding(0, dp(3), 0, 0) })
                }, Ui.lp(0, WRAP_CONTENT, 1f))
                addView(Ui.button(context, "Cambiar") {
                    pickImage { uri -> if (Wallpapers.setFrom(this@LauncherSettingsActivity, uri, f)) render() else toast("No pude usar esa imagen") }
                }, LinearLayout.LayoutParams(WRAP_CONTENT, dp(38)))
                addView(Ui.button(context, "Quitar") { Wallpapers.remove(f); render() },
                    LinearLayout.LayoutParams(WRAP_CONTENT, dp(38)).apply { leftMargin = dp(6) })
            })
        }
    }

    private fun chooseGameForWallpaper(games: List<Game>) {
        if (games.isEmpty()) { toast("No hay juegos en la biblioteca"); return }
        val sorted = games.sortedBy { it.title.lowercase() }
        AlertDialog.Builder(this)
            .setTitle("¿A qué juego?")
            .setItems(sorted.map { "${it.title} · ${Systems.byId(it.system)?.short}" }.toTypedArray()) { _, w ->
                val f = Wallpapers.gameFile(this, sorted[w])
                pickImage { uri -> if (Wallpapers.setFrom(this, uri, f)) render() else toast("No pude usar esa imagen") }
            }.show()
    }

    // ---------- Emulador GBA IA ----------

    private fun gbaSection() {
        body.addView(Ui.label(this, "Imagen por defecto"))
        gap(Ui.row(this, Settings.scaleLabel(Settings.scale(this)), "Cada juego puede tener la suya (Opciones del juego › Imagen)",
            Ui.button(this, "Cambiar") {
                val values = Settings.SCALES
                AlertDialog.Builder(this).setTitle("Imagen por defecto")
                    .setSingleChoiceItems(values.map { Settings.scaleLabel(it) }.toTypedArray(), values.indexOf(Settings.scale(this))) { d, w ->
                        Settings.setScale(this, values[w]); d.dismiss(); render()
                    }.show()
            }), 0)
        val fps = Settings.showFps(this)
        gap(Ui.row(this, "Mostrar FPS y uso", "La línea amarilla de arriba durante el juego", Ui.button(this, if (fps) "Activado" else "Apagado", primary = fps) {
            Settings.setShowFps(this, !fps); render()
        }))
        gap(Ui.row(this, "Abrir un archivo suelto", "Un .gba o .zip que no esté en la carpeta de juegos", Ui.button(this, "Elegir") {
            pickFile { uri -> startActivity(Intent(this, EmulatorActivity::class.java).setData(uri)) }
        }))
    }
}
