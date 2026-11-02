extends RefCounted
## Estado global del juego: lo elegido en el menú, el torneo en curso y el
## progreso guardado (desbloqueos y mejores vueltas). Usa variables estáticas
## para sobrevivir al recargar la escena entre carreras.

const SAVE_PATH := "user://progreso.cfg"

const TRACKS := [
	{"id": "ovalo", "name": "Óvalo de Tierra", "theme": "Tierra"},
	{"id": "desierto", "name": "Cañón Seco", "theme": "Desierto"},
	{"id": "nieve", "name": "Pico Helado", "theme": "Nieve", "unlock_after": "desierto"},
	{"id": "bosque", "name": "Sendero del Bosque", "theme": "Bosque", "unlock_after": "nieve"},
	{"id": "pueblo", "name": "Calles del Pueblo", "theme": "Pueblo", "unlock_after": "bosque"},
]
const TOURNAMENT_TRACKS := ["desierto", "nieve", "bosque", "pueblo"]
const POINTS := [10, 8, 6, 4, 2, 1]

## Autos: accel (empuje), drag (resistencia: vel. máx ≈ accel/drag),
## turn (giro), grip (agarre lateral), mass (peso en choques) y forma.
const CARS := {
	"coyote": {"name": "Coyote", "desc": "Equilibrado", "color": Color("e63946"), "number": 7,
		"accel": 30.0, "drag": 1.0, "turn": 2.3, "grip": 0.15, "mass": 1.0,
		"shape": {"length": 1.0, "width": 1.0, "roof": 1.0, "spoiler": 1.0}},
	"liebre": {"name": "Liebre", "desc": "Arranca rapidísimo", "color": Color("06d6a0"), "number": 11,
		"accel": 35.0, "drag": 1.14, "turn": 2.4, "grip": 0.14, "mass": 0.85,
		"shape": {"length": 0.92, "width": 0.95, "roof": 0.93, "spoiler": 0.7}},
	"halcon": {"name": "Halcón", "desc": "Gira como en rieles", "color": Color("118ab2"), "number": 22,
		"accel": 29.5, "drag": 0.98, "turn": 2.75, "grip": 0.2, "mass": 0.95,
		"shape": {"length": 1.03, "width": 1.0, "roof": 0.95, "spoiler": 1.5}},
	"toro": {"name": "Toro", "desc": "Pesado y muy veloz", "color": Color("ffb703"), "number": 1,
		"accel": 31.0, "drag": 0.92, "turn": 2.05, "grip": 0.13, "mass": 1.5,
		"shape": {"length": 1.06, "width": 1.08, "roof": 1.06, "spoiler": 1.1}},
}
const CAR_ORDER := ["coyote", "liebre", "halcon", "toro"]
const CAR_UNLOCKS := {"halcon": "Gana una carrera", "toro": "Gana el Torneo"}

# --- Selección actual (sobrevive a recargar la escena)
static var track_id := "ovalo"
static var car_id := "coyote"
static var mode := 0                 # 0 = carrera, 1 = práctica
static var laps := 3
static var autostart := false
static var tournament := {}          # {"active", "index", "points": {nombre: pts}}

# --- Progreso guardado
static var _loaded := false
static var unlocked_tracks: Array = ["ovalo", "desierto"]
static var unlocked_cars: Array = ["coyote", "liebre"]
static var best_laps := {}


static func track_info(id: String) -> Dictionary:
	for t in TRACKS:
		if t.id == id:
			return t
	return TRACKS[0]


static func is_track_unlocked(id: String) -> bool:
	load_progress()
	return id in unlocked_tracks


static func is_car_unlocked(id: String) -> bool:
	load_progress()
	return id in unlocked_cars


static func track_unlock_hint(id: String) -> String:
	var t := track_info(id)
	if t.has("unlock_after"):
		return "Sube al podio en " + track_info(t.unlock_after).name
	return ""


## Guarda el resultado de una carrera y devuelve lo que se desbloqueó.
static func record_race(track: String, position: int, best_lap: float) -> Array:
	load_progress()
	var news := []
	if best_lap > 0.0 and best_lap < INF and best_lap < best_laps.get(track, INF):
		best_laps[track] = best_lap
	if position <= 3:
		for t in TRACKS:
			if t.get("unlock_after", "") == track and not t.id in unlocked_tracks:
				unlocked_tracks.append(t.id)
				news.append("Pista: " + t.name)
	if position == 1 and not "halcon" in unlocked_cars:
		unlocked_cars.append("halcon")
		news.append("Auto: Halcón")
	save_progress()
	return news


static func record_tournament_win() -> Array:
	load_progress()
	if "toro" in unlocked_cars:
		return []
	unlocked_cars.append("toro")
	save_progress()
	return ["Auto: Toro"]


static func load_progress() -> void:
	if _loaded:
		return
	_loaded = true
	var cfg := ConfigFile.new()
	if cfg.load(SAVE_PATH) != OK:
		return
	unlocked_tracks = cfg.get_value("progreso", "pistas", unlocked_tracks)
	unlocked_cars = cfg.get_value("progreso", "autos", unlocked_cars)
	best_laps = cfg.get_value("progreso", "mejores_vueltas", {})


static func save_progress() -> void:
	var cfg := ConfigFile.new()
	cfg.set_value("progreso", "pistas", unlocked_tracks)
	cfg.set_value("progreso", "autos", unlocked_cars)
	cfg.set_value("progreso", "mejores_vueltas", best_laps)
	cfg.save(SAVE_PATH)


## Barras de 1 a 5 para mostrar en el menú.
static func car_bars(id: String) -> Dictionary:
	var c: Dictionary = CARS[id]
	return {
		"Velocidad": clampi(roundi(remap(c.accel / c.drag, 29.0, 34.0, 1.0, 5.0)), 1, 5),
		"Aceleración": clampi(roundi(remap(c.accel, 29.0, 35.0, 1.0, 5.0)), 1, 5),
		"Manejo": clampi(roundi(remap(c.turn * 10.0 + c.grip * 40.0, 26.0, 35.5, 1.0, 5.0)), 1, 5),
		"Peso": clampi(roundi(remap(c.mass, 0.85, 1.5, 1.0, 5.0)), 1, 5),
	}
