extends Camera3D
## Cámara que sigue al auto por detrás, con un poco de retraso para que se
## sienta la velocidad. El campo de visión se abre al ir rápido.

const DISTANCE := 8.5
const HEIGHT := 3.6
const FOLLOW_SHARPNESS := 6.0
const BASE_FOV := 70.0
const FAST_FOV := 82.0

var target: Node3D


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
	global_position = global_position.lerp(_desired_position(), t)
	_look()
	var kmh: float = target.get_speed_kmh()
	fov = lerpf(fov, lerpf(BASE_FOV, FAST_FOV, clampf(kmh / 110.0, 0.0, 1.0)), t)


func _desired_position() -> Vector3:
	var pos: Vector3 = target.get_car_position()
	var fwd: Vector3 = target.get_forward_flat()
	return pos - fwd * DISTANCE + Vector3.UP * HEIGHT


func _look() -> void:
	var pos: Vector3 = target.get_car_position()
	var fwd: Vector3 = target.get_forward_flat()
	look_at(pos + Vector3.UP * 1.2 + fwd * 3.0, Vector3.UP)
