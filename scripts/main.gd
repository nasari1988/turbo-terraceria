extends Node3D
## Escena de la Fase 0: solo confirma que el APK compila y corre en el teléfono.
## Se arma por código para que no dependa de modelos ni texturas.

var _auto: MeshInstance3D


func _ready() -> void:
	# Cielo y luz
	var entorno := WorldEnvironment.new()
	var env := Environment.new()
	env.background_mode = Environment.BG_COLOR
	env.background_color = Color("87c5ff")
	env.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
	env.ambient_light_color = Color(0.6, 0.6, 0.6)
	entorno.environment = env
	add_child(entorno)

	var sol := DirectionalLight3D.new()
	sol.rotation_degrees = Vector3(-50, 30, 0)
	sol.shadow_enabled = true
	add_child(sol)

	# Piso de terracería
	var piso := MeshInstance3D.new()
	var plano := PlaneMesh.new()
	plano.size = Vector2(40, 40)
	piso.mesh = plano
	piso.material_override = _material(Color("b5651d"))
	add_child(piso)

	# "Auto" provisional: una caja roja con cabina
	_auto = MeshInstance3D.new()
	var carroceria := BoxMesh.new()
	carroceria.size = Vector3(2.0, 0.6, 4.0)
	_auto.mesh = carroceria
	_auto.material_override = _material(Color("e63946"))
	_auto.position.y = 0.5
	add_child(_auto)

	var cabina := MeshInstance3D.new()
	var caja_cabina := BoxMesh.new()
	caja_cabina.size = Vector3(1.6, 0.5, 1.8)
	cabina.mesh = caja_cabina
	cabina.material_override = _material(Color("f1faee"))
	cabina.position = Vector3(0, 0.55, -0.2)
	_auto.add_child(cabina)

	# Cámara
	var camara := Camera3D.new()
	camara.position = Vector3(0, 4, 8)
	add_child(camara)
	camara.look_at(Vector3(0, 0.5, 0))

	# Texto en pantalla
	var etiqueta := Label.new()
	etiqueta.text = "Turbo Terracería - Fase 0 OK\nToca la pantalla para cambiar de color"
	etiqueta.add_theme_font_size_override("font_size", 36)
	etiqueta.position = Vector2(24, 24)
	var capa := CanvasLayer.new()
	capa.add_child(etiqueta)
	add_child(capa)


func _process(delta: float) -> void:
	_auto.rotate_y(delta * 0.8)


func _unhandled_input(event: InputEvent) -> void:
	if event is InputEventScreenTouch and event.pressed:
		_auto.material_override = _material(Color.from_hsv(randf(), 0.8, 0.9))


func _material(color: Color) -> StandardMaterial3D:
	var m := StandardMaterial3D.new()
	m.albedo_color = color
	return m
