extends CanvasLayer
## Controles táctiles (multitáctil) y HUD de carrera: vuelta, posición,
## tiempos, velocímetro, cuenta regresiva y avisos.

var car: Node3D
var race: Node
var powerups: Node3D
var sfx: Node

var _buttons: Array[TouchScreenButton] = []
var _left: TouchScreenButton
var _right: TouchScreenButton
var _gas: TouchScreenButton
var _brake: TouchScreenButton
var _reset: TouchScreenButton
var _pause: TouchScreenButton
var _item: TouchScreenButton
var _item_label: Label
var _last_count := -1

var _hud: Control
var _speed_label: Label
var _drift_label: Label
var _lap_label: Label
var _pos_label: Label
var _time_label: Label
var _laps_info: Label
var _center_label: Label
var _notice: Label
var _notice_time := 0.0
var _hint: Label


func _ready() -> void:
	_hud = Control.new()
	_hud.set_anchors_preset(Control.PRESET_FULL_RECT)
	_hud.mouse_filter = Control.MOUSE_FILTER_IGNORE
	add_child(_hud)

	_left = _make_button("<", "izquierda", 150, Color(1, 1, 1, 0.35))
	_right = _make_button(">", "derecha", 150, Color(1, 1, 1, 0.35))
	_gas = _make_button("GAS", "acelerar", 170, Color(0.3, 0.85, 0.4, 0.45))
	_brake = _make_button("FRENO", "frenar", 140, Color(0.95, 0.3, 0.3, 0.45))
	_reset = _make_button("R", "reiniciar", 80, Color(1, 1, 1, 0.3))
	_pause = _make_button("II", "pausa", 80, Color(1, 1, 1, 0.3))
	_item = _make_button("", "usar", 130, Color(1.0, 0.62, 0.11, 0.6))
	_item_label = _item.get_child(0)
	_item_label.add_theme_font_size_override("font_size", 24)

	_speed_label = _make_label(60, HORIZONTAL_ALIGNMENT_CENTER)
	_drift_label = _make_label(40, HORIZONTAL_ALIGNMENT_CENTER)
	_drift_label.text = "¡DERRAPE!"
	_drift_label.add_theme_color_override("font_color", Color("ffd166"))
	_lap_label = _make_label(40, HORIZONTAL_ALIGNMENT_LEFT)
	_pos_label = _make_label(64, HORIZONTAL_ALIGNMENT_LEFT)
	_pos_label.add_theme_color_override("font_color", Color("ffd166"))
	_time_label = _make_label(40, HORIZONTAL_ALIGNMENT_CENTER)
	_laps_info = _make_label(26, HORIZONTAL_ALIGNMENT_CENTER)
	_center_label = _make_label(150, HORIZONTAL_ALIGNMENT_CENTER)
	_notice = _make_label(52, HORIZONTAL_ALIGNMENT_CENTER)
	_hint = _make_label(26, HORIZONTAL_ALIGNMENT_CENTER)
	_hint.text = "Mantén FRENO mientras giras a buena velocidad para derrapar"

	get_viewport().size_changed.connect(_layout)
	_layout()

	race.lap_completed.connect(_on_lap)
	race.final_lap.connect(func(): show_notice("¡ÚLTIMA VUELTA!", Color("ffd166")))
	race.state_changed.connect(_on_state)
	race.player_finished.connect(func(): if sfx: sfx.play("meta", -2.0))
	powerups.item_picked.connect(func(c, kind):
		if c == car:
			show_notice("¡" + powerups.item_name(kind) + "!", Color("ffb347"), 1.2)
			if sfx: sfx.play("item", -6.0))
	_on_state(race.state)


func show_notice(text: String, color := Color.WHITE, seconds := 2.0) -> void:
	_notice.text = text
	_notice.add_theme_color_override("font_color", color)
	_notice_time = seconds


func _on_lap(lap_time: float, is_best: bool) -> void:
	if race.mode == race.Mode.RACE and car.laps_done >= race.laps:
		return
	var t: String = "Vuelta: " + race.format_time(lap_time)
	show_notice(t + ("  ¡MEJOR!" if is_best else ""), Color("8ef58e") if is_best else Color.WHITE)


func _on_state(s: int) -> void:
	var driving: bool = s == race.State.COUNTDOWN or s == race.State.RACING
	for b in _buttons:
		b.visible = driving
	_hud.visible = s != race.State.MENU
	for l in [_speed_label, _lap_label, _pos_label, _time_label, _laps_info]:
		l.visible = driving
	if s == race.State.FINISHED:
		show_notice("¡META!", Color("ffd166"), 1.4)
	_hint.visible = s == race.State.COUNTDOWN
	if s == race.State.RACING:
		get_tree().create_timer(5.0).timeout.connect(func(): _hint.visible = false)
	_pos_label.visible = driving and race.mode == race.Mode.RACE


func _process(delta: float) -> void:
	if car == null or race == null:
		return
	_speed_label.text = "%d km/h" % roundi(car.get_speed_kmh())
	_drift_label.visible = car.drifting and race.state == race.State.RACING

	if race.mode == race.Mode.RACE:
		_lap_label.text = "Vuelta %d/%d" % [race.current_lap(), race.laps]
		_pos_label.text = "%d°/%d" % [race.player_position(), race.cars.size()]
	else:
		_lap_label.text = "Práctica · vuelta %d" % race.current_lap()
	_time_label.text = race.format_time(race.current_lap_time()) if race.state != race.State.COUNTDOWN else "0:00.00"
	_laps_info.text = "Última %s   Mejor %s" % [race.format_time(race.last_lap), race.format_time(race.best_lap)]

	# Cuenta regresiva 3, 2, 1, ¡YA!
	if race.state == race.State.COUNTDOWN:
		var n := ceili(race.countdown)
		_center_label.text = str(n) if n <= 3 else ""
		_center_label.add_theme_color_override("font_color", Color.WHITE)
		_center_label.visible = true
	elif race.state == race.State.RACING and race.race_time < 1.0:
		_center_label.text = "¡YA!"
		_center_label.add_theme_color_override("font_color", Color("8ef58e"))
		_center_label.visible = true
	elif race.wrong_way:
		_center_label.text = "¡SENTIDO\nCONTRARIO!"
		_center_label.add_theme_font_size_override("font_size", 70)
		_center_label.add_theme_color_override("font_color", Color("ff6b6b"))
		_center_label.visible = true
	else:
		_center_label.visible = false
		_center_label.add_theme_font_size_override("font_size", 150)

	_notice_time -= delta
	_notice.visible = _notice_time > 0.0

	# Botón del objeto: solo aparece si traes uno.
	var driving: bool = race.state == race.State.COUNTDOWN or race.state == race.State.RACING
	_item.visible = driving and car.item != ""
	_item_label.text = powerups.item_name(car.item)

	# Pitidos de la cuenta regresiva.
	if race.state == race.State.COUNTDOWN and sfx:
		var c := ceili(race.countdown)
		if c != _last_count and c <= 3 and c >= 1:
			sfx.play("beep", -4.0)
		_last_count = c
	elif race.state == race.State.RACING and _last_count != 0:
		_last_count = 0
		if sfx and race.race_time < 0.5:
			sfx.play("ya", -2.0)


func _layout() -> void:
	var size := get_viewport().get_visible_rect().size
	var m := 40.0
	_place(_left, Vector2(m + 75, size.y - m - 75))
	_place(_right, Vector2(m + 75 + 180, size.y - m - 75))
	_place(_gas, Vector2(size.x - m - 85, size.y - m - 85))
	_place(_brake, Vector2(size.x - m - 85 - 185, size.y - m - 70))
	_place(_item, Vector2(size.x - m - 85, size.y - m - 85 - 185))
	_place(_pause, Vector2(size.x - m - 40, m + 40))
	_place(_reset, Vector2(size.x - m - 40 - 100, m + 40))
	_set_rect(_speed_label, Vector2(0, size.y - 105), Vector2(size.x, 80))
	_set_rect(_drift_label, Vector2(0, size.y * 0.27), Vector2(size.x, 60))
	_set_rect(_pos_label, Vector2(m, m - 10), Vector2(400, 80))
	_set_rect(_lap_label, Vector2(m, m + 70), Vector2(500, 50))
	_set_rect(_time_label, Vector2(0, m - 10), Vector2(size.x, 50))
	_set_rect(_laps_info, Vector2(0, m + 40), Vector2(size.x, 40))
	_set_rect(_center_label, Vector2(0, size.y * 0.25), Vector2(size.x, 200))
	_set_rect(_notice, Vector2(0, size.y * 0.42), Vector2(size.x, 70))
	_set_rect(_hint, Vector2(0, size.y * 0.62), Vector2(size.x, 40))


func _set_rect(c: Control, pos: Vector2, size: Vector2) -> void:
	c.position = pos
	c.size = size


func _place(b: TouchScreenButton, center: Vector2) -> void:
	var s: float = b.get_meta("diameter")
	b.position = center - Vector2(s, s) / 2.0


func _make_button(text: String, action: String, diameter: int, color: Color) -> TouchScreenButton:
	var b := TouchScreenButton.new()
	b.texture_normal = _circle(diameter, color)
	b.texture_pressed = _circle(diameter, Color(color.r, color.g, color.b, minf(color.a + 0.35, 1.0)))
	b.action = action
	b.passby_press = action in ["izquierda", "derecha", "acelerar", "frenar"]
	b.visibility_mode = TouchScreenButton.VISIBILITY_ALWAYS
	b.set_meta("diameter", float(diameter))
	var label := Label.new()
	label.text = text
	label.size = Vector2(diameter, diameter)
	label.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	label.vertical_alignment = VERTICAL_ALIGNMENT_CENTER
	label.add_theme_font_size_override("font_size", 48 if text.length() <= 1 else 30)
	label.add_theme_color_override("font_outline_color", Color(0, 0, 0, 0.6))
	label.add_theme_constant_override("outline_size", 6)
	label.mouse_filter = Control.MOUSE_FILTER_IGNORE
	b.add_child(label)
	add_child(b)
	_buttons.append(b)
	return b


func _make_label(font_size: int, align: HorizontalAlignment) -> Label:
	var l := Label.new()
	l.horizontal_alignment = align
	l.vertical_alignment = VERTICAL_ALIGNMENT_CENTER
	l.add_theme_font_size_override("font_size", font_size)
	l.add_theme_color_override("font_outline_color", Color(0, 0, 0, 0.8))
	l.add_theme_constant_override("outline_size", 10)
	l.mouse_filter = Control.MOUSE_FILTER_IGNORE
	_hud.add_child(l)
	return l


## Textura de círculo con borde, generada en tiempo de ejecución.
func _circle(diameter: int, color: Color) -> ImageTexture:
	var img := Image.create(diameter, diameter, false, Image.FORMAT_RGBA8)
	var r := diameter / 2.0
	for y in diameter:
		for x in diameter:
			var d := Vector2(x + 0.5 - r, y + 0.5 - r).length()
			var edge := clampf(r - d, 0.0, 1.0)
			var c := color
			if d > r - 5.0:
				c = Color(1, 1, 1, 0.8)
			img.set_pixel(x, y, Color(c.r, c.g, c.b, c.a * edge))
	return ImageTexture.create_from_image(img)
