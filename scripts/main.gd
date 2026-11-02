extends Node3D
## Escena principal: arma la pista elegida, el auto, la carrera, la cámara,
## los controles y el menú. Lo elegido vive en game_state.gd.

const GameState := preload("res://scripts/game_state.gd")
const Settings := preload("res://scripts/settings.gd")
const Track := preload("res://scripts/track.gd")
const SplineTrack := preload("res://scripts/spline_track.gd")
const Car := preload("res://scripts/car.gd")
const FollowCamera := preload("res://scripts/follow_camera.gd")
const GameUI := preload("res://scripts/ui.gd")
const Race := preload("res://scripts/race.gd")
const Menu := preload("res://scripts/menu.gd")
const Sfx := preload("res://scripts/sfx.gd")
const Powerups := preload("res://scripts/powerups.gd")

## Acciones de control y sus teclas (para probar en la computadora).
const ACTIONS := {
	"acelerar": [KEY_W, KEY_UP],
	"frenar": [KEY_S, KEY_DOWN, KEY_SPACE],
	"izquierda": [KEY_A, KEY_LEFT],
	"derecha": [KEY_D, KEY_RIGHT],
	"reiniciar": [KEY_R],
	"pausa": [KEY_ESCAPE, KEY_P],
	"usar": [KEY_E, KEY_SHIFT, KEY_ENTER],
}


var _sun: DirectionalLight3D
var _menu: Node


func _ready() -> void:
	_setup_input()
	Settings.load_settings()
	DisplayServer.screen_set_keep_on(true)
	Engine.max_fps = 60

	var sfx := Sfx.new()
	sfx.name = "Sfx"
	add_child(sfx)

	var track: Node3D
	if GameState.track_id == "ovalo":
		track = Track.new()
	else:
		track = SplineTrack.new()
		track.theme_id = GameState.track_id
	add_child(track)
	_setup_environment(track.environment_settings())

	var car_data: Dictionary = GameState.CARS[GameState.car_id]
	var player := Car.new()
	player.track = track
	player.apply_type(GameState.car_id, car_data)
	player.body_color = car_data.color
	player.number = car_data.number
	add_child(player)

	var race := Race.new()
	race.name = "Race"
	race.track = track
	race.player = player
	add_child(race)
	player.respawn_callback = race.respawn_on_track

	var powerups := Powerups.new()
	powerups.track = track
	powerups.race = race
	add_child(powerups)
	race.powerups = powerups

	var camera := FollowCamera.new()
	camera.target = player
	add_child(camera)
	camera.current = true
	race.state_changed.connect(func(s): camera.orbit = s == Race.State.MENU)
	player.impact.connect(func(strength): camera.add_shake(clampf(strength / 25.0, 0.15, 0.8)))

	var ui := GameUI.new()
	ui.car = player
	ui.race = race
	ui.powerups = powerups
	ui.sfx = sfx
	add_child(ui)

	var menu := Menu.new()
	menu.race = race
	menu.sfx = sfx
	add_child(menu)
	_menu = menu
	apply_quality()

	race.to_menu()
	camera.snap()
	if GameState.autostart:
		GameState.autostart = false
		race.start(GameState.mode, GameState.laps)
		camera.orbit = false
		camera.snap()


## Botones y ejes de control (Bluetooth/USB, distribución tipo Xbox).
const PAD_BUTTONS := {
	"acelerar": [JOY_BUTTON_A],
	"frenar": [JOY_BUTTON_B, JOY_BUTTON_X],
	"izquierda": [JOY_BUTTON_DPAD_LEFT],
	"derecha": [JOY_BUTTON_DPAD_RIGHT],
	"reiniciar": [JOY_BUTTON_BACK],
	"pausa": [JOY_BUTTON_START],
	"usar": [JOY_BUTTON_Y, JOY_BUTTON_RIGHT_SHOULDER, JOY_BUTTON_LEFT_SHOULDER],
}
## [acción, eje, dirección]
const PAD_AXES := [
	["acelerar", JOY_AXIS_TRIGGER_RIGHT, 1.0],
	["frenar", JOY_AXIS_TRIGGER_LEFT, 1.0],
	["izquierda", JOY_AXIS_LEFT_X, -1.0],
	["derecha", JOY_AXIS_LEFT_X, 1.0],
]


func _setup_input() -> void:
	if InputMap.has_action("acelerar"):
		return  # ya configurado (la escena se recarga entre carreras)
	for action in ACTIONS:
		InputMap.add_action(action, 0.2)
		for key in ACTIONS[action]:
			var ev := InputEventKey.new()
			ev.physical_keycode = key
			InputMap.action_add_event(action, ev)
		for button in PAD_BUTTONS.get(action, []):
			var jb := InputEventJoypadButton.new()
			jb.button_index = button
			InputMap.action_add_event(action, jb)
	for a in PAD_AXES:
		var jm := InputEventJoypadMotion.new()
		jm.axis = a[1]
		jm.axis_value = a[2]
		InputMap.action_add_event(a[0], jm)
	# Botones de menú (aceptar/regresar) con el control.
	var accept := InputEventJoypadButton.new()
	accept.button_index = JOY_BUTTON_A
	InputMap.action_add_event("ui_accept", accept)


## Aplica la calidad gráfica elegida (resolución 3D y sombras).
func apply_quality() -> void:
	var vp := get_viewport()
	vp.scaling_3d_mode = Viewport.SCALING_3D_MODE_BILINEAR
	vp.scaling_3d_scale = Settings.render_scale()
	if _sun:
		_sun.shadow_enabled = Settings.shadows()
		_sun.directional_shadow_max_distance = 90.0 if Settings.quality == 2 else 55.0


## Si el jugador sale de la app (llamada, notificación…), se pausa la carrera.
func _notification(what: int) -> void:
	if what == NOTIFICATION_APPLICATION_FOCUS_OUT or what == NOTIFICATION_APPLICATION_PAUSED:
		if _menu and _menu.has_method("pause_from_outside"):
			_menu.pause_from_outside()


func _setup_environment(t: Dictionary) -> void:
	var sky_mat := ProceduralSkyMaterial.new()
	sky_mat.sky_top_color = t.sky_top
	sky_mat.sky_horizon_color = t.sky_horizon
	sky_mat.ground_horizon_color = t.sky_horizon
	var sky := Sky.new()
	sky.sky_material = sky_mat
	var env := Environment.new()
	env.background_mode = Environment.BG_SKY
	env.sky = sky
	env.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
	env.ambient_light_color = Color("9fb4d0")
	env.ambient_light_energy = 0.45
	env.tonemap_mode = Environment.TONE_MAPPER_FILMIC
	if t.fog > 0.0:
		env.fog_enabled = true
		env.fog_light_color = t.sky_horizon
		env.fog_density = t.fog
		env.fog_sky_affect = 0.3
	var world := WorldEnvironment.new()
	world.environment = env
	add_child(world)

	var sun := DirectionalLight3D.new()
	_sun = sun
	sun.rotation_degrees = Vector3(-55, 35, 0)
	sun.light_energy = t.sun_energy
	sun.light_color = t.sun
	sun.shadow_enabled = true
	sun.directional_shadow_max_distance = 90.0
	add_child(sun)
