extends Node3D

signal impact(strength: float)
signal item_use_requested(car: Node3D)
signal item_changed(item: String)
## Auto con física arcade estilo "bola": una esfera invisible sin fricción se
## desliza por el mundo y el modelo visible la sigue. Así el manejo es estable,
## perdona errores y funciona bien en rampas y saltos.

# --- Ajustes de manejo (cámbialos para afinar cómo se siente) ---
const ACCEL := 30.0          # empuje del acelerador (m/s²)
const REVERSE_ACCEL := 12.0  # empuje en reversa
const BRAKE := 40.0          # fuerza de frenado
const DRAG := 1.0            # resistencia; velocidad máxima ≈ ACCEL / DRAG (m/s)
const GRASS_DRAG := 2.0      # resistencia extra sobre el pasto
const TURN_SPEED := 2.3      # velocidad de giro (rad/s)
const DRIFT_TURN_BONUS := 1.4
const GRIP := 0.15           # agarre lateral normal (0 = hielo, 1 = rieles)
const DRIFT_GRIP := 0.04     # qué tan rápido se endereza la trayectoria al derrapar
const MAX_DRIFT_ANGLE := deg_to_rad(35.0)  # máximo ángulo de derrape (evita trompos)
const DRIFT_MIN_SPEED := 12.0
const BALL_RADIUS := 1.0

var track: Node3D            # la pista, para saber si vamos sobre pasto
var display_name := "Tú"
var number := 7
var body_color := Color("e63946")
var power := 1.0             # multiplicador de aceleración (la IA usa menos de 1)
var car_type := "coyote"     # forma del auto (ver game_state.gd)
var shape := {"length": 1.0, "width": 1.0, "roof": 1.0, "spoiler": 1.0}
var accel_stat := ACCEL
var drag_stat := DRAG
var turn_stat := TURN_SPEED
var grip_stat := GRIP
var mass_stat := 1.0
var use_player_input := true # false = lo maneja la IA (escribe en input_*)
var controls_enabled := true # false = congelado (cuenta regresiva)
var respawn_callback: Callable  # la carrera decide dónde reaparecer

var input_gas := 0.0
var input_brake := 0.0
var input_steer := 0.0       # +1 izquierda, -1 derecha

# Power-ups
const BOOST_ACCEL := 8.0     # empuje extra mientras dura el turbo
const BOOST_KICK := 7.0      # empujón inmediato al activarlo (m/s)
var item := ""               # "turbo", "escudo", "aceite", "onda" o vacío
var boost_time := 0.0
var shield_time := 0.0
var spin_time := 0.0
var _spin_dir := 1.0
var _prev_hv := Vector3.ZERO
var _skip_impact := 0

# Datos de carrera (los llena race.gd)
var race_progress := 0.0
var prev_angle := 0.0
var laps_done := 0
var finished := false
var finish_time := 0.0
var heading := 0.0           # hacia dónde apunta el auto (radianes)
var speed := 0.0             # velocidad hacia adelante (m/s, negativa en reversa)
var drifting := false
var grounded := false

var _ball: RigidBody3D
var _ray: RayCast3D
var _hull: CollisionShape3D
var _model: Node3D
var _body: Node3D
var _front_pivots: Array[Node3D] = []
var _wheel_spinners: Array[Node3D] = []
var _model_up := Vector3.UP
var _spawn_pos := Vector3.ZERO
var _spawn_heading := 0.0
var _steer_visual := 0.0
var _dust: CPUParticles3D
var _flames: Array[MeshInstance3D] = []
var _shield_mesh: MeshInstance3D
var _engine: AudioStreamPlayer3D
var _skid: AudioStreamPlayer3D
var _sfx: Node


## Copia las estadísticas y la forma de un tipo de auto.
func apply_type(id: String, data: Dictionary, stats := true) -> void:
	car_type = id
	shape = data.shape
	if stats:
		accel_stat = data.accel
		drag_stat = data.drag
		turn_stat = data.turn
		grip_stat = data.grip
		mass_stat = data.mass


func _ready() -> void:
	_build_ball()
	_build_model()
	_build_effects()
	_sfx = get_tree().get_first_node_in_group("sfx")
	if _sfx:
		_build_audio()


# --------------------------------------------------------------- power-ups

func give_item(kind: String) -> void:
	item = kind
	item_changed.emit(item)


func request_use_item() -> void:
	if item != "" and controls_enabled:
		item_use_requested.emit(self)


func take_item() -> String:
	var k := item
	item = ""
	item_changed.emit(item)
	return k


func start_boost(seconds := 1.6) -> void:
	boost_time = seconds
	_skip_impact = 2
	_ball.linear_velocity += get_forward_flat() * BOOST_KICK
	_play("turbo", 0.0)


func start_shield(seconds := 5.0) -> void:
	shield_time = seconds
	_play("escudo", 0.0)


## Recibe un ataque (aceite u onda). Devuelve false si el escudo lo bloqueó.
func hit(kind: String, push := Vector3.ZERO) -> bool:
	if shield_time > 0.0:
		shield_time = 0.0
		_play("escudo", 2.0, 0.8)
		return false
	_skip_impact = 3
	spin_time = 1.1 if kind == "aceite" else 0.7
	_spin_dir = 1.0 if randf() < 0.5 else -1.0
	var v := _ball.linear_velocity
	_ball.linear_velocity = Vector3(v.x * 0.5, v.y, v.z * 0.5) + push
	_play("aceite" if kind == "aceite" else "golpe", 2.0)
	return true


func clear_effects() -> void:
	boost_time = 0.0
	shield_time = 0.0
	spin_time = 0.0
	if item != "":
		take_item()


# ------------------------------------------------------------------ API

func respawn_at(pos: Vector3, new_heading: float) -> void:
	_spawn_pos = pos
	_spawn_heading = new_heading
	respawn()


func respawn() -> void:
	_skip_impact = 3
	spin_time = 0.0
	heading = _spawn_heading
	_ball.linear_velocity = Vector3.ZERO
	_ball.global_position = _spawn_pos
	_model_up = Vector3.UP
	_update_model(0.0)
	_ball.reset_physics_interpolation()
	_model.reset_physics_interpolation()


func get_car_position() -> Vector3:
	return _model.global_position


func get_forward_flat() -> Vector3:
	return Vector3(-sin(heading), 0.0, -cos(heading))


func get_speed_kmh() -> float:
	return absf(speed) * 3.6


# ------------------------------------------------------------- física

func _physics_process(delta: float) -> void:
	var wants_reset := use_player_input and controls_enabled and Input.is_action_just_pressed("reiniciar")
	if wants_reset or _ball.global_position.y < -20.0:
		if respawn_callback.is_valid():
			respawn_callback.call(self)
		else:
			respawn()
		return

	if use_player_input:
		input_gas = Input.get_action_strength("acelerar")
		input_brake = Input.get_action_strength("frenar")
		input_steer = Input.get_action_strength("izquierda") - Input.get_action_strength("derecha")
	if use_player_input and controls_enabled and Input.is_action_just_pressed("usar"):
		request_use_item()
	var gas := input_gas if controls_enabled else 0.0
	var brake := input_brake if controls_enabled else 0.0
	var steer := input_steer if controls_enabled else 0.0

	boost_time = maxf(0.0, boost_time - delta)
	shield_time = maxf(0.0, shield_time - delta)
	if spin_time > 0.0:
		# Trompo: sin control unos instantes.
		spin_time -= delta
		heading += 9.0 * _spin_dir * delta
		gas = 0.0
		brake = 0.0
		steer = 0.0

	grounded = _ray.is_colliding()
	var normal := _ray.get_collision_normal() if grounded else Vector3.UP

	var vel := _ball.linear_velocity
	var fwd_flat := get_forward_flat()
	speed = vel.dot(fwd_flat)

	# Choque: cambio brusco de velocidad horizontal entre un cuadro y otro.
	var hv := Vector3(vel.x, 0.0, vel.z)
	var dv := (hv - _prev_hv).length()
	_prev_hv = hv
	if _skip_impact > 0:
		_skip_impact -= 1
	elif dv > 5.0:
		_on_impact(dv)

	var flat_vel := Vector3(vel.x, 0.0, vel.z)
	drifting = grounded and brake > 0.5 and absf(steer) > 0.3 and speed > 0.0 \
		and flat_vel.length() > DRIFT_MIN_SPEED

	# Giro: depende de la velocidad, se invierte en reversa y es menor en el aire.
	# Si se acelera contra un muro casi sin moverse, aun así se puede girar.
	var turn_speed := maxf(absf(speed), maxf(gas, brake) * 5.0)
	if turn_speed > 0.5:
		var direction := -1.0 if speed < -0.5 or (brake > 0.0 and gas == 0.0 and speed < 1.0) else 1.0
		var turn := steer * turn_stat * clampf(turn_speed / 8.0, 0.0, 1.0) * direction
		if drifting:
			turn *= DRIFT_TURN_BONUS
		if not grounded:
			turn *= 0.3
		heading += turn * delta

	# Al derrapar, la cola se abre pero nunca más allá del ángulo máximo.
	if drifting:
		var vel_heading := atan2(-flat_vel.x, -flat_vel.z)
		var diff := wrapf(heading - vel_heading, -PI, PI)
		heading = vel_heading + clampf(diff, -MAX_DRIFT_ANGLE, MAX_DRIFT_ANGLE)

	if grounded:
		var forward := fwd_flat.slide(normal).normalized()
		var right := forward.cross(normal).normalized()

		var drive := accel_stat * power * gas
		if boost_time > 0.0:
			drive += BOOST_ACCEL
		if brake > 0.0:
			if drifting:
				pass  # el freno sirve para iniciar el derrape, no frena
			elif speed > 1.0:
				drive -= BRAKE * brake
			else:
				drive -= REVERSE_ACCEL * brake
		if gas > 0.0 and speed < -1.0:
			drive += BRAKE * gas * 0.5
		_ball.apply_central_force(forward * drive * _ball.mass)

		if drifting:
			# En derrape la velocidad no se pierde: se va girando poco a poco
			# hacia donde apunta el auto (la cola "barre" la curva).
			var slide_v := vel.slide(normal)
			var dir := slide_v.normalized().slerp(forward, DRIFT_GRIP)
			_ball.linear_velocity = dir * slide_v.length() * 0.998 + vel.project(normal)
		else:
			# Agarre lateral: quita parte del deslizamiento de lado.
			var lateral := vel.dot(right)
			var surface: float = track.grip if track and "grip" in track else 1.0
			_ball.apply_central_impulse(-right * lateral * grip_stat * surface * _ball.mass)

		# Resistencia del aire y del terreno.
		var drag := drag_stat * (0.85 if boost_time > 0.0 else 1.0)
		if track and track.has_method("is_on_grass") and track.is_on_grass(_ball.global_position):
			drag += GRASS_DRAG
		_ball.apply_central_force(-vel.slide(normal) * drag * _ball.mass)

		# Si no se toca nada y casi no se mueve, que se detenga del todo.
		if gas == 0.0 and brake == 0.0 and vel.slide(normal).length() < 0.6:
			_ball.linear_velocity = vel.project(normal)

	_hull.basis = Basis(Vector3.UP, heading) * Basis(Vector3.RIGHT, PI / 2.0)
	_update_model(delta, normal, steer)
	_update_effects(delta)


# --------------------------------------------------------- construcción

func _build_ball() -> void:
	_ball = RigidBody3D.new()
	_ball.mass = mass_stat
	_ball.gravity_scale = 1.8
	_ball.lock_rotation = true
	_ball.can_sleep = false
	_ball.continuous_cd = true
	var mat := PhysicsMaterial.new()
	mat.friction = 0.0
	mat.bounce = 0.0
	_ball.physics_material_override = mat
	var col := CollisionShape3D.new()
	var sphere := SphereShape3D.new()
	sphere.radius = BALL_RADIUS
	col.shape = sphere
	_ball.add_child(col)

	# Casco alargado para chocar con muros y otros autos (no toca el suelo).
	_hull = CollisionShape3D.new()
	var capsule := CapsuleShape3D.new()
	capsule.radius = 0.8
	capsule.height = 4.2
	_hull.shape = capsule
	_hull.position = Vector3(0, 0.3, 0)
	_hull.basis = Basis(Vector3.RIGHT, PI / 2.0)
	_ball.add_child(_hull)

	_ray = RayCast3D.new()
	_ray.target_position = Vector3(0, -BALL_RADIUS - 0.6, 0)
	_ball.add_child(_ray)
	add_child(_ball)


func _build_model() -> void:
	_model = Node3D.new()
	add_child(_model)

	_body = Node3D.new()
	_model.add_child(_body)
	_build_body()

	for x in [-1.0, 1.0]:
		for z in [-1.4, 1.4]:
			var pivot := Node3D.new()
			pivot.position = Vector3(x * shape.width, 0.42, z * shape.length)
			_model.add_child(pivot)
			var spinner := Node3D.new()
			pivot.add_child(spinner)
			var wheel := MeshInstance3D.new()
			var cyl := CylinderMesh.new()
			cyl.top_radius = 0.42
			cyl.bottom_radius = 0.42
			cyl.height = 0.35
			cyl.radial_segments = 12
			wheel.mesh = cyl
			wheel.rotation.z = PI / 2
			wheel.material_override = _mat(Color("1b1b1b"))
			spinner.add_child(wheel)
			var hub := MeshInstance3D.new()
			var hub_box := BoxMesh.new()
			hub_box.size = Vector3(0.37, 0.25, 0.25)
			hub.mesh = hub_box
			hub.material_override = _mat(Color("bbbbbb"))
			spinner.add_child(hub)
			_wheel_spinners.append(spinner)
			if z < 0.0:
				_front_pivots.append(pivot)


func _update_model(delta: float, normal := Vector3.UP, steer := 0.0) -> void:
	var smooth := 1.0 if delta == 0.0 else 1.0 - exp(-10.0 * delta)
	_model_up = _model_up.lerp(normal, smooth).normalized()
	var fwd := get_forward_flat().slide(_model_up).normalized()
	var look := Basis.looking_at(fwd, _model_up)
	_model.global_transform = Transform3D(look, _ball.global_position - _model_up * BALL_RADIUS)

	# Detalles visuales: llantas que giran, dirección e inclinación de la carrocería.
	_steer_visual = lerpf(_steer_visual, steer, smooth)
	for p in _front_pivots:
		p.rotation.y = _steer_visual * 0.45
	for s in _wheel_spinners:
		s.rotate_x(-speed * delta / 0.42)
	var lean := -_steer_visual * clampf(absf(speed) / 25.0, 0.0, 1.0) * (0.12 if drifting else 0.06)
	_body.rotation.z = lean


## Carrocería estilo stock car: perfil lateral extruido (cofre largo,
## parabrisas inclinado, cajuela corta), cabina oscura, alerón y franjas.
func _build_body() -> void:
	var L: float = shape.length
	var W: float = shape.width
	var R: float = shape.roof
	var paint := StandardMaterial3D.new()
	paint.albedo_color = body_color
	paint.roughness = 0.35
	paint.metallic = 0.25
	var accent := Color.WHITE if body_color.get_luminance() < 0.6 else Color("1d3557")

	var lower := [Vector2(-2.3, 0.3), Vector2(-2.3, 0.55), Vector2(-2.1, 0.7), Vector2(-0.9, 0.88),
		Vector2(1.0, 0.95), Vector2(2.25, 0.98), Vector2(2.3, 0.75), Vector2(2.3, 0.3)]
	_body.add_child(_extruded(_scale_profile(lower, L, 1.0), 1.0 * W, paint))

	var glass := StandardMaterial3D.new()
	glass.albedo_color = Color("16213e")
	glass.roughness = 0.1
	glass.metallic = 0.5
	var cabin := [Vector2(-0.85, 0.88), Vector2(-0.1, 1.45 * R), Vector2(1.0, 1.48 * R), Vector2(1.55, 0.95)]
	_body.add_child(_extruded(_scale_profile(cabin, L, 1.0), 0.8 * W, glass))

	# Techo pintado, franjas laterales, defensa, faros y alerón.
	_add_box(_body, Vector3(1.5 * W, 0.05, 1.0 * L), Vector3(0, 1.48 * R, 0.45 * L), body_color)
	for side in [-1.0, 1.0]:
		_add_box(_body, Vector3(0.02, 0.14, 4.2 * L), Vector3(side * (1.0 * W + 0.01), 0.5, 0.0), accent)
	_add_box(_body, Vector3(1.6 * W, 0.18, 0.06), Vector3(0, 0.42, -2.31 * L), Color("111111"))
	for side in [-1.0, 1.0]:
		_add_box(_body, Vector3(0.4, 0.12, 0.05), Vector3(side * 0.65 * W, 0.58, -2.31 * L), Color("fff3b0"))
		_add_box(_body, Vector3(0.4, 0.12, 0.05), Vector3(side * 0.65 * W, 0.72, 2.31 * L), Color("d00000"))
	var sp: float = shape.spoiler
	_add_box(_body, Vector3(1.9 * W, 0.06, 0.45), Vector3(0, 0.98 + 0.25 * sp, 2.1 * L), accent)
	for side in [-1.0, 1.0]:
		_add_box(_body, Vector3(0.08, 0.25 * sp, 0.08), Vector3(side * 0.7 * W, 0.98 + 0.125 * sp, 2.1 * L), Color("222222"))
	if car_type == "toro":
		_add_box(_body, Vector3(1.9 * W, 0.12, 0.12), Vector3(0, 0.48, -2.42 * L), Color("444444"))

	# Número en el techo y en las puertas.
	_add_number(Vector3(0, 1.52 * R, 0.45 * L), Vector3(-PI / 2.0, 0, 0), 0.006)
	_add_number(Vector3(-1.0 * W - 0.02, 0.72, 0.1), Vector3(0, -PI / 2.0, 0), 0.005)
	_add_number(Vector3(1.0 * W + 0.02, 0.72, 0.1), Vector3(0, PI / 2.0, 0), 0.005)


func _scale_profile(points: Array, sz: float, sy: float) -> Array:
	var out := []
	for p in points:
		out.append(Vector2(p.x * sz, p.y * sy))
	return out


## Extruye un perfil convexo (z, y) a lo ancho (x = ±half), con caras planas.
func _extruded(profile: Array, half: float, mat: Material) -> MeshInstance3D:
	var st := SurfaceTool.new()
	st.begin(Mesh.PRIMITIVE_TRIANGLES)
	st.set_smooth_group(-1)
	var n := profile.size()
	for side in [-1.0, 1.0]:
		for i in range(1, n - 1):
			var a := Vector3(side * half, profile[0].y, profile[0].x)
			var b := Vector3(side * half, profile[i].y, profile[i].x)
			var c := Vector3(side * half, profile[i + 1].y, profile[i + 1].x)
			if side > 0.0:
				st.add_vertex(a); st.add_vertex(b); st.add_vertex(c)
			else:
				st.add_vertex(a); st.add_vertex(c); st.add_vertex(b)
	for i in n:
		var p0: Vector2 = profile[i]
		var p1: Vector2 = profile[(i + 1) % n]
		var a := Vector3(-half, p0.y, p0.x)
		var b := Vector3(half, p0.y, p0.x)
		var c := Vector3(half, p1.y, p1.x)
		var d := Vector3(-half, p1.y, p1.x)
		st.add_vertex(a); st.add_vertex(c); st.add_vertex(b)
		st.add_vertex(a); st.add_vertex(d); st.add_vertex(c)
	st.generate_normals()
	var m := MeshInstance3D.new()
	m.mesh = st.commit()
	var mm: Material = mat.duplicate()
	mm.cull_mode = BaseMaterial3D.CULL_DISABLED
	m.material_override = mm
	return m


func _add_number(pos: Vector3, rot: Vector3, pixel: float) -> void:
	var l := Label3D.new()
	l.text = str(number)
	l.font_size = 96
	l.outline_size = 18
	l.pixel_size = pixel
	l.modulate = Color.WHITE
	l.outline_modulate = Color(0, 0, 0, 1)
	l.position = pos
	l.rotation = rot
	_body.add_child(l)


func _add_box(parent: Node3D, size: Vector3, pos: Vector3, color: Color) -> void:
	var m := MeshInstance3D.new()
	var box := BoxMesh.new()
	box.size = size
	m.mesh = box
	m.position = pos
	m.material_override = _mat(color)
	parent.add_child(m)


func _mat(color: Color) -> StandardMaterial3D:
	var m := StandardMaterial3D.new()
	m.albedo_color = color
	return m


# ------------------------------------------------------------- efectos

func _build_effects() -> void:
	# Polvo de las llantas traseras.
	_dust = CPUParticles3D.new()
	_dust.amount = 22
	_dust.lifetime = 0.9
	_dust.local_coords = false
	_dust.emitting = false
	var quad := QuadMesh.new()
	quad.size = Vector2(1.1, 1.1)
	var dm := StandardMaterial3D.new()
	dm.billboard_mode = BaseMaterial3D.BILLBOARD_PARTICLES
	dm.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	dm.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	dm.vertex_color_use_as_albedo = true
	# Textura circular difuminada para que el polvo no se vea cuadrado.
	var puff := GradientTexture2D.new()
	puff.fill = GradientTexture2D.FILL_RADIAL
	puff.fill_from = Vector2(0.5, 0.5)
	puff.fill_to = Vector2(1.0, 0.5)
	puff.width = 64
	puff.height = 64
	var g := Gradient.new()
	g.set_color(0, Color(1, 1, 1, 1))
	g.set_color(1, Color(1, 1, 1, 0))
	puff.gradient = g
	dm.albedo_texture = puff
	quad.material = dm
	_dust.mesh = quad
	_dust.emission_shape = CPUParticles3D.EMISSION_SHAPE_BOX
	_dust.emission_box_extents = Vector3(0.9, 0.1, 0.2)
	_dust.direction = Vector3(0, 1, 1)
	_dust.spread = 30.0
	_dust.initial_velocity_min = 1.0
	_dust.initial_velocity_max = 3.0
	_dust.gravity = Vector3(0, 0.6, 0)
	_dust.scale_amount_min = 0.7
	_dust.scale_amount_max = 1.6
	var grow := Curve.new()
	grow.add_point(Vector2(0, 0.5))
	grow.add_point(Vector2(1, 1.8))
	_dust.scale_amount_curve = grow
	var dust_color := Color(0.78, 0.6, 0.42)
	if track and "theme" in track and track.theme.has("dust"):
		dust_color = track.theme.dust
	var fade := Gradient.new()
	fade.set_color(0, Color(dust_color, 0.45))
	fade.set_color(1, Color(dust_color, 0.0))
	_dust.color_ramp = fade
	_dust.position = Vector3(0, 0.25, 1.7)
	_model.add_child(_dust)

	# Flamas del turbo.
	for x in [-0.5, 0.5]:
		var f := MeshInstance3D.new()
		var cone := CylinderMesh.new()
		cone.top_radius = 0.0
		cone.bottom_radius = 0.18
		cone.height = 0.9
		f.mesh = cone
		var fm := StandardMaterial3D.new()
		fm.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
		fm.albedo_color = Color("ff9f1c")
		f.material_override = fm
		f.rotation.x = PI / 2.0
		f.position = Vector3(x, 0.55, 2.6)
		f.visible = false
		_model.add_child(f)
		_flames.append(f)

	# Burbuja del escudo.
	_shield_mesh = MeshInstance3D.new()
	var sphere := SphereMesh.new()
	sphere.radius = 2.5
	sphere.height = 3.4
	_shield_mesh.mesh = sphere
	var sm := StandardMaterial3D.new()
	sm.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	sm.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	sm.albedo_color = Color(0.4, 0.85, 1.0, 0.28)
	sm.cull_mode = BaseMaterial3D.CULL_DISABLED
	_shield_mesh.material_override = sm
	_shield_mesh.position = Vector3(0, 0.9, 0)
	_shield_mesh.visible = false
	_model.add_child(_shield_mesh)


func _build_audio() -> void:
	_engine = AudioStreamPlayer3D.new()
	_engine.stream = _sfx.streams.motor
	_engine.volume_db = -3.0 if use_player_input else -9.0
	_engine.unit_size = 10.0
	_engine.position = Vector3(0, 0.8, 0)
	_model.add_child(_engine)
	_engine.play()
	_skid = AudioStreamPlayer3D.new()
	_skid.stream = _sfx.streams.derrape
	_skid.volume_db = -80.0
	_skid.unit_size = 10.0
	_model.add_child(_skid)
	_skid.play()


func _update_effects(delta: float) -> void:
	var fast_on_ground := grounded and absf(speed) > 9.0
	_dust.emitting = grounded and (drifting or spin_time > 0.0 or (fast_on_ground and input_gas > 0.0))

	var boosting := boost_time > 0.0
	for f in _flames:
		f.visible = boosting
		if boosting:
			f.scale = Vector3.ONE * randf_range(0.8, 1.3)
	_shield_mesh.visible = shield_time > 0.0 and (shield_time > 1.0 or fmod(shield_time, 0.2) > 0.1)
	if spin_time > 0.0:
		_body.rotation.z += sin(spin_time * 30.0) * 0.02

	if _engine:
		var target := 0.55 + absf(speed) / 18.0 + (0.15 if input_gas > 0.0 and controls_enabled else 0.0)
		if boosting:
			target += 0.25
		_engine.pitch_scale = lerpf(_engine.pitch_scale, clampf(target, 0.5, 2.8), 1.0 - exp(-8.0 * delta))
		var skid_on := drifting or spin_time > 0.0
		_skid.volume_db = lerpf(_skid.volume_db, -6.0 if skid_on else -60.0, 1.0 - exp(-12.0 * delta))


func _on_impact(strength: float) -> void:
	impact.emit(strength)
	_play("golpe", linear_to_db(clampf(strength / 18.0, 0.25, 1.0)) + 2.0, randf_range(0.85, 1.15))
	var sparks := CPUParticles3D.new()
	sparks.one_shot = true
	sparks.explosiveness = 1.0
	sparks.amount = int(clampf(strength * 1.5, 10, 32))
	sparks.lifetime = 0.5
	var box := BoxMesh.new()
	box.size = Vector3(0.07, 0.07, 0.25)
	var m := StandardMaterial3D.new()
	m.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	m.albedo_color = Color("ffd166")
	box.material = m
	sparks.mesh = box
	sparks.direction = Vector3(0, 1, 0)
	sparks.spread = 80.0
	sparks.initial_velocity_min = 4.0
	sparks.initial_velocity_max = 9.0
	sparks.gravity = Vector3(0, -14, 0)
	sparks.particle_flag_align_y = true
	get_parent().add_child(sparks)
	sparks.global_position = get_car_position() + get_forward_flat() * 1.6 + Vector3.UP * 0.7
	sparks.emitting = true
	get_tree().create_timer(1.2).timeout.connect(sparks.queue_free)


func _play(sound: String, volume_db := 0.0, pitch := 1.0) -> void:
	if _sfx:
		_sfx.play_at(sound, get_car_position(), volume_db, pitch)
