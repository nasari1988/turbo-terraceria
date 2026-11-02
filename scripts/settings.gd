extends RefCounted
## Ajustes del jugador (se guardan en user://ajustes.cfg): vueltas,
## dificultad, sonido, vibración y calidad gráfica.

const PATH := "user://ajustes.cfg"
const QUALITY_NAMES := ["Baja", "Media", "Alta"]

static var laps := 3
static var difficulty := 1
static var sound := true
static var vibration := true
static var quality := 2          # 0 = baja, 1 = media, 2 = alta
static var _loaded := false


static func load_settings() -> void:
	if _loaded:
		return
	_loaded = true
	var cfg := ConfigFile.new()
	if cfg.load(PATH) != OK:
		return
	laps = cfg.get_value("carrera", "vueltas", laps)
	difficulty = cfg.get_value("carrera", "dificultad", difficulty)
	sound = cfg.get_value("general", "sonido", sound)
	vibration = cfg.get_value("general", "vibracion", vibration)
	quality = cfg.get_value("general", "calidad", quality)


static func save() -> void:
	var cfg := ConfigFile.new()
	cfg.set_value("carrera", "vueltas", laps)
	cfg.set_value("carrera", "dificultad", difficulty)
	cfg.set_value("general", "sonido", sound)
	cfg.set_value("general", "vibracion", vibration)
	cfg.set_value("general", "calidad", quality)
	cfg.save(PATH)


## Escala de resolución 3D, sombras y cantidad de decoración por calidad.
static func render_scale() -> float:
	return [0.6, 0.8, 1.0][quality]


static func shadows() -> bool:
	return quality >= 1


static func scenery_density() -> float:
	return [0.35, 0.7, 1.0][quality]
