extends Node3D
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
var use_player_input := true # false = lo maneja la IA (escribe en input_*)
var controls_enabled := true # false = congelado (cuenta regresiva)
var respawn_callback: Callable  # la carrera decide dónde reaparecer

var input_gas := 0.0
var input_brake := 0.0
var input_steer := 0.0       # +1 izquierda, -1 derecha

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


func _ready() -> void:
	_build_ball()
	_build_model()


# ------------------------------------------------------------------ API

func respawn_at(pos: Vector3, new_heading: float) -> void:
	_spawn_pos = pos
	_spawn_heading = new_heading
	respawn()


func respawn() -> void:
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
	var gas := input_gas if controls_enabled else 0.0
	var brake := input_brake if controls_enabled else 0.0
	var steer := input_steer if controls_enabled else 0.0

	grounded = _ray.is_colliding()
	var normal := _ray.get_collision_normal() if grounded else Vector3.UP

	var vel := _ball.linear_velocity
	var fwd_flat := get_forward_flat()
	speed = vel.dot(fwd_flat)

	var flat_vel := Vector3(vel.x, 0.0, vel.z)
	drifting = grounded and brake > 0.5 and absf(steer) > 0.3 and speed > 0.0 \
		and flat_vel.length() > DRIFT_MIN_SPEED

	# Giro: depende de la velocidad, se invierte en reversa y es menor en el aire.
	# Si se acelera contra un muro casi sin moverse, aun así se puede girar.
	var turn_speed := maxf(absf(speed), maxf(gas, brake) * 5.0)
	if turn_speed > 0.5:
		var direction := -1.0 if speed < -0.5 or (brake > 0.0 and gas == 0.0 and speed < 1.0) else 1.0
		var turn := steer * TURN_SPEED * clampf(turn_speed / 8.0, 0.0, 1.0) * direction
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

		var drive := ACCEL * power * gas
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
			var hv := vel.slide(normal)
			var dir := hv.normalized().slerp(forward, DRIFT_GRIP)
			_ball.linear_velocity = dir * hv.length() * 0.998 + vel.project(normal)
		else:
			# Agarre lateral: quita parte del deslizamiento de lado.
			var lateral := vel.dot(right)
			_ball.apply_central_impulse(-right * lateral * GRIP * _ball.mass)

		# Resistencia del aire y del terreno.
		var drag := DRAG
		if track and track.has_method("is_on_grass") and track.is_on_grass(_ball.global_position):
			drag += GRASS_DRAG
		_ball.apply_central_force(-vel.slide(normal) * drag * _ball.mass)

		# Si no se toca nada y casi no se mueve, que se detenga del todo.
		if gas == 0.0 and brake == 0.0 and vel.slide(normal).length() < 0.6:
			_ball.linear_velocity = vel.project(normal)

	_hull.basis = Basis(Vector3.UP, heading) * Basis(Vector3.RIGHT, PI / 2.0)
	_update_model(delta, normal, steer)


# --------------------------------------------------------- construcción

func _build_ball() -> void:
	_ball = RigidBody3D.new()
	_ball.mass = 1.0
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
	_add_box(_body, Vector3(2.0, 0.55, 4.3), Vector3(0, 0.72, 0), body_color)            # carrocería
	_add_box(_body, Vector3(1.7, 0.5, 2.0), Vector3(0, 1.22, 0.3), Color("1d3557"))      # ventanas
	_add_box(_body, Vector3(1.75, 0.08, 1.9), Vector3(0, 1.5, 0.3), Color("f1faee"))     # techo
	_add_box(_body, Vector3(2.0, 0.08, 0.5), Vector3(0, 1.25, 2.0), Color("f1faee"))     # alerón
	_add_box(_body, Vector3(0.12, 0.4, 0.12), Vector3(-0.8, 1.0, 2.0), Color("222222"))
	_add_box(_body, Vector3(0.12, 0.4, 0.12), Vector3(0.8, 1.0, 2.0), Color("222222"))
	_add_box(_body, Vector3(1.8, 0.2, 0.15), Vector3(0, 0.6, -2.18), Color("ffd166"))    # defensa
	# Número en el techo y en las puertas.
	_add_number(Vector3(0, 1.56, 0.3), Vector3(-PI / 2.0, 0, 0), 0.007)
	_add_number(Vector3(-1.02, 0.75, 0.1), Vector3(0, -PI / 2.0, 0), 0.005)
	_add_number(Vector3(1.02, 0.75, 0.1), Vector3(0, PI / 2.0, 0), 0.005)

	for x in [-1.0, 1.0]:
		for z in [-1.35, 1.35]:
			var pivot := Node3D.new()
			pivot.position = Vector3(x, 0.42, z)
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
