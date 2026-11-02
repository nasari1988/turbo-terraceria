extends Node
## Lógica de la carrera: parrilla de salida, cuenta regresiva, vueltas,
## posiciones, tiempos y fin de carrera. También el modo práctica libre.

signal state_changed(new_state: int)
signal lap_completed(lap_time: float, is_best: bool)
signal final_lap
signal player_finished

enum State { MENU, COUNTDOWN, RACING, FINISHED }
enum Mode { RACE, PRACTICE }

const CarScript := preload("res://scripts/car.gd")
const GameState := preload("res://scripts/game_state.gd")
const AIDriver := preload("res://scripts/ai_driver.gd")

## Rivales (nombres y colores originales).
const RIVALS := [
	{"name": "Rayo Ruiz", "number": 14, "color": Color("2a9d8f"), "power": 0.97},
	{"name": "La Tuerca", "number": 3, "color": Color("f4a261"), "power": 0.95},
	{"name": "Chuy Turbo", "number": 21, "color": Color("3a86ff"), "power": 0.93},
	{"name": "Doña Polvo", "number": 9, "color": Color("8338ec"), "power": 0.91},
	{"name": "El Güero", "number": 55, "color": Color("ffbe0b"), "power": 0.89},
]
const COUNTDOWN_TIME := 3.0

## Dificultad: multiplica la potencia de los rivales y cambia qué tanto
## arriesgan con el atajo y cuánta ayuda reciben al quedarse atrás.
const DIFFICULTIES := [
	{"name": "Fácil", "power": 0.88, "shortcut": 0.15, "catch_up": 1.02},
	{"name": "Normal", "power": 1.0, "shortcut": 0.4, "catch_up": 1.06},
	{"name": "Difícil", "power": 1.06, "shortcut": 0.6, "catch_up": 1.1},
]

var track: Node3D
var powerups: Node3D
var player: Node3D
var cars: Array = []          # el jugador siempre es cars[0]
var state := State.MENU
var mode := Mode.RACE
var laps := 3
var difficulty := 1

var countdown := 0.0
var race_time := 0.0
var lap_start_time := 0.0
var last_lap := 0.0
var best_lap := INF
var wrong_way := false
var last_unlocks: Array = []        # lo que se desbloqueó en esta carrera
var points_gained := {}             # puntos de torneo de esta carrera

var _drivers: Array = []
var _rivals: Array = []
var _wrong_way_time := 0.0


func _ready() -> void:
	process_physics_priority = 10  # después de que los autos se movieron


# ------------------------------------------------------------- arranque

## Prepara una carrera (o práctica) y arranca la cuenta regresiva.
func start(new_mode: int, new_laps: int) -> void:
	mode = new_mode
	laps = new_laps
	_clear()
	cars = [player]
	player.use_player_input = true
	if mode == Mode.RACE:
		for i in RIVALS.size():
			var r: Dictionary = RIVALS[i]
			var c := CarScript.new()
			c.display_name = r.name
			c.number = r.number
			c.body_color = r.color
			c.power = r.power * DIFFICULTIES[difficulty].power
			var look: String = GameState.CAR_ORDER[(i + 1) % GameState.CAR_ORDER.size()]
			c.apply_type(look, GameState.CARS[look], false)   # solo la forma
			c.use_player_input = false
			c.track = track
			c.respawn_callback = respawn_on_track
			get_parent().add_child(c)
			_rivals.append(c)
			cars.append(c)
			var d := _add_driver(c, (i % 3 - 1) * 2.5)
			d.shortcut_chance = DIFFICULTIES[difficulty].shortcut

	# Parrilla: el jugador sale al final para tener a quién rebasar.
	var order := cars.slice(1)
	order.append(player)
	for slot in order.size():
		var g: Dictionary = track.grid_slot(slot)
		order[slot].respawn_at(g.position, g.heading)

	for c in cars:
		c.controls_enabled = false
		c.finished = false
		c.laps_done = 0
		c.prev_angle = track.progress_angle(c.get_car_position())
		c.race_progress = wrapf(c.prev_angle - track.start_angle(), -PI, PI)

	race_time = 0.0
	lap_start_time = 0.0
	last_lap = 0.0
	best_lap = INF
	countdown = COUNTDOWN_TIME + 0.8
	if powerups:
		powerups.reset(cars)
	_set_state(State.COUNTDOWN)


## Vuelve al menú: deja los autos en la parrilla, quietos.
func to_menu() -> void:
	_clear()
	cars = [player]
	var g: Dictionary = track.grid_slot(0)
	player.respawn_at(g.position, g.heading)
	player.controls_enabled = false
	player.use_player_input = true
	if powerups:
		powerups.reset(cars)
	_set_state(State.MENU)


func _clear() -> void:
	for d in _drivers:
		d.queue_free()
	_drivers.clear()
	for r in _rivals:
		r.queue_free()
	_rivals.clear()


func _add_driver(c: Node3D, lane: float) -> Node:
	var d := AIDriver.new()
	d.car = c
	d.track = track
	d.lane = lane
	d.cars = cars
	add_child(d)
	_drivers.append(d)
	return d


func _set_state(s: int) -> void:
	state = s
	state_changed.emit(s)


# ---------------------------------------------------------- cada cuadro

func _physics_process(delta: float) -> void:
	match state:
		State.COUNTDOWN:
			countdown -= delta
			if countdown <= 0.0:
				for c in cars:
					c.controls_enabled = true
				_set_state(State.RACING)
		State.RACING, State.FINISHED:
			race_time += delta
			_update_progress(delta)
			if mode == Mode.RACE:
				_rubber_band()


func _update_progress(delta: float) -> void:
	for c in cars:
		var angle: float = track.progress_angle(c.get_car_position())
		var d := wrapf(angle - c.prev_angle, -PI, PI)
		c.prev_angle = angle
		c.race_progress += d
		var done := maxi(0, floori(c.race_progress / TAU))
		if done > c.laps_done:
			c.laps_done = done
			if c == player:
				_on_player_lap()
			if mode == Mode.RACE and not c.finished and c.laps_done >= laps:
				c.finished = true
				c.finish_time = race_time
				if c == player:
					_on_player_finished()

	# Sentido contrario: el jugador avanza en dirección opuesta a la pista.
	var track_heading: float = track.centerline_heading(player.prev_angle)
	var off := absf(wrapf(player.heading - track_heading, -PI, PI))
	var going_back: bool = off > 2.2 and player.speed > 3.0 and not track.is_on_shortcut(player.get_car_position())
	_wrong_way_time = _wrong_way_time + delta if going_back else 0.0
	wrong_way = _wrong_way_time > 1.0 and state == State.RACING


func _on_player_lap() -> void:
	last_lap = race_time - lap_start_time
	lap_start_time = race_time
	var is_best := last_lap < best_lap
	if is_best:
		best_lap = last_lap
	lap_completed.emit(last_lap, is_best)
	if mode == Mode.RACE and player.laps_done == laps - 1:
		final_lap.emit()
	if mode == Mode.PRACTICE and is_best:
		GameState.record_race(GameState.track_id, 99, best_lap)


func _on_player_finished() -> void:
	# El jugador terminó: el piloto automático toma el volante.
	player.use_player_input = false
	_add_driver(player, 0.0)
	last_unlocks = GameState.record_race(GameState.track_id, player_position(), best_lap)
	points_gained = {}
	if is_tournament():
		var t: Dictionary = GameState.tournament
		var order := standings()
		for i in order.size():
			var pts: int = GameState.POINTS[i] if i < GameState.POINTS.size() else 0
			points_gained[order[i].display_name] = pts
			t.points[order[i].display_name] = t.points.get(order[i].display_name, 0) + pts
		if is_last_tournament_race() and tournament_standings()[0][0] == player.display_name:
			last_unlocks.append_array(GameState.record_tournament_win())
	_set_state(State.FINISHED)
	player_finished.emit()


## Ayuda ligera: los rivales muy adelantados aflojan y los rezagados empujan.
func _rubber_band() -> void:
	for i in _rivals.size():
		var r: Node3D = _rivals[i]
		var base: float = RIVALS[i].power * DIFFICULTIES[difficulty].power
		var gap: float = r.race_progress - player.race_progress
		r.power = base * clampf(1.0 - gap * 0.02, 0.94, DIFFICULTIES[difficulty].catch_up)


# ---------------------------------------------------------------- torneo

func is_tournament() -> bool:
	return GameState.tournament.get("active", false)


func is_last_tournament_race() -> bool:
	return GameState.tournament.get("index", 0) >= GameState.TOURNAMENT_TRACKS.size() - 1


## [[nombre, puntos], ...] de mayor a menor.
func tournament_standings() -> Array:
	var list := []
	for name in GameState.tournament.get("points", {}):
		list.append([name, GameState.tournament.points[name]])
	list.sort_custom(func(a, b): return a[1] > b[1])
	return list


# ---------------------------------------------------------------- datos

## Coloca un auto de vuelta en el centro de la pista, donde iba.
func respawn_on_track(c: Node3D) -> void:
	var here: Vector3 = c.get_car_position()
	if track.is_on_shortcut(here):
		# En el atajo se avanza hacia -x.
		c.respawn_at(Vector3(here.x, 1.5, 0.0), PI / 2.0)
	else:
		var angle: float = c.prev_angle
		c.respawn_at(track.centerline_point(angle) + Vector3(0, 1.5, 0), track.centerline_heading(angle))


## Autos ordenados por posición: primero los que ya terminaron (por tiempo).
func standings() -> Array:
	var list := cars.duplicate()
	list.sort_custom(func(a, b):
		if a.finished and b.finished:
			return a.finish_time < b.finish_time
		if a.finished != b.finished:
			return a.finished
		return a.race_progress > b.race_progress)
	return list


func player_position() -> int:
	return standings().find(player) + 1


func current_lap() -> int:
	return clampi(player.laps_done + 1, 1, laps if mode == Mode.RACE else 999)


func current_lap_time() -> float:
	return race_time - lap_start_time


static func format_time(t: float) -> String:
	if t == INF or t <= 0.0:
		return "--:--.--"
	var m := int(t) / 60
	var s := fmod(t, 60.0)
	return "%d:%05.2f" % [m, s]
