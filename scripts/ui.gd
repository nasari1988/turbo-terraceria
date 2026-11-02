extends CanvasLayer
## Controles táctiles (multitáctil) y velocímetro.
## Izquierda: girar. Derecha: acelerar y frenar. Arriba a la derecha: reiniciar.

var car: Node3D

var _left: TouchScreenButton
var _right: TouchScreenButton
var _gas: TouchScreenButton
var _brake: TouchScreenButton
var _reset: TouchScreenButton
var _speed_label: Label
var _hint: Label
var _drift_label: Label


func _ready() -> void:
	_left = _make_button("<", "izquierda", 150, Color(1, 1, 1, 0.35))
	_right = _make_button(">", "derecha", 150, Color(1, 1, 1, 0.35))
	_gas = _make_button("GAS", "acelerar", 170, Color(0.3, 0.85, 0.4, 0.45))
	_brake = _make_button("FRENO", "frenar", 140, Color(0.95, 0.3, 0.3, 0.45))
	_reset = _make_button("R", "reiniciar", 80, Color(1, 1, 1, 0.3))

	_speed_label = _make_label(64, HORIZONTAL_ALIGNMENT_CENTER)
	_drift_label = _make_label(40, HORIZONTAL_ALIGNMENT_CENTER)
	_drift_label.text = "¡DERRAPE!"
	_drift_label.add_theme_color_override("font_color", Color("ffd166"))
	_drift_label.visible = false
	_hint = _make_label(26, HORIZONTAL_ALIGNMENT_CENTER)
	_hint.text = "Mantén FRENO mientras giras a buena velocidad para derrapar"

	get_viewport().size_changed.connect(_layout)
	_layout()
	get_tree().create_timer(7.0).timeout.connect(func(): _hint.visible = false)


func _process(_delta: float) -> void:
	if car:
		_speed_label.text = "%d km/h" % roundi(car.get_speed_kmh())
		_drift_label.visible = car.drifting


func _layout() -> void:
	var size := get_viewport().get_visible_rect().size
	var m := 40.0
	_place(_left, Vector2(m + 75, size.y - m - 75))
	_place(_right, Vector2(m + 75 + 180, size.y - m - 75))
	_place(_gas, Vector2(size.x - m - 85, size.y - m - 85))
	_place(_brake, Vector2(size.x - m - 85 - 185, size.y - m - 70))
	_place(_reset, Vector2(size.x - m - 40, m + 40))
	_speed_label.position = Vector2(0, size.y - 110)
	_speed_label.size = Vector2(size.x, 80)
	_drift_label.position = Vector2(0, size.y * 0.25)
	_drift_label.size = Vector2(size.x, 60)
	_hint.position = Vector2(0, 30)
	_hint.size = Vector2(size.x, 40)


func _place(b: TouchScreenButton, center: Vector2) -> void:
	var s: float = b.get_meta("diameter")
	b.position = center - Vector2(s, s) / 2.0


func _make_button(text: String, action: String, diameter: int, color: Color) -> TouchScreenButton:
	var b := TouchScreenButton.new()
	b.texture_normal = _circle(diameter, color)
	b.texture_pressed = _circle(diameter, Color(color.r, color.g, color.b, minf(color.a + 0.35, 1.0)))
	b.action = action
	b.passby_press = true
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
	return b


func _make_label(font_size: int, align: HorizontalAlignment) -> Label:
	var l := Label.new()
	l.horizontal_alignment = align
	l.add_theme_font_size_override("font_size", font_size)
	l.add_theme_color_override("font_outline_color", Color(0, 0, 0, 0.8))
	l.add_theme_constant_override("outline_size", 10)
	l.mouse_filter = Control.MOUSE_FILTER_IGNORE
	add_child(l)
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
