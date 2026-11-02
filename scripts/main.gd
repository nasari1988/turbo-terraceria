extends Node3D
## Escena principal de la Fase 1: arma la pista, el auto, la cámara y los controles.

const Track := preload("res://scripts/track.gd")
const Car := preload("res://scripts/car.gd")
const FollowCamera := preload("res://scripts/follow_camera.gd")
const GameUI := preload("res://scripts/ui.gd")
const Race := preload("res://scripts/race.gd")
const Menu := preload("res://scripts/menu.gd")

## Acciones de control y sus teclas (para probar en la computadora).
const ACTIONS := {
	"acelerar": [KEY_W, KEY_UP],
	"frenar": [KEY_S, KEY_DOWN, KEY_SPACE],
	"izquierda": [KEY_A, KEY_LEFT],
	"derecha": [KEY_D, KEY_RIGHT],
	"reiniciar": [KEY_R],
	"pausa": [KEY_ESCAPE, KEY_P],
}


func _ready() -> void:
	_setup_input()
	_setup_environment()

	var track := Track.new()
	add_child(track)

	var player := Car.new()
	player.track = track
	add_child(player)

	var race := Race.new()
	race.name = "Race"
	race.track = track
	race.player = player
	add_child(race)
	player.respawn_callback = race.respawn_on_track

	var camera := FollowCamera.new()
	camera.target = player
	add_child(camera)
	camera.current = true
	race.state_changed.connect(func(s): camera.orbit = s == Race.State.MENU)

	var ui := GameUI.new()
	ui.car = player
	ui.race = race
	add_child(ui)

	var menu := Menu.new()
	menu.race = race
	add_child(menu)

	race.to_menu()
	camera.snap()


func _setup_input() -> void:
	for action in ACTIONS:
		if not InputMap.has_action(action):
			InputMap.add_action(action)
		for key in ACTIONS[action]:
			var ev := InputEventKey.new()
			ev.physical_keycode = key
			InputMap.action_add_event(action, ev)


func _setup_environment() -> void:
	var sky_mat := ProceduralSkyMaterial.new()
	sky_mat.sky_top_color = Color("4a90d9")
	sky_mat.sky_horizon_color = Color("bfe0ff")
	sky_mat.ground_horizon_color = Color("bfe0ff")
	var sky := Sky.new()
	sky.sky_material = sky_mat
	var env := Environment.new()
	env.background_mode = Environment.BG_SKY
	env.sky = sky
	env.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
	env.ambient_light_color = Color("9fb4d0")
	env.ambient_light_energy = 0.45
	env.tonemap_mode = Environment.TONE_MAPPER_FILMIC
	var world := WorldEnvironment.new()
	world.environment = env
	add_child(world)

	var sun := DirectionalLight3D.new()
	sun.rotation_degrees = Vector3(-55, 35, 0)
	sun.light_energy = 0.9
	sun.shadow_enabled = true
	sun.directional_shadow_max_distance = 90.0
	add_child(sun)
