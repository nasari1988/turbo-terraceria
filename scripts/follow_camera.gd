extends Camera3D
## Cámara que sigue al auto por detrás, con un poco de retraso para que se
## sienta la velocidad. El campo de visión se abre al ir rápido.

const DISTANCE := 8.5
const HEIGHT := 3.6
const FOLLOW_SHARPNESS := 6.0
const BASE_FOV := 70.0
const FAST_FOV := 82.0

var target: Node3D
var shake := 0.0            # intensidad de la sacudida (se desvanece sola)
var orbit := false          # en el menú la cámara gira alrededor del auto
var _orbit_angle := 0.6


func _ready() -> void:
	far = 600.0
	fov = BASE_FOV


func snap() -> void:
	global_position = _desired_position()
	_look()
	reset_physics_interpolation()


func _physics_process(delta: float) -> void:
	if target == null:
		return
	var t := 1.0 - exp(-FOLLOW_SHARPNESS * delta)
	if orbit:
		_orbit_angle += delta * 0.25
		var c: Vector3 = target.get_car_position()
		var want := c + Vector3(cos(_orbit_angle) * 11.0, 3.5, sin(_orbit_angle) * 11.0)
		global_position = global_position.lerp(want, t)
		look_at(c + Vector3.UP * 1.0, Vector3.UP)
		fov = lerpf(fov, BASE_FOV, t)
		return
	global_position = global_position.lerp(_desired_position(), t)
	_look()
	shake = maxf(0.0, shake - delta * 2.5)
	h_offset = randf_range(-1.0, 1.0) * shake * 0.6
	v_offset = randf_range(-1.0, 1.0) * shake * 0.4
	var kmh: float = target.get_speed_kmh()
	fov = lerpf(fov, lerpf(BASE_FOV, FAST_FOV, clampf(kmh / 110.0, 0.0, 1.0)), t)


func add_shake(amount: float) -> void:
	shake = clampf(shake + amount, 0.0, 1.0)


func _desired_position() -> Vector3:
	var pos: Vector3 = target.get_car_position()
	var fwd: Vector3 = target.get_forward_flat()
	return pos - fwd * DISTANCE + Vector3.UP * HEIGHT


func _look() -> void:
	var pos: Vector3 = target.get_car_position()
	var fwd: Vector3 = target.get_forward_flat()
	look_at(pos + Vector3.UP * 1.2 + fwd * 3.0, Vector3.UP)
