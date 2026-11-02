extends CanvasLayer
## Menús: principal, selección de pista y auto, pausa, resultados y torneo.
## Funciona aunque el juego esté en pausa.

const GameState := preload("res://scripts/game_state.gd")

var race: Node
var sfx: Node
var _sound_on := true
var _sound_button: Button

var _dim: ColorRect
var _main: VBoxContainer
var _tracks: VBoxContainer
var _cars: VBoxContainer
var _pause: VBoxContainer
var _results: VBoxContainer
var _tour: VBoxContainer
var _results_table: GridContainer
var _results_title: Label
var _results_extra: Label
var _results_buttons: HBoxContainer
var _tour_table: GridContainer
var _tour_title: Label
var _tour_extra: Label
var _tour_button: Button
var _laps_label: Label
var _laps := 3
var _difficulty_label: Label
var _pending := "carrera"     # "carrera", "practica" o "torneo"
var _chosen_track := "ovalo"
const SETTINGS_PATH := "user://ajustes.cfg"
const LAP_OPTIONS := [1, 3, 5, 10]


func _ready() -> void:
	layer = 10
	process_mode = Node.PROCESS_MODE_ALWAYS

	_dim = ColorRect.new()
	_dim.color = Color(0, 0, 0, 0.5)
	_dim.set_anchors_preset(Control.PRESET_FULL_RECT)
	add_child(_dim)

	_build_main()
	_tracks = _panel()
	_cars = _panel()
	_build_pause()
	_build_results()
	_build_tournament()

	race.state_changed.connect(_on_state)
	race.player_finished.connect(func():
		await get_tree().create_timer(1.5).timeout
		_show_results())
	_on_state(race.state)


func _process(_delta: float) -> void:
	if Input.is_action_just_pressed("pausa"):
		if get_tree().paused:
			_resume()
		elif race.state == race.State.COUNTDOWN or race.state == race.State.RACING:
			_open_pause()


# ------------------------------------------------------------ menú principal

func _build_main() -> void:
	_main = _panel()
	_main.add_child(_label("TURBO TERRACERÍA", 64, Color("ffd166")))
	_main.add_child(_button("CARRERA RÁPIDA", func(): _choose("carrera")))
	_main.add_child(_button("TORNEO", func(): _choose("torneo")))
	_main.add_child(_button("PRÁCTICA LIBRE", func(): _choose("practica")))

	var row := HBoxContainer.new()
	row.alignment = BoxContainer.ALIGNMENT_CENTER
	row.add_theme_constant_override("separation", 10)
	row.add_child(_small_button("<", func(): _change_laps(-1)))
	_laps_label = _label("", 30)
	_laps_label.custom_minimum_size = Vector2(170, 0)
	row.add_child(_laps_label)
	row.add_child(_small_button(">", func(): _change_laps(1)))
	row.add_child(_spacer_w(24))
	row.add_child(_small_button("<", func(): _change_difficulty(-1)))
	_difficulty_label = _label("", 30)
	_difficulty_label.custom_minimum_size = Vector2(250, 0)
	row.add_child(_difficulty_label)
	row.add_child(_small_button(">", func(): _change_difficulty(1)))
	_main.add_child(row)

	_load_settings()
	_change_laps(0)
	_change_difficulty(0)

	_sound_button = _small_button("", _toggle_sound)
	_sound_button.custom_minimum_size = Vector2(420, 60)
	_sound_button.add_theme_font_size_override("font_size", 26)
	_main.add_child(_sound_button)
	_apply_sound()


func _choose(kind: String) -> void:
	_pending = kind
	if kind == "torneo":
		_chosen_track = GameState.TOURNAMENT_TRACKS[0]
		_fill_cars()
		_show(_cars)
	else:
		_fill_tracks()
		_show(_tracks)


func _fill_tracks() -> void:
	_clear(_tracks)
	_tracks.add_child(_label("ELIGE PISTA", 52, Color("ffd166")))
	for t in GameState.TRACKS:
		var open := GameState.is_track_unlocked(t.id)
		var text: String = "%s  ·  %s" % [t.name, t.theme]
		if open:
			var best: float = GameState.best_laps.get(t.id, INF)
			if best < INF:
				text += "   (mejor %s)" % race.format_time(best)
		else:
			text = "%s  —  %s" % [t.name, GameState.track_unlock_hint(t.id)]
		var b := _button(text, func(): _chosen_track = t.id; _fill_cars(); _show(_cars))
		b.custom_minimum_size = Vector2(760, 66)
		b.add_theme_font_size_override("font_size", 28 if open else 22)
		b.disabled = not open
		_tracks.add_child(b)
	_tracks.add_child(_back_button(func(): _show(_main)))


func _fill_cars() -> void:
	_clear(_cars)
	var title := "ELIGE AUTO" if _pending != "torneo" else "TORNEO · ELIGE AUTO"
	_cars.add_child(_label(title, 48, Color("ffd166")))
	var grid := GridContainer.new()
	grid.columns = 2
	grid.add_theme_constant_override("h_separation", 18)
	grid.add_theme_constant_override("v_separation", 12)
	for id in GameState.CAR_ORDER:
		grid.add_child(_car_card(id))
	_cars.add_child(grid)
	if _pending == "torneo":
		_cars.add_child(_back_button(func(): _show(_main)))
	else:
		_cars.add_child(_back_button(func(): _fill_tracks(); _show(_tracks)))


func _car_card(id: String) -> Control:
	var data: Dictionary = GameState.CARS[id]
	var open := GameState.is_car_unlocked(id)
	var card := VBoxContainer.new()
	card.add_theme_constant_override("separation", 2)
	var b := _button("#%d %s" % [data.number, data.name] if open else "%s (bloqueado)" % data.name,
		func(): _go(id))
	b.custom_minimum_size = Vector2(400, 58)
	b.add_theme_font_size_override("font_size", 28)
	b.disabled = not open
	if open:
		b.add_theme_stylebox_override("normal", _box(Color(data.color.r * 0.55, data.color.g * 0.55, data.color.b * 0.55, 0.95)))
	card.add_child(b)
	var desc: String = data.desc if open else GameState.CAR_UNLOCKS.get(id, "")
	card.add_child(_label(desc, 22, Color(1, 1, 1, 0.85)))
	var bars := GameState.car_bars(id)
	for stat in bars:
		var row := HBoxContainer.new()
		var l := _label(stat, 20, Color(1, 1, 1, 0.8), HORIZONTAL_ALIGNMENT_LEFT)
		l.custom_minimum_size = Vector2(150, 0)
		row.add_child(l)
		var bar := ProgressBar.new()
		bar.max_value = 5
		bar.value = bars[stat]
		bar.show_percentage = false
		bar.custom_minimum_size = Vector2(230, 16)
		bar.size_flags_vertical = Control.SIZE_SHRINK_CENTER
		var fill := StyleBoxFlat.new()
		fill.bg_color = Color("ffd166")
		fill.set_corner_radius_all(6)
		bar.add_theme_stylebox_override("fill", fill)
		var bg := StyleBoxFlat.new()
		bg.bg_color = Color(1, 1, 1, 0.15)
		bg.set_corner_radius_all(6)
		bar.add_theme_stylebox_override("background", bg)
		row.add_child(bar)
		card.add_child(row)
	return card


## Arranca: guarda lo elegido y recarga la escena con la pista nueva.
func _go(car_id: String) -> void:
	GameState.car_id = car_id
	GameState.track_id = _chosen_track
	GameState.laps = _laps
	GameState.mode = race.Mode.PRACTICE if _pending == "practica" else race.Mode.RACE
	if _pending == "torneo":
		GameState.tournament = {"active": true, "index": 0, "points": {}}
	else:
		GameState.tournament = {}
	GameState.autostart = true
	get_tree().paused = false
	get_tree().reload_current_scene()


# --------------------------------------------------------- pausa y resultados

func _build_pause() -> void:
	_pause = _panel()
	_pause.add_child(_label("PAUSA", 70, Color("ffd166")))
	_pause.add_child(_spacer(10))
	_pause.add_child(_button("CONTINUAR", _resume))
	_pause.add_child(_button("REINICIAR", func(): _resume(); race.start(race.mode, race.laps)))
	_pause.add_child(_button("MENÚ PRINCIPAL", func():
		_resume()
		GameState.tournament = {}
		race.to_menu()))


func _build_results() -> void:
	_results = _panel()
	_results.add_theme_constant_override("separation", 8)
	_results_title = _label("", 54, Color("ffd166"))
	_results.add_child(_results_title)
	_results_table = GridContainer.new()
	_results_table.columns = 4
	_results_table.add_theme_constant_override("h_separation", 34)
	_results_table.add_theme_constant_override("v_separation", 2)
	_results.add_child(_results_table)
	_results_extra = _label("", 26, Color("8ef58e"))
	_results.add_child(_results_extra)
	_results_buttons = HBoxContainer.new()
	_results_buttons.alignment = BoxContainer.ALIGNMENT_CENTER
	_results_buttons.add_theme_constant_override("separation", 20)
	_results.add_child(_results_buttons)


func _show_results() -> void:
	if race.state != race.State.FINISHED:
		return
	var pos: int = race.player_position()
	_results_title.text = "¡GANASTE!" if pos == 1 else "Llegaste %d° de %d" % [pos, race.cars.size()]
	_clear(_results_table)
	var tour: bool = race.is_tournament()
	var standings: Array = race.standings()
	for i in standings.size():
		var c: Node3D = standings[i]
		var color := Color("ffd166") if c == race.player else Color.WHITE
		_results_table.add_child(_label("%d°" % (i + 1), 30, color))
		_results_table.add_child(_label("#%d %s" % [c.number, c.display_name], 30, color, HORIZONTAL_ALIGNMENT_LEFT))
		var t: String = race.format_time(c.finish_time) if c.finished else "en pista"
		_results_table.add_child(_label(t, 30, color))
		var pts: String = "+%d pts" % race.points_gained.get(c.display_name, 0) if tour else ""
		_results_table.add_child(_label(pts, 30, Color("8ef58e")))
	var extra: String = "Tu mejor vuelta: " + race.format_time(race.best_lap)
	for u in race.last_unlocks:
		extra += "\n¡Desbloqueaste! " + u
	_results_extra.text = extra

	_clear(_results_buttons)
	if tour:
		_results_buttons.add_child(_button("VER TORNEO", _show_tournament))
	else:
		_results_buttons.add_child(_button("REVANCHA", func(): race.start(race.mode, race.laps)))
		_results_buttons.add_child(_button("MENÚ", func(): race.to_menu()))
	_show(_results)


func _build_tournament() -> void:
	_tour = _panel()
	_tour.add_theme_constant_override("separation", 8)
	_tour_title = _label("", 50, Color("ffd166"))
	_tour.add_child(_tour_title)
	_tour_table = GridContainer.new()
	_tour_table.columns = 3
	_tour_table.add_theme_constant_override("h_separation", 40)
	_tour.add_child(_tour_table)
	_tour_extra = _label("", 28, Color("8ef58e"))
	_tour.add_child(_tour_extra)
	_tour_button = _button("", func(): pass)
	_tour.add_child(_tour_button)


func _show_tournament() -> void:
	var t: Dictionary = GameState.tournament
	var idx: int = t.index
	var last: bool = race.is_last_tournament_race()
	_tour_title.text = "TORNEO · carrera %d de %d" % [idx + 1, GameState.TOURNAMENT_TRACKS.size()]
	_clear(_tour_table)
	var list: Array = race.tournament_standings()
	for i in list.size():
		var mine: bool = list[i][0] == race.player.display_name
		var color := Color("ffd166") if mine else Color.WHITE
		_tour_table.add_child(_label("%d°" % (i + 1), 32, color))
		_tour_table.add_child(_label(list[i][0], 32, color, HORIZONTAL_ALIGNMENT_LEFT))
		_tour_table.add_child(_label("%d pts" % list[i][1], 32, color))
	for c in _tour_button.pressed.get_connections():
		_tour_button.pressed.disconnect(c.callable)
	if last:
		var champion: bool = list[0][0] == race.player.display_name
		_tour_title.text = "¡CAMPEÓN DEL TORNEO!" if champion else "TORNEO TERMINADO"
		_tour_extra.text = "Ganó " + list[0][0] if not champion else "¡Felicidades!"
		_tour_button.text = "MENÚ"
		_tour_button.pressed.connect(func():
			GameState.tournament = {}
			race.to_menu())
	else:
		var next: String = GameState.TOURNAMENT_TRACKS[idx + 1]
		_tour_extra.text = "Siguiente: " + GameState.track_info(next).name
		_tour_button.text = "SIGUIENTE CARRERA"
		_tour_button.pressed.connect(func():
			t.index = idx + 1
			GameState.track_id = next
			GameState.autostart = true
			get_tree().reload_current_scene())
	_show(_tour)


func _on_state(s: int) -> void:
	if s == race.State.MENU:
		_show(_main)
	elif s == race.State.COUNTDOWN or s == race.State.RACING:
		_show(null)


func _show(panel: Control) -> void:
	for p in [_main, _tracks, _cars, _pause, _results, _tour]:
		p.get_parent().visible = p == panel
	_dim.visible = panel != null


# ---------------------------------------------------------------- acciones

func _change_difficulty(step: int) -> void:
	race.difficulty = clampi(race.difficulty + step, 0, race.DIFFICULTIES.size() - 1)
	var colors := [Color("8ef58e"), Color.WHITE, Color("ff6b6b")]
	_difficulty_label.text = "Rivales: " + race.DIFFICULTIES[race.difficulty].name
	_difficulty_label.add_theme_color_override("font_color", colors[race.difficulty])
	if step != 0:
		_save_settings()


func _toggle_sound() -> void:
	_sound_on = not _sound_on
	_apply_sound()
	_save_settings()


func _apply_sound() -> void:
	if sfx:
		sfx.set_muted(not _sound_on)
	_sound_button.text = "Sonido: " + ("SÍ" if _sound_on else "NO")


func _load_settings() -> void:
	var cfg := ConfigFile.new()
	if cfg.load(SETTINGS_PATH) == OK:
		_laps = cfg.get_value("carrera", "vueltas", 3)
		race.difficulty = cfg.get_value("carrera", "dificultad", 1)
		_sound_on = cfg.get_value("general", "sonido", true)


func _save_settings() -> void:
	var cfg := ConfigFile.new()
	cfg.set_value("carrera", "vueltas", _laps)
	cfg.set_value("carrera", "dificultad", race.difficulty)
	cfg.set_value("general", "sonido", _sound_on)
	cfg.save(SETTINGS_PATH)


func _change_laps(step: int) -> void:
	var i := LAP_OPTIONS.find(_laps)
	i = clampi(i + step, 0, LAP_OPTIONS.size() - 1)
	_laps = LAP_OPTIONS[i]
	_laps_label.text = "%d vuelta%s" % [_laps, "" if _laps == 1 else "s"]
	if step != 0:
		_save_settings()


func _open_pause() -> void:
	get_tree().paused = true
	_show(_pause)


func _resume() -> void:
	get_tree().paused = false
	_show(null)


# ---------------------------------------------------------------- ayudas

func _panel() -> VBoxContainer:
	var center := CenterContainer.new()
	center.set_anchors_preset(Control.PRESET_FULL_RECT)
	add_child(center)
	var box := VBoxContainer.new()
	box.alignment = BoxContainer.ALIGNMENT_CENTER
	box.add_theme_constant_override("separation", 14)
	center.add_child(box)
	center.visible = false
	return box


func _label(text: String, size: int, color := Color.WHITE, align := HORIZONTAL_ALIGNMENT_CENTER) -> Label:
	var l := Label.new()
	l.text = text
	l.horizontal_alignment = align
	l.add_theme_font_size_override("font_size", size)
	l.add_theme_color_override("font_color", color)
	l.add_theme_color_override("font_outline_color", Color(0, 0, 0, 0.8))
	l.add_theme_constant_override("outline_size", 10)
	return l


func _button(text: String, on_press: Callable) -> Button:
	var b := Button.new()
	b.text = text
	b.custom_minimum_size = Vector2(440, 74)
	b.add_theme_font_size_override("font_size", 34)
	b.add_theme_stylebox_override("normal", _box(Color(0.12, 0.16, 0.27, 0.92)))
	b.add_theme_stylebox_override("hover", _box(Color(0.2, 0.26, 0.42, 0.95)))
	b.add_theme_stylebox_override("pressed", _box(Color(0.9, 0.22, 0.27, 0.95)))
	b.add_theme_stylebox_override("focus", _box(Color(0.2, 0.26, 0.42, 0.95)))
	b.add_theme_stylebox_override("disabled", _box(Color(0.1, 0.1, 0.12, 0.8)))
	b.add_theme_color_override("font_disabled_color", Color(1, 1, 1, 0.45))
	b.pressed.connect(on_press)
	return b


func _small_button(text: String, on_press: Callable) -> Button:
	var b := _button(text, on_press)
	b.custom_minimum_size = Vector2(70, 64)
	return b


func _box(color: Color) -> StyleBoxFlat:
	var s := StyleBoxFlat.new()
	s.bg_color = color
	s.set_corner_radius_all(18)
	s.set_border_width_all(3)
	s.border_color = Color(1, 1, 1, 0.5)
	return s


func _back_button(on_press: Callable) -> Button:
	var b := _button("VOLVER", on_press)
	b.custom_minimum_size = Vector2(300, 60)
	b.add_theme_font_size_override("font_size", 28)
	return b


func _clear(node: Node) -> void:
	for c in node.get_children():
		node.remove_child(c)
		c.queue_free()


func _spacer_w(w: float) -> Control:
	var c := Control.new()
	c.custom_minimum_size = Vector2(w, 0)
	return c


func _spacer(h: float) -> Control:
	var c := Control.new()
	c.custom_minimum_size = Vector2(0, h)
	return c
