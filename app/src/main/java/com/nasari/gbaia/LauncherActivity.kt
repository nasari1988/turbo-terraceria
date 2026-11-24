package com.nasari.gbaia

import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import kotlin.concurrent.thread

/** Launcher propio: carrusel de consolas → carrusel de juegos → jugar. */
class LauncherActivity : PickActivity() {
    private enum class Screen { SETUP, SYSTEMS, GAMES }
    private enum class Filter(val label: String) { ALL("Todos"), FAV("Favoritos"), RECENT("Recientes") }

    private lateinit var bg: ImageView
    private lateinit var dim: View
    private lateinit var content: FrameLayout

    private var screen = Screen.SYSTEMS
    private var systems: List<GameSystem> = emptyList()
    private var gamesBySystem: Map<String, List<Game>> = emptyMap()
    private var system: GameSystem? = null
    private var filter = Filter.ALL
    private var games: List<Game> = emptyList()
    private var carousel: CarouselView? = null
    private var scanning = false
    private var askedNames = false

    // Vistas de la pantalla actual que cambian con la selección
    private var infoUpdate: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bg = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        dim = View(this)
        content = FrameLayout(this)
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Ui.BG)
            addView(bg, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(dim, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(content, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        })
        Wallpapers.pickRandom(this)
        hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        if (!hasFilesAccess() || LauncherPrefs.romRoot(this) == null) {
            showSetup()
            return
        }
        loadLibrary(rescan = Library.games(this).isEmpty())
    }

    private fun loadLibrary(rescan: Boolean) {
        gamesBySystem = Library.bySystem(this)
        systems = Systems.ALL.filter { (gamesBySystem[it.id]?.size ?: 0) > 0 }
        when {
            screen == Screen.GAMES && system != null && systems.contains(system) -> showGames(system!!, keepSelection = true)
            else -> showSystems()
        }
        if (rescan && !scanning) {
            scanning = true
            thread(name = "buscar-juegos") {
                Library.scan(this)
                val dirs = Library.brokenDirs(this)
                val broken = dirs.sumOf { Native.fixNames(it, false) }
                runOnUiThread {
                    scanning = false
                    loadLibrary(rescan = false)
                    if (broken > 0 && !askedNames) {
                        askedNames = true
                        offerFixNames(dirs, broken)
                    }
                }
            }
        }
    }

    /** Archivos con la ñ o acentos dañados en el nombre: así no se pueden abrir. */
    private fun offerFixNames(dirs: List<String>, count: Int) {
        AlertDialog.Builder(this)
            .setTitle("Nombres con la ñ dañada")
            .setMessage("Encontré $count ${if (count == 1) "archivo" else "archivos"} cuyo nombre tiene la ñ o un acento guardado mal (se ve como �). Así los emuladores no los pueden abrir.\n\n¿Los corrijo? Solo cambia el nombre del archivo, por ejemplo \"Espa�ol\" → \"Español\".")
            .setPositiveButton("Corregir") { _, _ ->
                thread {
                    val n = dirs.sumOf { Native.fixNames(it, true) }
                    Library.scan(this)
                    runOnUiThread {
                        toast("Corregí $n ${if (n == 1) "nombre" else "nombres"}")
                        loadLibrary(rescan = false)
                    }
                }
            }
            .setNegativeButton("Ahora no", null)
            .show()
    }

    // ---------- Fondo ----------

    private fun applyWallpaper(sys: String?, game: Game?): Wallpapers.Source {
        val (file, source) = Wallpapers.resolve(this, sys, game)
        val bmp = file?.let { Wallpapers.load(it, 1600) }
        bg.setImageBitmap(bmp)
        val blur = LauncherPrefs.blur(this)
        bg.setRenderEffect(if (bmp != null && blur > 0) RenderEffect.createBlurEffect(blur * 8f, blur * 8f, Shader.TileMode.CLAMP) else null)
        dim.setBackgroundColor(Color.argb(if (bmp == null) 0 else (LauncherPrefs.dim(this) * 255 / 100), 10, 11, 15))
        return source
    }

    // ---------- Primer inicio ----------

    private fun showSetup() {
        screen = Screen.SETUP
        applyWallpaper(null, null)
        val needAccess = !hasFilesAccess()
        val accent = Ui.accent(this)
        content.removeAllViews()
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(56), dp(24), dp(56), dp(24))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(Ui.text(context, if (needAccess) "PASO 1 DE 2" else "PASO 2 DE 2", 12f, accent, bold = true).apply { letterSpacing = 0.1f })
                addView(Ui.text(context,
                    if (needAccess) "Dale permiso para ver tus archivos" else "Elige la carpeta donde guardas tus juegos",
                    30f, Ui.TEXT, bold = true).apply { setPadding(0, dp(12), 0, dp(12)) })
                addView(Ui.text(context,
                    if (needAccess) "El launcher necesita leer la carpeta de juegos y pasárselos a los emuladores (RetroArch, DuckStation…). En la pantalla que se abre, activa \"Permitir acceso a todos los archivos\"."
                    else "El launcher la revisa y acomoda cada juego en su consola. Si tus juegos están sueltos, los ordena por tipo de archivo (.gba, .sfc, .cue, .nds…).",
                    14f, Ui.TEXT2).apply { setLineSpacing(0f, 1.3f) })
                addView(LinearLayout(context).apply {
                    setPadding(0, dp(18), 0, 0)
                    addView(Ui.button(context, if (needAccess) "Dar permiso" else "Elegir carpeta", primary = true) {
                        if (needAccess) askFilesAccess() else pickFolder { path ->
                            if (path == null || !File(path).isDirectory) toast("No pude usar esa carpeta")
                            else {
                                LauncherPrefs.setRomRoot(this@LauncherActivity, path)
                                loadLibrary(rescan = true)
                            }
                        }
                    })
                })
            }, Ui.lp(0, WRAP_CONTENT, 1f))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(16))
                background = Ui.round(Ui.CARD2, dp(16).toFloat())
                addView(Ui.text(context, "Ejemplo de carpeta", 12f, Ui.MUTED).apply { setPadding(0, 0, 0, dp(8)) })
                addView(Ui.text(context, "Juegos", 13f, Ui.TEXT, bold = true))
                for (l in listOf("gba / Pokemon Rojo Fuego.gba", "gba / Mario Kart.zip", "psx / juego.cue + juego.bin", "snes / juego.sfc", "nds / juego.nds", "… las demás consolas igual"))
                    addView(Ui.text(context, l, 13f, Ui.TEXT2).apply { setPadding(dp(16), dp(5), 0, 0) })
            }, LinearLayout.LayoutParams(dp(300), WRAP_CONTENT).apply { leftMargin = dp(40) })
        }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    // ---------- Consolas ----------

    private fun showSystems() {
        screen = Screen.SYSTEMS
        val accent = Ui.accent(this)
        content.removeAllViews()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // Encabezado
        col.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(14), dp(24), 0)
            addView(Ui.text(context, "Mi Launcher", 18f, Ui.TEXT, bold = true), Ui.lp(0, WRAP_CONTENT, 1f))
            addView(iconButton(Icon.Kind.SEARCH, "Buscar juego") { showSearch() }, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(iconButton(Icon.Kind.GEAR, "Ajustes") { openSettings() }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin = dp(8) })
        })

        if (systems.isEmpty()) {
            col.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(Ui.text(context, if (scanning) "Buscando juegos…" else "No encontré juegos", 22f, Ui.TEXT, bold = true))
                addView(Ui.text(context, "Carpeta: ${LauncherPrefs.romRoot(context)}", 13f, Ui.MUTED).apply { setPadding(0, dp(8), 0, dp(16)) })
                if (!scanning) addView(LinearLayout(context).apply {
                    addView(Ui.button(context, "Buscar otra vez", primary = true) { loadLibrary(rescan = true); showSystems() })
                    addView(Ui.button(context, "Cambiar carpeta") { openSettings() }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(10) })
                })
            }, Ui.lp(MATCH_PARENT, 0, 1f))
            content.addView(col)
            applyWallpaper(null, null)
            return
        }

        val c = CarouselView(this).apply {
            this.accent = accent
            cardW = 300f
            cardH = 200f
            items = systems.map { s ->
                CarouselView.Item(s.short, s.name, Ui.CARD)
            }
        }
        carousel = c
        val sub = Ui.text(this, "", 14f, Ui.TEXT).apply { gravity = Gravity.CENTER }
        val dots = LinearLayout(this).apply { gravity = Gravity.CENTER }
        infoUpdate = {
            val s = systems[c.selected]
            val n = gamesBySystem[s.id]?.size ?: 0
            sub.text = android.text.SpannableStringBuilder().apply {
                append("$n ${if (n == 1) "juego" else "juegos"} · abre con ")
                val start = length
                append(Launch.launcherFor(this@LauncherActivity, s).label)
                setSpan(android.text.style.ForegroundColorSpan(accent), start, length, 0)
                setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, length, 0)
            }
            dots.removeAllViews()
            systems.indices.forEach { k ->
                dots.addView(View(this).apply { background = Ui.round(if (k == c.selected) accent else Color.rgb(0x3A, 0x3F, 0x4B), dp(3).toFloat()) },
                    LinearLayout.LayoutParams(if (k == c.selected) dp(22) else dp(6), dp(6)).apply { leftMargin = dp(3); rightMargin = dp(3) })
            }
            applyWallpaper(s.id, null)
            LauncherPrefs.setLastSystem(this, s.id)
        }
        c.onSelected = { infoUpdate?.invoke() }
        c.onActivate = { showGames(systems[it]) }

        col.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
            addView(iconButton(Icon.Kind.BACK, "Consola anterior") { c.move(-1) }, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(c, Ui.lp(0, MATCH_PARENT, 1f))
            addView(iconButton(Icon.Kind.NEXT, "Consola siguiente") { c.move(1) }, LinearLayout.LayoutParams(dp(44), dp(44)))
        }, Ui.lp(MATCH_PARENT, 0, 1f))
        col.addView(sub, Ui.lp(MATCH_PARENT, WRAP_CONTENT))
        col.addView(dots, Ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        col.addView(footer("◀ ▶ cambiar consola", "A entrar", "Start ajustes", "Y buscar"))
        content.addView(col)

        val last = systems.indexOfFirst { it.id == (system?.id ?: LauncherPrefs.lastSystem(this)) }.coerceAtLeast(0)
        c.scrollTo(last, animate = false)
        infoUpdate?.invoke()
    }

    // ---------- Juegos ----------

    private fun filtered(s: GameSystem): List<Game> {
        val all = gamesBySystem[s.id] ?: emptyList()
        return when (filter) {
            Filter.ALL -> all
            Filter.FAV -> all.filter { GameDb.isFavorite(this, it.path) }
            Filter.RECENT -> all.filter { GameDb.lastPlayed(this, it.path) > 0 }.sortedByDescending { GameDb.lastPlayed(this, it.path) }
        }
    }

    private fun showGames(s: GameSystem, keepSelection: Boolean = false, selectPath: String? = null) {
        val previous = if (keepSelection) games.getOrNull(carousel?.selected ?: 0)?.path else selectPath
        screen = Screen.GAMES
        system = s
        games = filtered(s)
        val accent = Ui.accent(this)
        content.removeAllViews()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val pos = Ui.text(this, "", 13f, Ui.TEXT2)
        val chip = Ui.text(this, "", 11f, Ui.TEXT).apply {
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = Ui.round(Color.argb(30, 255, 255, 255), dp(10).toFloat())
        }
        col.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(24), 0)
            addView(ImageView(context).apply {
                setImageDrawable(Icon(Icon.Kind.BACK, Ui.TEXT, dp(1).toFloat()))
                contentDescription = "Volver a consolas"
                setPadding(dp(12), dp(12), dp(12), dp(12))
                isFocusable = true
                setOnClickListener { showSystems() }
            }, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(Ui.text(context, s.name, 18f, Ui.TEXT, bold = true))
            addView(pos, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(10) })
            addView(chip, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(10) })
            addView(View(context), Ui.lp(0, 1, 1f))
            for (f in Filter.values()) {
                val on = f == filter
                addView(Ui.text(context, f.label, 13f, if (on) Ui.accentLight(context) else Ui.TEXT2).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(14), 0, dp(14), 0)
                    background = Ui.ripple(Ui.round(if (on) Ui.accentSoft(context) else Ui.CARD, dp(18).toFloat(), dp(1), if (on) accent else Ui.LINE))
                    isFocusable = true
                    setOnClickListener {
                        filter = f
                        showGames(s)
                    }
                }, LinearLayout.LayoutParams(WRAP_CONTENT, dp(36)).apply { leftMargin = dp(6) })
            }
        })

        if (games.isEmpty()) {
            col.addView(Ui.text(this, if (filter == Filter.FAV) "Aún no marcas favoritos aquí (botón Y o en Opciones)" else "Aún no has jugado nada de esta consola", 16f, Ui.MUTED).apply {
                gravity = Gravity.CENTER
            }, Ui.lp(MATCH_PARENT, 0, 1f))
            col.addView(footer("B volver", "L / R cambiar filtro"))
            content.addView(col)
            carousel = null
            applyWallpaper(s.id, null)
            return
        }

        val c = CarouselView(this).apply {
            this.accent = accent
            cardW = 200f
            cardH = 200f
        }
        carousel = c
        fun itemsNow() = games.map { g ->
            CarouselView.Item(
                initials(g.title), "", coverColor(g),
                bitmap = { Covers.get(this, g) { c.invalidate() } },
                badge = GameDb.isFavorite(this, g.path),
            )
        }
        c.items = itemsNow()

        // Panel de información
        val title = Ui.text(this, "", 22f, Ui.TEXT, bold = true)
        val meta = Ui.text(this, "", 13f, Ui.MUTED).apply { setPadding(0, dp(6), 0, dp(10)) }
        val lastV = statValue()
        val timeV = statValue()
        val modeV = statValue()
        val playBtn = Ui.button(this, "Jugar", primary = true) { play(games[c.selected]) }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title)
            addView(meta)
            addView(GridLayout(context).apply {
                columnCount = 2
                addView(stat("Última vez", lastV), GridLayout.LayoutParams(GridLayout.spec(0), GridLayout.spec(0, 1f)).apply { width = 0; rightMargin = dp(8); bottomMargin = dp(8) })
                addView(stat("Tiempo jugado", timeV), GridLayout.LayoutParams(GridLayout.spec(0), GridLayout.spec(1, 1f)).apply { width = 0; bottomMargin = dp(8) })
                addView(stat("Imagen / emulador", modeV), GridLayout.LayoutParams(GridLayout.spec(1), GridLayout.spec(0, 2, 1f)).apply { width = 0 })
            }, Ui.lp(MATCH_PARENT, WRAP_CONTENT))
            addView(LinearLayout(context).apply {
                setPadding(0, dp(12), 0, 0)
                addView(playBtn, Ui.lp(0, dp(46), 1f))
                addView(iconButton(Icon.Kind.DOTS, "Opciones del juego") { showOptions(games[c.selected]) }, LinearLayout.LayoutParams(dp(46), dp(46)).apply { leftMargin = dp(8) })
            })
        }

        infoUpdate = {
            val g = games[c.selected]
            pos.text = "${c.selected + 1} de ${games.size}"
            title.text = g.title
            meta.text = "${s.short} · ${g.fileName}"
            lastV.text = GameDb.whenText(GameDb.lastPlayed(this, g.path))
            val launcher = Launch.launcherFor(this, s)
            val internal = Launch.isInternal(launcher)
            timeV.text = if (internal) GameDb.durationText(GameDb.playTime(this, g.path)) else "Solo en los emuladores integrados"
            modeV.text = when (launcher) {
                Launcher.Internal -> Settings.scaleLabel(GameDb.mode(this, g.path) ?: Settings.scale(this)).substringBefore(" (")
                Launcher.InternalSnes -> "SNES IA · Mode 7 " + SnesActivity.MODE7_LABELS[SnesActivity.MODE7_VALUES.indexOf(SnesActivity.mode7(this)).coerceAtLeast(0)].substringBefore(" (")
                else -> launcher.label
            }
            playBtn.text = if (internal && Launch.stateFile(this, g, "auto").exists()) "Continuar" else "Jugar"
            chip.text = applyWallpaper(s.id, g).label
        }
        c.onSelected = { infoUpdate?.invoke() }
        c.onActivate = { play(games[it]) }

        col.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(24), 0)
            addView(iconButton(Icon.Kind.BACK, "Juego anterior") { c.move(-1) }, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(c, Ui.lp(0, MATCH_PARENT, 1f))
            addView(iconButton(Icon.Kind.NEXT, "Juego siguiente") { c.move(1) }, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(panel, LinearLayout.LayoutParams(dp(280), MATCH_PARENT).apply { leftMargin = dp(16) })
        }, Ui.lp(MATCH_PARENT, 0, 1f))
        col.addView(footer("◀ ▶ cambiar juego", "A jugar", "B volver", "Y favorito", "Select opciones"))
        content.addView(col)

        val idx = previous?.let { p -> games.indexOfFirst { it.path == p } }?.takeIf { it >= 0 } ?: 0
        c.scrollTo(idx, animate = false)
        infoUpdate?.invoke()
    }

    private fun statValue() = Ui.text(this, "", 13f, Ui.TEXT, bold = true)

    private fun stat(label: String, value: TextView) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = Ui.round(Color.argb(220, 0x19, 0x1C, 0x23), dp(10).toFloat())
        addView(Ui.text(context, label, 11f, Ui.MUTED).apply { setPadding(0, 0, 0, dp(3)) })
        addView(value)
    }

    private fun footer(vararg hints: String) = LinearLayout(this).apply {
        gravity = Gravity.CENTER
        setPadding(0, dp(6), 0, dp(12))
        for (h in hints) addView(Ui.text(context, h, 12f, Ui.MUTED).apply { setPadding(dp(11), 0, dp(11), 0) })
    }

    private fun initials(t: String): String {
        val words = t.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() && it.lowercase() !in setOf("the", "de", "la", "el", "of", "and", "y") }
        return words.take(4).joinToString("") { it.take(1).uppercase() }.ifEmpty { "?" }
    }

    private fun coverColor(g: Game): Int {
        val h = (g.title.hashCode() and 0x7fffffff) % 360
        return Color.HSVToColor(floatArrayOf(h.toFloat(), 0.55f, 0.55f))
    }

    // ---------- Acciones ----------

    private fun play(g: Game, state: String? = null) {
        val sys = Systems.byId(g.system) ?: return
        val st = state ?: if (Launch.isInternal(Launch.launcherFor(this, sys)) &&
            Launch.stateFile(this, g, "auto").exists()) "auto" else null
        Launch.start(this, g, st)?.let { toast(it) }
    }

    private fun toggleFavorite(g: Game) {
        val on = !GameDb.isFavorite(this, g.path)
        GameDb.setFavorite(this, g.path, on)
        toast(if (on) "Agregado a favoritos" else "Quitado de favoritos")
        system?.let { showGames(it, keepSelection = true) }
    }

    private fun openSettings() = startActivity(Intent(this, LauncherSettingsActivity::class.java))

    private fun showSearch() {
        val all = Library.games(this).sortedBy { it.title.lowercase() }
        val input = EditText(this).apply {
            hint = "Nombre del juego"
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.MUTED)
            isSingleLine = true
        }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
            addView(ScrollView(context).apply { addView(list) }, Ui.lp(MATCH_PARENT, dp(220)))
        }
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_NoActionBar)
            .setTitle("Buscar juego")
            .setView(box)
            .setNegativeButton("Cerrar", null)
            .create()
        fun refresh() {
            val q = Covers.normalize(input.text.toString())
            list.removeAllViews()
            all.filter { q.isBlank() || Covers.normalize(it.title).contains(q) }.take(40).forEach { g ->
                list.addView(Ui.text(this, "${g.title}  ·  ${Systems.byId(g.system)?.short}", 15f, Ui.TEXT).apply {
                    setPadding(dp(4), dp(12), dp(4), dp(12))
                    isFocusable = true
                    setOnClickListener {
                        dialog.dismiss()
                        filter = Filter.ALL
                        Systems.byId(g.system)?.let { s -> showGames(s, selectPath = g.path) }
                    }
                })
            }
        }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) = refresh()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        refresh()
        dialog.show()
    }

    // ---------- Opciones del juego ----------

    private fun showOptions(g: Game) {
        val sys = Systems.byId(g.system) ?: return
        val launcher = Launch.launcherFor(this, sys)
        val internal = launcher == Launcher.Internal
        val internalAny = Launch.isInternal(launcher)
        val dialog = Dialog(this, android.R.style.Theme_Material_NoActionBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        fun reopen() { dialog.dismiss(); showOptions(g) }

        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(16))
            setBackgroundColor(Ui.BG)
            val cover = Covers.file(this@LauncherActivity, g)?.let { Covers.decode(it, 400) }
            addView(if (cover != null) ImageView(context).apply {
                setImageBitmap(cover)
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                background = Ui.round(Ui.CARD, dp(14).toFloat())
            } else Ui.text(context, initials(g.title), 30f, Color.WHITE, bold = true).apply {
                gravity = Gravity.CENTER
                background = Ui.round(coverColor(g), dp(14).toFloat())
            }, LinearLayout.LayoutParams(dp(120), dp(120)))
            addView(Ui.text(context, g.title, 19f, Ui.TEXT, bold = true).apply { setPadding(0, dp(12), 0, dp(6)) })
            addView(Ui.text(context, "${sys.name} · ${g.fileName}", 12f, Ui.MUTED))
            addView(View(context), Ui.lp(1, 0, 1f))
            addView(Ui.button(context, "Volver") { dialog.dismiss() }, Ui.lp(MATCH_PARENT, dp(44)))
        }

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(14), dp(24), dp(14))
            addView(Ui.label(context, "Opciones de este juego"))
            if (launcher == Launcher.InternalSnes) {
                val cur = SnesActivity.MODE7_VALUES.indexOf(SnesActivity.mode7(context)).coerceAtLeast(0)
                addView(Ui.row(context, "Mode 7 en HD", "Para todos los juegos de SNES (también se cambia jugando, en el menú)",
                    Ui.button(context, SnesActivity.MODE7_LABELS[cur] + " ▾") {
                        AlertDialog.Builder(this@LauncherActivity)
                            .setTitle("Mode 7 en HD")
                            .setSingleChoiceItems(SnesActivity.MODE7_LABELS.toTypedArray(), cur) { d, w ->
                                SnesActivity.setMode7(this@LauncherActivity, SnesActivity.MODE7_VALUES[w])
                                d.dismiss()
                                reopen()
                            }.show()
                    }), Ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(10) })
            }
            if (internal) {
                val own = GameDb.mode(context, g.path)
                val label = if (own == null) "Igual que la app (${Settings.scaleLabel(Settings.scale(context)).substringBefore(" (")})" else Settings.scaleLabel(own)
                addView(Ui.row(context, "Imagen", "Solo para este juego; si no, usa la de la app",
                    Ui.button(context, "$label ▾") {
                        val opts = listOf<Int?>(null) + Settings.SCALES
                        AlertDialog.Builder(this@LauncherActivity)
                            .setTitle("Imagen para ${g.title}")
                            .setSingleChoiceItems(opts.map { if (it == null) "Igual que la app" else Settings.scaleLabel(it) }.toTypedArray(), opts.indexOf(own)) { d, w ->
                                GameDb.setMode(this@LauncherActivity, g.path, opts[w])
                                d.dismiss()
                                reopen()
                            }.show()
                    }), Ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(10) })
            }
            if (internalAny) {
                // Partidas guardadas
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), dp(10), dp(14), dp(12))
                    background = Ui.round(Ui.CARD2, dp(12).toFloat())
                    addView(Ui.text(context, "Partidas guardadas (toca para jugar desde ahí)", 14f, Ui.TEXT, bold = true).apply { setPadding(0, 0, 0, dp(8)) })
                    addView(LinearLayout(context).apply {
                        for ((slot, name) in listOf("auto" to "Automática", "1" to "Ranura 1", "2" to "Ranura 2", "3" to "Ranura 3")) {
                            val f = Launch.stateFile(this@LauncherActivity, g, slot)
                            addView(LinearLayout(context).apply {
                                orientation = LinearLayout.VERTICAL
                                gravity = Gravity.CENTER_VERTICAL
                                setPadding(dp(10), 0, dp(10), 0)
                                background = Ui.ripple(Ui.round(Color.rgb(0x1D, 0x20, 0x28), dp(10).toFloat(), dp(1), Ui.LINE))
                                addView(Ui.text(context, name, 12f, Ui.TEXT, bold = true))
                                addView(Ui.text(context, if (f.exists()) GameDb.whenText(f.lastModified()) else "Vacía", 11f, Ui.MUTED).apply { setPadding(0, dp(3), 0, 0) })
                                isFocusable = true
                                setOnClickListener {
                                    if (!f.exists()) toast("Esa ranura está vacía") else { dialog.dismiss(); play(g, slot) }
                                }
                            }, Ui.lp(0, dp(56), 1f).apply { rightMargin = dp(8) })
                        }
                    })
                }, Ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(10) })
            }
            if (sys.launchers.size > 1) {
                addView(Ui.row(context, "Emulador", "Se cambia para toda la consola ${sys.short}",
                    Ui.button(context, Launch.launcherFor(context, sys).label + " ▾") { chooseLauncher(sys) { reopen() } }),
                    Ui.lp(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(10) })
            }
            val fav = GameDb.isFavorite(context, g.path)
            addView(LinearLayout(context).apply {
                addView(Ui.button(context, if (fav) "★ Favorito" else "☆ Favorito") {
                    GameDb.setFavorite(this@LauncherActivity, g.path, !fav)
                    system?.let { showGames(it, keepSelection = true) }
                    reopen()
                }, Ui.lp(0, dp(52), 1f).apply { rightMargin = dp(8) })
                addView(Ui.button(context, "Carátula") { coverMenu(g) { reopen() } }, Ui.lp(0, dp(52), 1f).apply { rightMargin = dp(8) })
                addView(Ui.button(context, "Controles") { controlsHelp(internalAny) }, Ui.lp(0, dp(52), 1f).apply { rightMargin = dp(8) })
                addView(Ui.button(context, "Fondo") { wallpaperMenu(g) { reopen() } }, Ui.lp(0, dp(52), 1f))
            })
        }

        dialog.setContentView(LinearLayout(this).apply {
            setBackgroundColor(Color.rgb(0x0B, 0x0C, 0x10))
            addView(left, LinearLayout.LayoutParams(dp(250), MATCH_PARENT))
            addView(ScrollView(context).apply { addView(right) }, Ui.lp(0, MATCH_PARENT, 1f))
        })
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        dialog.setOnDismissListener { hideSystemBars(); infoUpdate?.invoke() }
        dialog.show()
        dialog.window?.let { w ->
            w.setDecorFitsSystemWindows(false)
            w.insetsController?.hide(android.view.WindowInsets.Type.systemBars())
        }
    }

    fun chooseLauncher(sys: GameSystem, done: () -> Unit) {
        val opts = sys.launchers
        val cur = opts.indexOf(Launch.launcherFor(this, sys))
        AlertDialog.Builder(this)
            .setTitle("Abrir ${sys.short} con")
            .setSingleChoiceItems(opts.map { it.label + if (Launch.isAvailable(this, it)) "" else "  (no instalado)" }.toTypedArray(), cur) { d, w ->
                if (!Launch.isAvailable(this, opts[w])) toast("${opts[w].label} no está instalado")
                else {
                    LauncherPrefs.setLauncherIndex(this, sys.id, w)
                    d.dismiss()
                    done()
                }
            }.show()
    }

    private fun coverMenu(g: Game, done: () -> Unit) {
        val own = Covers.ownFile(this, g)
        val items = mutableListOf("Elegir imagen de la galería", "Buscar otra vez en internet")
        if (own.exists()) items += "Quitar la mía"
        AlertDialog.Builder(this).setTitle("Carátula").setItems(items.toTypedArray()) { _, w ->
            when (w) {
                0 -> pickImage { uri ->
                    if (Wallpapers.setFrom(this, uri, own)) { Covers.forget(this, g); done() } else toast("No pude usar esa imagen")
                }
                1 -> { Covers.forget(this, g); toast("Buscando…"); done() }
                2 -> { own.delete(); Covers.forget(this, g); done() }
            }
        }.show()
    }

    private fun wallpaperMenu(g: Game, done: () -> Unit) {
        val f = Wallpapers.gameFile(this, g)
        val items = mutableListOf("Elegir imagen de la galería")
        if (f.exists()) items += "Quitar (usar el de la consola)"
        AlertDialog.Builder(this)
            .setTitle("Fondo de ${g.title}")
            .setMessage(if (Launch.isInternal(Launch.launcherFor(this, Systems.byId(g.system)!!)))
                "También puedes poner una captura: en el juego, menú › \"Usar como fondo\"." else null)
            .setItems(items.toTypedArray()) { _, w ->
                when (w) {
                    0 -> pickImage { uri -> if (Wallpapers.setFrom(this, uri, f)) done() else toast("No pude usar esa imagen") }
                    1 -> { Wallpapers.remove(f); done() }
                }
            }.show()
    }

    private fun controlsHelp(internal: Boolean) {
        AlertDialog.Builder(this)
            .setTitle("Controles")
            .setMessage(if (internal)
                "Táctiles: aparecen al tocar la pantalla.\nControl Bluetooth: cruceta o palanca, A, B, L, R, Start y Select. Select + Start (o el botón central) abre el menú del juego."
            else "Este juego se abre en otro emulador: los controles se configuran dentro de esa app.")
            .setPositiveButton("Entendido", null)
            .show()
    }

    // ---------- Control (Bluetooth / teclado) ----------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) {
            return if (isNavKey(event.keyCode)) true else super.dispatchKeyEvent(event)
        }
        val c = carousel
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { c?.move(-1); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { c?.move(1); return true }
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                when (screen) {
                    Screen.SYSTEMS -> if (systems.isNotEmpty() && c != null) showGames(systems[c.selected])
                    Screen.GAMES -> if (games.isNotEmpty() && c != null) play(games[c.selected])
                    Screen.SETUP -> return super.dispatchKeyEvent(event)
                }
                return true
            }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                if (screen == Screen.GAMES) { showSystems(); return true }
                return true // el launcher no se cierra con Atrás
            }
            KeyEvent.KEYCODE_BUTTON_Y -> {
                if (screen == Screen.GAMES && games.isNotEmpty() && c != null) toggleFavorite(games[c.selected])
                else if (screen == Screen.SYSTEMS) showSearch()
                return true
            }
            KeyEvent.KEYCODE_BUTTON_START -> { openSettings(); return true }
            KeyEvent.KEYCODE_BUTTON_SELECT -> {
                if (screen == Screen.GAMES && games.isNotEmpty() && c != null) showOptions(games[c.selected])
                return true
            }
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1 -> {
                if (screen == Screen.GAMES) {
                    val all = Filter.values()
                    val d = if (event.keyCode == KeyEvent.KEYCODE_BUTTON_R1) 1 else all.size - 1
                    filter = all[(filter.ordinal + d) % all.size]
                    system?.let { showGames(it) }
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isNavKey(code: Int) = code in setOf(
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
        KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
    )

    @Deprecated("Atrás se maneja en dispatchKeyEvent")
    override fun onBackPressed() {
        if (screen == Screen.GAMES) showSystems()
    }
}
