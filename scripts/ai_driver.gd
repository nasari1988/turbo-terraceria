extends Node
## Piloto automático: sigue la línea central de la pista con un carril propio,
## esquiva autos que tiene enfrente, a veces toma el atajo y se recupera solo
## si se atora. Escribe en car.input_* antes de que el auto calcule su física.

const LOOKAHEAD := 0.32        # qué tan adelante mira (radianes de pista)
const AVOID_DISTANCE := 11.0
const SHORTCUT_CHANCE := 0.4

var car: Node3D
var track: Node3D
var cars: Array = []           # todos los autos, para esquivar
var lane := 0.0                # metros hacia afuera de la línea central
var shortcut_chance := SHORTCUT_CHANCE

var _rng := RandomNumberGenerator.new()
var _taking_shortcut := false
var _decided_this_lap := false
var _stuck_time := 0.0
var _reverse_time := 0.0
var _lane_wobble := 0.0


func _ready() -> void:
	process_physics_priority = -10  # antes que el auto
	_rng.randomize()
	_lane_wobble = _rng.randf() * TAU


func _physics_process(delta: float) -> void:
	if car == null or not car.controls_enabled:
		return
	var p: Vector3 = car.get_car_position()
	var angle: float = track.progress_angle(p)

	# ¿Atajo? (solo en el óvalo) Se decide una vez por vuelta.
	if not track.has_shortcut:
		_taking_shortcut = false
	elif angle < -0.9 and angle > -1.4:
		_decided_this_lap = false
	if not _decided_this_lap and angle > -0.6 and angle < 0.0 and p.x > 45.0:
		_decided_this_lap = true
		_taking_shortcut = _rng.randf() < shortcut_chance
	if _taking_shortcut and p.x < -50.0:
		_taking_shortcut = false

	var target: Vector3
	if _taking_shortcut and track.is_on_shortcut(p):
		target = Vector3(p.x - 15.0, 0, 0)
	elif _taking_shortcut and p.x > 45.0:
		target = Vector3(-60.0, 0, 0)
	else:
		_lane_wobble += delta * 0.4
		var l := lane + sin(_lane_wobble) * 1.0
		target = track.centerline_point(angle + track.lookahead(), l)

	var fwd: Vector3 = car.get_forward_flat()
	var right := fwd.cross(Vector3.UP)
	target += right * _avoid_offset(p, fwd, right)

	var to_target := target - p
	to_target.y = 0.0
	var turn := fwd.signed_angle_to(to_target.normalized(), Vector3.UP)  # + = izquierda

	var steer := clampf(turn * 2.5, -1.0, 1.0)
	var gas := 1.0
	var brake := 0.0
	# Curva cerrada a buena velocidad: derrapa.
	if absf(turn) > 0.55 and car.speed > 15.0:
		brake = 1.0
	# Curva cerrada adelante: suelta el acelerador si va muy rápido.
	var safe_speed: float = track.curve_radius_ahead(angle) * 2.0 * (0.8 + 0.2 * track.grip) + 4.0
	if car.speed > safe_speed:
		gas = 0.0
		if car.speed > safe_speed + 6.0 and absf(turn) > 0.2:
			brake = 1.0

	# Atorado (contra un muro o un auto): reversa un momento.
	if car.speed < 1.5 and car.speed > -1.5:
		_stuck_time += delta
	else:
		_stuck_time = maxf(0.0, _stuck_time - delta)
	if _stuck_time > 1.2 and _reverse_time <= 0.0:
		_reverse_time = 1.0
	if _reverse_time > 0.0:
		_reverse_time -= delta
		gas = 0.0
		brake = 1.0
		steer = -signf(turn)
		if _reverse_time <= 0.0:
			_stuck_time = 0.0
	if _stuck_time > 6.0 and car.respawn_callback.is_valid():
		_stuck_time = 0.0
		car.respawn_callback.call(car)

	car.input_gas = gas
	car.input_brake = brake
	car.input_steer = steer
	_use_item_logic(delta, p, fwd, turn)


## Decide cuándo usar el objeto que trae.
var _item_wait := 0.0

func _use_item_logic(delta: float, p: Vector3, fwd: Vector3, turn: float) -> void:
	if car.item == "":
		_item_wait = _rng.randf_range(0.5, 2.0)
		return
	_item_wait -= delta
	if _item_wait > 0.0:
		return
	var behind := INF
	var near := INF
	for other in cars:
		if other == car:
			continue
		var d: Vector3 = other.get_car_position() - p
		near = minf(near, d.length())
		if d.dot(fwd) < 0.0:
			behind = minf(behind, d.length())
	var use := false
	match car.item:
		"turbo":
			use = absf(turn) < 0.15 and car.speed > 10.0
		"escudo":
			use = behind < 12.0 or _item_wait < -6.0
		"aceite":
			use = behind < 18.0 or _item_wait < -8.0
		"onda":
			use = near < 8.0 or _item_wait < -10.0
	if use:
		car.request_use_item()


## Desplazamiento lateral para rebasar al auto que va justo enfrente.
func _avoid_offset(p: Vector3, fwd: Vector3, right: Vector3) -> float:
	var best := 0.0
	var closest := AVOID_DISTANCE
	for other in cars:
		if other == car:
			continue
		var d: Vector3 = other.get_car_position() - p
		var ahead := d.dot(fwd)
		var side := d.dot(right)
		if ahead > 0.0 and ahead < closest and absf(side) < 3.0:
			closest = ahead
			best = -3.5 if side > 0.0 else 3.5
	return best
