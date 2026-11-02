extends CanvasLayer
## Menús: principal, pausa y resultados. Funciona aunque el juego esté en pausa.

var race: Node

var _dim: ColorRect
var _main: VBoxContainer
var _pause: VBoxContainer
var _results: VBoxContainer
var _results_table: GridContainer
var _results_title: Label
var _laps_label: Label
var _laps := 3
const LAP_OPTIONS := [1, 3, 5, 10]


func _ready() -> void:
	layer = 10
	process_mode = Node.PROCESS_MODE_ALWAYS

	_dim = ColorRect.new()
	_dim.color = Color(0, 0, 0, 0.45)
	_dim.set_anchors_preset(Control.PRESET_FULL_RECT)
	add_child(_dim)

	_build_main()
	_build_pause()
	_build_results()

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


# ---------------------------------------------------------------- pantallas

func _build_main() -> void:
	_main = _panel()
	var title := _label("TURBO TERRACERÍA", 76, Color("ffd166"))
	_main.add_child(title)
	_main.add_child(_label("Carreras arcade en tierra", 30, Color(1, 1, 1, 0.8)))
	_main.add_child(_spacer(20))
	_main.add_child(_button("CARRERA", func(): _start(race.Mode.RACE)))

	var laps_row := HBoxContainer.new()
	laps_row.alignment = BoxContainer.ALIGNMENT_CENTER
	laps_row.add_theme_constant_override("separation", 16)
	laps_row.add_child(_small_button("<", func(): _change_laps(-1)))
	_laps_label = _label("", 34)
	_laps_label.custom_minimum_size = Vector2(240, 0)
	laps_row.add_child(_laps_label)
	laps_row.add_child(_small_button(">", func(): _change_laps(1)))
	_main.add_child(laps_row)
	_change_laps(0)

	_main.add_child(_button("PRÁCTICA LIBRE", func(): _start(race.Mode.PRACTICE)))


func _build_pause() -> void:
	_pause = _panel()
	_pause.add_child(_label("PAUSA", 70, Color("ffd166")))
	_pause.add_child(_spacer(10))
	_pause.add_child(_button("CONTINUAR", _resume))
	_pause.add_child(_button("REINICIAR", func(): _resume(); race.start(race.mode, race.laps)))
	_pause.add_child(_button("MENÚ PRINCIPAL", func(): _resume(); race.to_menu()))


func _build_results() -> void:
	_results = _panel()
	_results_title = _label("", 60, Color("ffd166"))
	_results.add_child(_results_title)
	_results_table = GridContainer.new()
	_results_table.columns = 3
	_results_table.add_theme_constant_override("h_separation", 40)
	_results_table.add_theme_constant_override("v_separation", 4)
	_results.add_child(_results_table)
	_results.add_child(_spacer(10))
	var row := HBoxContainer.new()
	row.alignment = BoxContainer.ALIGNMENT_CENTER
	row.add_theme_constant_override("separation", 20)
	row.add_child(_button("REVANCHA", func(): race.start(race.mode, race.laps)))
	row.add_child(_button("MENÚ", func(): race.to_menu()))
	_results.add_child(row)


func _show_results() -> void:
	if race.state != race.State.FINISHED:
		return
	var pos: int = race.player_position()
	_results_title.text = "¡GANASTE!" if pos == 1 else "Llegaste %d° de %d" % [pos, race.cars.size()]
	for c in _results_table.get_children():
		c.queue_free()
	var standings: Array = race.standings()
	for i in standings.size():
		var c: Node3D = standings[i]
		var color := Color("ffd166") if c == race.player else Color.WHITE
		_results_table.add_child(_label("%d°" % (i + 1), 32, color))
		_results_table.add_child(_label("#%d %s" % [c.number, c.display_name], 32, color, HORIZONTAL_ALIGNMENT_LEFT))
		var t: String = race.format_time(c.finish_time) if c.finished else "en pista"
		_results_table.add_child(_label(t, 32, color))
	_results_table.add_child(_label("", 26))
	_results_table.add_child(_label("Tu mejor vuelta", 26, Color(1, 1, 1, 0.8), HORIZONTAL_ALIGNMENT_LEFT))
	_results_table.add_child(_label(race.format_time(race.best_lap), 26, Color("8ef58e")))
	_show(_results)


func _on_state(s: int) -> void:
	if s == race.State.MENU:
		_show(_main)
	elif s == race.State.COUNTDOWN or s == race.State.RACING:
		_show(null)


func _show(panel: Control) -> void:
	for p in [_main, _pause, _results]:
		p.get_parent().visible = p == panel
	_dim.visible = panel != null


# ---------------------------------------------------------------- acciones

func _start(mode: int) -> void:
	race.start(mode, _laps)


func _change_laps(step: int) -> void:
	var i := LAP_OPTIONS.find(_laps)
	i = clampi(i + step, 0, LAP_OPTIONS.size() - 1)
	_laps = LAP_OPTIONS[i]
	_laps_label.text = "%d vuelta%s" % [_laps, "" if _laps == 1 else "s"]


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
	b.custom_minimum_size = Vector2(420, 84)
	b.add_theme_font_size_override("font_size", 36)
	b.add_theme_stylebox_override("normal", _box(Color(0.12, 0.16, 0.27, 0.92)))
	b.add_theme_stylebox_override("hover", _box(Color(0.2, 0.26, 0.42, 0.95)))
	b.add_theme_stylebox_override("pressed", _box(Color(0.9, 0.22, 0.27, 0.95)))
	b.add_theme_stylebox_override("focus", _box(Color(0.2, 0.26, 0.42, 0.95)))
	b.pressed.connect(on_press)
	return b


func _small_button(text: String, on_press: Callable) -> Button:
	var b := _button(text, on_press)
	b.custom_minimum_size = Vector2(84, 70)
	return b


func _box(color: Color) -> StyleBoxFlat:
	var s := StyleBoxFlat.new()
	s.bg_color = color
	s.set_corner_radius_all(18)
	s.set_border_width_all(3)
	s.border_color = Color(1, 1, 1, 0.5)
	return s


func _spacer(h: float) -> Control:
	var c := Control.new()
	c.custom_minimum_size = Vector2(0, h)
	return c
