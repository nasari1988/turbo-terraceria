extends Node3D
## Cajas de power-ups en la pista y efectos de cada objeto:
## TURBO (empujón), ESCUDO (protege), ACEITE (mancha que hace girar) y
## ONDA (empuja a los autos cercanos).

const Settings := preload("res://scripts/settings.gd")

signal item_picked(car: Node3D, kind: String)
signal item_used(car: Node3D, kind: String)

const ITEMS := ["turbo", "escudo", "aceite", "onda"]
const NAMES := {"turbo": "TURBO", "escudo": "ESCUDO", "aceite": "ACEITE", "onda": "ONDA"}
const BOX_RESPAWN := 3.0
const OIL_LIFETIME := 15.0
const WAVE_RADIUS := 10.0

var track: Node3D
var race: Node
var cars: Array = []

var _boxes: Array[Area3D] = []
var _oils: Array = []
var _rng := RandomNumberGenerator.new()
var _box_material: StandardMaterial3D


func _ready() -> void:
	_rng.randomize()
	_box_material = StandardMaterial3D.new()
	_box_material.albedo_color = Color("ffd166")
	_box_material.emission_enabled = true
	_box_material.emission = Color("ff9f1c")
	_box_material.emission_energy_multiplier = 0.6
	_box_material.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	_box_material.albedo_color.a = 0.85

	for pos in track.item_box_positions():
		_add_box(pos)


## Se llama al empezar una carrera: limpia manchas y conecta los autos.
func reset(new_cars: Array) -> void:
	for o in _oils:
		if is_instance_valid(o.node):
			o.node.queue_free()
	_oils.clear()
	for b in _boxes:
		_set_box_active(b, true)
	cars = new_cars
	for c in cars:
		c.clear_effects()
		if not c.item_use_requested.is_connected(_on_use):
			c.item_use_requested.connect(_on_use)


func _process(delta: float) -> void:
	for b in _boxes:
		b.get_child(1).rotate_y(delta * 2.0)
		b.get_child(1).rotate_x(delta * 1.3)
		if not b.monitoring:
			b.set_meta("timer", b.get_meta("timer") - delta)
			if b.get_meta("timer") <= 0.0:
				_set_box_active(b, true)
	# Manchas de aceite: caducan con el tiempo.
	for o in _oils.duplicate():
		o.time -= delta
		if o.time <= 0.0 and is_instance_valid(o.node):
			o.node.queue_free()
			_oils.erase(o)


# ------------------------------------------------------------------ cajas

func _add_box(pos: Vector3) -> void:
	var area := Area3D.new()
	area.position = pos
	var col := CollisionShape3D.new()
	var shape := BoxShape3D.new()
	shape.size = Vector3(2.2, 2.4, 2.2)
	col.shape = shape
	area.add_child(col)
	var mesh := MeshInstance3D.new()
	var box := BoxMesh.new()
	box.size = Vector3(1.2, 1.2, 1.2)
	mesh.mesh = box
	mesh.material_override = _box_material
	area.add_child(mesh)
	var q := Label3D.new()
	q.text = "?"
	q.font_size = 120
	q.outline_size = 24
	q.pixel_size = 0.008
	q.billboard = BaseMaterial3D.BILLBOARD_ENABLED
	q.no_depth_test = false
	area.add_child(q)
	area.body_entered.connect(_on_box_touched.bind(area))
	add_child(area)
	_boxes.append(area)


func _set_box_active(b: Area3D, active: bool) -> void:
	b.set_deferred("monitoring", active)
	b.visible = active
	b.set_meta("timer", BOX_RESPAWN)


func _on_box_touched(body: Node3D, box: Area3D) -> void:
	var car := body.get_parent()
	if car not in cars or not box.visible:
		return
	_set_box_active(box, false)
	if car.item != "":
		return
	var kind := _roll_item(car)
	car.give_item(kind)
	item_picked.emit(car, kind)


## Los de atrás reciben más turbos y ondas; los de adelante, defensas.
func _roll_item(car: Node3D) -> String:
	var weights := {"turbo": 1.0, "escudo": 1.0, "aceite": 1.0, "onda": 1.0}
	if race and race.mode == race.Mode.RACE and cars.size() > 1:
		var standings: Array = race.standings()
		var rank := float(standings.find(car)) / (cars.size() - 1)  # 0 = líder
		weights = {
			"turbo": lerpf(1.0, 3.0, rank),
			"escudo": lerpf(2.5, 0.8, rank),
			"aceite": lerpf(2.5, 1.0, rank),
			"onda": lerpf(0.6, 2.5, rank),
		}
	var total := 0.0
	for k in weights:
		total += weights[k]
	var r := _rng.randf() * total
	for k in weights:
		r -= weights[k]
		if r <= 0.0:
			return k
	return "turbo"


# ----------------------------------------------------------------- usar

func _on_use(car: Node3D) -> void:
	var kind: String = car.take_item()
	if kind == "":
		return
	match kind:
		"turbo":
			car.start_boost()
		"escudo":
			car.start_shield()
		"aceite":
			_drop_oil(car)
		"onda":
			_shock_wave(car)
	item_used.emit(car, kind)


func _drop_oil(owner_car: Node3D) -> void:
	var pos: Vector3 = owner_car.get_car_position() - owner_car.get_forward_flat() * 5.0
	pos.y = 0.0
	var area := Area3D.new()
	area.position = pos + Vector3(0, 0.8, 0)
	var col := CollisionShape3D.new()
	var shape := CylinderShape3D.new()
	shape.radius = 2.0
	shape.height = 1.6
	col.shape = shape
	area.add_child(col)
	var puddle := MeshInstance3D.new()
	var disc := CylinderMesh.new()
	disc.top_radius = 2.2
	disc.bottom_radius = 2.2
	disc.height = 0.03
	puddle.mesh = disc
	puddle.position.y = -0.72
	var m := StandardMaterial3D.new()
	m.albedo_color = Color(0.05, 0.05, 0.08)
	m.metallic = 0.6
	m.roughness = 0.1
	puddle.material_override = m
	area.add_child(puddle)
	add_child(area)
	var oil := {"node": area, "time": OIL_LIFETIME, "owner": owner_car, "grace": 0.6}
	_oils.append(oil)
	area.body_entered.connect(func(body):
		var victim: Node = body.get_parent()
		if victim not in cars:
			return
		if victim == owner_car and OIL_LIFETIME - oil.time < oil.grace:
			return
		if victim.hit("aceite") and victim.use_player_input and Settings.vibration:
			Input.vibrate_handheld(120)
		oil.time = 0.0)
	if owner_car.has_method("_play"):
		owner_car._play("aceite", -4.0, 1.3)


func _shock_wave(source: Node3D) -> void:
	var center: Vector3 = source.get_car_position()
	for c in cars:
		if c == source:
			continue
		var d: Vector3 = c.get_car_position() - center
		d.y = 0.0
		if d.length() < WAVE_RADIUS:
			var push := d.normalized() * 13.0 + Vector3.UP * 5.0
			if c.hit("onda", push) and c.use_player_input and Settings.vibration:
				Input.vibrate_handheld(150)
	_ring_effect(center)
	var sfx := get_tree().get_first_node_in_group("sfx")
	if sfx:
		sfx.play_at("onda", center, 4.0)


func _ring_effect(center: Vector3) -> void:
	var ring := MeshInstance3D.new()
	var torus := TorusMesh.new()
	torus.inner_radius = 0.8
	torus.outer_radius = 1.0
	ring.mesh = torus
	var m := StandardMaterial3D.new()
	m.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	m.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	m.albedo_color = Color(0.5, 0.9, 1.0, 0.8)
	ring.material_override = m
	add_child(ring)
	ring.global_position = center + Vector3(0, 0.6, 0)
	var tw := create_tween().set_parallel()
	tw.tween_property(ring, "scale", Vector3.ONE * WAVE_RADIUS, 0.45)
	tw.tween_property(m, "albedo_color:a", 0.0, 0.45)
	tw.chain().tween_callback(ring.queue_free)


static func item_name(kind: String) -> String:
	return NAMES.get(kind, "")
