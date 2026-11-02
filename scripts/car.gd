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
var heading := 0.0           # hacia dónde apunta el auto (radianes)
var speed := 0.0             # velocidad hacia adelante (m/s, negativa en reversa)
var drifting := false
var grounded := false

var _ball: RigidBody3D
var _ray: RayCast3D
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
	if Input.is_action_just_pressed("reiniciar") or _ball.global_position.y < -20.0:
		respawn()
		return

	var gas := Input.get_action_strength("acelerar")
	var brake := Input.get_action_strength("frenar")
	var steer := Input.get_action_strength("izquierda") - Input.get_action_strength("derecha")

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

		var drive := ACCEL * gas
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

	_ray = RayCast3D.new()
	_ray.target_position = Vector3(0, -BALL_RADIUS - 0.6, 0)
	_ball.add_child(_ray)
	add_child(_ball)


func _build_model() -> void:
	_model = Node3D.new()
	add_child(_model)

	_body = Node3D.new()
	_model.add_child(_body)
	_add_box(_body, Vector3(2.0, 0.55, 4.3), Vector3(0, 0.72, 0), Color("e63946"))       # carrocería
	_add_box(_body, Vector3(1.7, 0.5, 2.0), Vector3(0, 1.22, 0.3), Color("1d3557"))      # ventanas
	_add_box(_body, Vector3(1.75, 0.08, 1.9), Vector3(0, 1.5, 0.3), Color("f1faee"))     # techo
	_add_box(_body, Vector3(2.0, 0.08, 0.5), Vector3(0, 1.25, 2.0), Color("f1faee"))     # alerón
	_add_box(_body, Vector3(0.12, 0.4, 0.12), Vector3(-0.8, 1.0, 2.0), Color("222222"))
	_add_box(_body, Vector3(0.12, 0.4, 0.12), Vector3(0.8, 1.0, 2.0), Color("222222"))
	_add_box(_body, Vector3(1.8, 0.2, 0.15), Vector3(0, 0.6, -2.18), Color("ffd166"))    # defensa
	_add_box(_body, Vector3(0.9, 0.02, 0.9), Vector3(0, 1.0, -1.1), Color("ffd166"))     # número en cofre

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
