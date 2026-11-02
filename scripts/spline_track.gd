extends Node3D
## Pista temática construida a partir de puntos de control (curva suave
## cerrada). Genera el camino, los bordes, los muros, rampas, la meta y la
## decoración según el tema: desierto, nieve, bosque o pueblo.
## Ofrece la misma interfaz que track.gd (el óvalo) para la carrera y la IA.

const HALF := 6.5          # medio ancho del camino
const RUNOFF := 2.5        # orilla (más lenta) antes del muro
const WALL_H := 1.1
const WALL_T := 0.6
const STEP := 2.0          # separación entre muestras de la línea central

const THEMES := {
	"desierto": {
		"name": "Cañón Seco",
		"points": [[0, -90], [90, -95], [150, -70], [170, -10], [140, 40], [80, 50], [40, 90],
			[-30, 110], [-110, 90], [-150, 30], [-140, -40], [-90, -85]],
		"ground": Color("d9b26f"), "road": Color("b8794a"), "runoff": Color("e3c48a"), "dust": Color("e3c48a"),
		"wall_a": Color("c1440e"), "wall_b": Color("f1e3c6"),
		"sky_top": Color("3d8fd6"), "sky_horizon": Color("f7d9a8"), "fog": 0.0015,
		"sun": Color("fff1d6"), "sun_energy": 1.0, "grip": 1.0,
		"ramps": [0.18, 0.62], "items": [0.1, 0.45, 0.8],
	},
	"nieve": {
		"name": "Pico Helado",
		"points": [[0, -70], [70, -75], [110, -40], [95, 0], [45, 12], [30, 50], [55, 85],
			[60, 120], [10, 135], [-50, 120], [-90, 80], [-120, 20], [-100, -45], [-50, -70]],
		"ground": Color("eef4fb"), "road": Color("8fa3b8"), "runoff": Color("ffffff"), "dust": Color("ffffff"),
		"wall_a": Color("2b6cb0"), "wall_b": Color("e8f1fb"),
		"sky_top": Color("7fa6c9"), "sky_horizon": Color("dfe9f2"), "fog": 0.006,
		"sun": Color("e8f0ff"), "sun_energy": 0.8, "grip": 0.6,
		"ramps": [0.55], "items": [0.12, 0.42, 0.75],
	},
	"bosque": {
		"name": "Sendero del Bosque",
		"points": [[0, -80], [60, -90], [110, -60], [110, -10], [75, 20], [85, 60], [55, 100],
			[-5, 100], [-45, 65], [-95, 75], [-135, 30], [-115, -30], [-60, -65]],
		"ground": Color("3f6b2a"), "road": Color("7a5536"), "runoff": Color("5c4a2e"), "dust": Color("9c7a55"),
		"wall_a": Color("6d4c41"), "wall_b": Color("a1887f"),
		"sky_top": Color("5b9bd5"), "sky_horizon": Color("cfe6d5"), "fog": 0.004,
		"sun": Color("fff6e0"), "sun_energy": 0.9, "grip": 0.95,
		"ramps": [0.3, 0.85], "items": [0.08, 0.5, 0.72],
	},
	"pueblo": {
		"name": "Calles del Pueblo",
		"points": [[0, -80], [65, -80], [102, -70], [118, -38], [116, 10], [98, 40], [62, 45],
			[40, 62], [35, 90], [8, 108], [-60, 108], [-98, 96], [-112, 55], [-112, 0],
			[-106, -52], [-78, -78]],
		"ground": Color("8a9a6a"), "road": Color("9c7b5b"), "runoff": Color("b0aaa0"), "dust": Color("c2a383"),
		"wall_a": Color("d62828"), "wall_b": Color("fcbf49"),
		"sky_top": Color("4a90d9"), "sky_horizon": Color("ffe8cc"), "fog": 0.002,
		"sun": Color("ffe9c7"), "sun_energy": 1.0, "grip": 1.0,
		"ramps": [0.4], "items": [0.15, 0.5, 0.82],
	},
}

var theme_id := "desierto"
var theme: Dictionary
var track_name := ""
var has_shortcut := false
var grip := 1.0
var length := 0.0
var start_position := Vector3.ZERO
var start_heading := 0.0

var _pos: PackedVector3Array = []
var _tan: PackedVector3Array = []
var _right: PackedVector3Array = []
var _radius: PackedFloat32Array = []
var _rng := RandomNumberGenerator.new()
var _step := STEP


func _ready() -> void:
	theme = THEMES[theme_id]
	track_name = theme.name
	grip = theme.grip
	_rng.seed = hash(theme_id)
	_sample_centerline()
	_build_ground()
	_build_road()
	_build_walls()
	for f in theme.ramps:
		_build_ramp(f * length)
	_build_start_line()
	_build_scenery()
	var g := grid_slot(0)
	start_position = g.position
	start_heading = g.heading


# ================================================================ interfaz

func progress_angle(p: Vector3) -> float:
	return TAU * _progress_s(p) / length


func start_angle() -> float:
	return 0.0


func centerline_point(angle: float, lateral := 0.0) -> Vector3:
	var s := fposmod(angle / TAU * length, length)
	var i := int(s / _step) % _pos.size()
	var j := (i + 1) % _pos.size()
	var t := (s - i * _step) / _step
	var p := _pos[i].lerp(_pos[j], t)
	var r := _right[i].lerp(_right[j], t).normalized()
	return p + r * lateral


func centerline_heading(angle: float) -> float:
	var s := fposmod(angle / TAU * length, length)
	var t := _tan[int(s / _step) % _pos.size()]
	return atan2(-t.x, -t.z)


func grid_slot(slot: int) -> Dictionary:
	var s := -10.0 - slot * 5.5
	var angle := TAU * s / length
	var lateral := -3.0 if slot % 2 == 0 else 3.0
	return {"position": centerline_point(angle, lateral) + Vector3(0, 1.2, 0),
		"heading": centerline_heading(angle)}


## Cuánto mira adelante la IA (en radianes de vuelta) ≈ 16 m.
func lookahead() -> float:
	return TAU * 16.0 / length


## Radio de curva más cerrado en los próximos `meters` metros.
func curve_radius_ahead(angle: float, meters := 35.0) -> float:
	var i0 := int(fposmod(angle / TAU * length, length) / _step)
	var r := INF
	for k in int(meters / _step):
		r = minf(r, _radius[(i0 + k) % _radius.size()])
	return r


func item_box_positions() -> Array:
	var out := []
	for f in theme.items:
		for lateral in [-3.5, 0.0, 3.5]:
			out.append(centerline_point(f * TAU, lateral) + Vector3(0, 1.1, 0))
	return out


func is_on_shortcut(_p: Vector3) -> bool:
	return false


## "Pasto" = la orilla fuera del camino (arena, nieve honda, etc.): frena.
func is_on_grass(p: Vector3) -> bool:
	var i := _nearest_index(p)
	var d := p - _pos[i]
	return absf(d.dot(_right[i])) > HALF + 0.3


func environment_settings() -> Dictionary:
	return theme


# ============================================================== geometría

func _sample_centerline() -> void:
	var pts: Array = theme.points
	var n := pts.size()
	var dense: Array[Vector3] = []
	for i in n:
		var p0 := _v(pts[(i - 1 + n) % n])
		var p1 := _v(pts[i])
		var p2 := _v(pts[(i + 1) % n])
		var p3 := _v(pts[(i + 2) % n])
		for k in 40:
			var t := k / 40.0
			var t2 := t * t
			var t3 := t2 * t
			dense.append(0.5 * ((2.0 * p1) + (-p0 + p2) * t + (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * t2
				+ (-p0 + 3.0 * p1 - 3.0 * p2 + p3) * t3))
	# Re-muestreo a distancias iguales (≈ STEP metros).
	var total := 0.0
	for i in dense.size():
		total += dense[i].distance_to(dense[(i + 1) % dense.size()])
	var count := int(round(total / _step))
	_step = total / count
	length = total
	var target := 0.0
	var acc := 0.0
	var idx := 0
	_pos.clear()
	while _pos.size() < count:
		var a := dense[idx % dense.size()]
		var b := dense[(idx + 1) % dense.size()]
		var seg := a.distance_to(b)
		if acc + seg >= target:
			_pos.append(a.lerp(b, (target - acc) / seg))
			target += _step
		else:
			acc += seg
			idx += 1
	for i in count:
		var t := (_pos[(i + 1) % count] - _pos[(i - 1 + count) % count]).normalized()
		_tan.append(t)
		_right.append(t.cross(Vector3.UP).normalized())
	for i in count:
		var a1 := atan2(_tan[i].x, _tan[i].z)
		var a2 := atan2(_tan[(i + 1) % count].x, _tan[(i + 1) % count].z)
		var da := absf(wrapf(a2 - a1, -PI, PI))
		_radius.append(_step / maxf(da, 0.0001))


func _v(p: Array) -> Vector3:
	return Vector3(p[0], 0, p[1])


func _nearest_index(p: Vector3) -> int:
	var best := 0
	var best_d := INF
	var n := _pos.size()
	for i in range(0, n, 4):
		var d := Vector2(_pos[i].x - p.x, _pos[i].z - p.z).length_squared()
		if d < best_d:
			best_d = d
			best = i
	for k in range(-4, 5):
		var i := (best + k + n) % n
		var d := Vector2(_pos[i].x - p.x, _pos[i].z - p.z).length_squared()
		if d < best_d:
			best_d = d
			best = i
	return best


func _progress_s(p: Vector3) -> float:
	var i := _nearest_index(p)
	var along := (p - _pos[i]).dot(_tan[i])
	return fposmod(i * _step + clampf(along, -_step, _step), length)


# ============================================================ construcción

func _build_ground() -> void:
	var body := StaticBody3D.new()
	var col := CollisionShape3D.new()
	var box := BoxShape3D.new()
	box.size = Vector3(800, 2, 800)
	col.shape = box
	col.position.y = -1.0
	body.add_child(col)
	add_child(body)
	var plane := MeshInstance3D.new()
	var pm := PlaneMesh.new()
	pm.size = Vector2(800, 800)
	plane.mesh = pm
	plane.material_override = _flat(theme.ground)
	add_child(plane)


## Camino y orillas como franjas continuas.
func _build_road() -> void:
	var st := SurfaceTool.new()
	st.begin(Mesh.PRIMITIVE_TRIANGLES)
	var n := _pos.size()
	var bands := [[-HALF - RUNOFF, -HALF, theme.runoff], [-HALF, HALF, theme.road], [HALF, HALF + RUNOFF, theme.runoff]]
	for band in bands:
		for i in n:
			var j := (i + 1) % n
			var shade := 1.0 if (i / 3) % 2 == 0 else 0.96
			var c: Color = band[2] * shade
			c.a = 1.0
			var y := 0.03 if band[2] == theme.road else 0.02
			var a: Vector3 = _pos[i] + _right[i] * band[0] + Vector3(0, y, 0)
			var b: Vector3 = _pos[i] + _right[i] * band[1] + Vector3(0, y, 0)
			var cc: Vector3 = _pos[j] + _right[j] * band[1] + Vector3(0, y, 0)
			var d: Vector3 = _pos[j] + _right[j] * band[0] + Vector3(0, y, 0)
			_quad(st, a, b, cc, d, c)
	st.generate_normals()
	var mesh := MeshInstance3D.new()
	mesh.mesh = st.commit()
	var road_mat := _vertex_color_material()
	road_mat.cull_mode = BaseMaterial3D.CULL_DISABLED
	mesh.material_override = road_mat
	add_child(mesh)


func _build_walls() -> void:
	var st := SurfaceTool.new()
	st.begin(Mesh.PRIMITIVE_TRIANGLES)
	var faces := PackedVector3Array()
	var n := _pos.size()
	for side in [-1.0, 1.0]:
		var inner := HALF + RUNOFF
		for i in n:
			var j := (i + 1) % n
			var c: Color = theme.wall_a if (i / 2) % 2 == 0 else theme.wall_b
			var a0: Vector3 = _pos[i] + _right[i] * inner * side
			var b0: Vector3 = _pos[j] + _right[j] * inner * side
			var a1: Vector3 = _pos[i] + _right[i] * (inner + WALL_T) * side
			var b1: Vector3 = _pos[j] + _right[j] * (inner + WALL_T) * side
			var up := Vector3(0, WALL_H, 0)
			# Cara interior, superior y exterior.
			_quad(st, a0, b0, b0 + up, a0 + up, c)
			_quad(st, a0 + up, b0 + up, b1 + up, a1 + up, c.lightened(0.15))
			_quad(st, a1, a1 + up, b1 + up, b1, c.darkened(0.2))
			for tri in [[a0, b0, b0 + up], [a0, b0 + up, a0 + up], [a1, b1 + up, b1], [a1, a1 + up, b1 + up]]:
				faces.append_array(PackedVector3Array(tri))
	st.generate_normals()
	var mesh := MeshInstance3D.new()
	mesh.mesh = st.commit()
	var mat := _vertex_color_material()
	mat.cull_mode = BaseMaterial3D.CULL_DISABLED
	mesh.material_override = mat
	add_child(mesh)

	var body := StaticBody3D.new()
	var col := CollisionShape3D.new()
	var shape := ConcavePolygonShape3D.new()
	shape.backface_collision = true
	shape.set_faces(faces)
	col.shape = shape
	body.add_child(col)
	add_child(body)


func _build_ramp(s: float) -> void:
	var angle := TAU * s / length
	var center := centerline_point(angle)
	var heading := centerline_heading(angle)
	var size := Vector3(HALF * 2.0 - 1.0, 1.0, 10.0)   # ancho, grosor, largo
	var tilt := deg_to_rad(13.0)
	var y := (size.z / 2.0) * sin(tilt) - (size.y / 2.0) * cos(tilt)
	var b := Basis(Vector3.UP, heading) * Basis(Vector3.RIGHT, tilt)
	var t := Transform3D(b, center + Vector3(0, y, 0))
	var body := StaticBody3D.new()
	body.transform = t
	var col := CollisionShape3D.new()
	var shape := BoxShape3D.new()
	shape.size = size
	col.shape = shape
	body.add_child(col)
	var mesh := MeshInstance3D.new()
	var bm := BoxMesh.new()
	bm.size = size
	mesh.mesh = bm
	mesh.material_override = _flat(Color("8d6e63"))
	body.add_child(mesh)
	for k in 3:
		var stripe := MeshInstance3D.new()
		var sb := BoxMesh.new()
		sb.size = Vector3(size.x, 0.05, 0.6)
		stripe.mesh = sb
		stripe.material_override = _flat(Color("ffd166"))
		stripe.position = Vector3(0, size.y / 2.0, (k - 1) * 3.0)
		body.add_child(stripe)
	add_child(body)


func _build_start_line() -> void:
	var st := SurfaceTool.new()
	st.begin(Mesh.PRIMITIVE_TRIANGLES)
	var cells := 10
	var w := HALF * 2.0 / cells
	for row in 2:
		for k in cells:
			var c := Color.WHITE if (k + row) % 2 == 0 else Color("222222")
			var l0 := -HALF + k * w
			var p0 := _pos[0] + _tan[0] * (row * 0.8) + Vector3(0, 0.05, 0)
			var p1 := _pos[0] + _tan[0] * ((row + 1) * 0.8) + Vector3(0, 0.05, 0)
			_quad(st, p0 + _right[0] * l0, p0 + _right[0] * (l0 + w), p1 + _right[0] * (l0 + w), p1 + _right[0] * l0, c)
	st.generate_normals()
	var mesh := MeshInstance3D.new()
	mesh.mesh = st.commit()
	mesh.material_override = _vertex_color_material()
	add_child(mesh)
	# Arco de meta.
	for side in [-1.0, 1.0]:
		var post := MeshInstance3D.new()
		var pb := BoxMesh.new()
		pb.size = Vector3(0.5, 6.0, 0.5)
		post.mesh = pb
		post.material_override = _flat(Color("222222"))
		post.position = _pos[0] + _right[0] * (HALF + RUNOFF + 1.2) * side + Vector3(0, 3, 0)
		add_child(post)
	var banner := MeshInstance3D.new()
	var bb := BoxMesh.new()
	bb.size = Vector3((HALF + RUNOFF + 1.2) * 2.0, 1.2, 0.3)
	banner.mesh = bb
	banner.material_override = _flat(Color("e63946"))
	banner.transform = Transform3D(Basis(Vector3.UP, centerline_heading(0.0)), _pos[0] + Vector3(0, 6.2, 0))
	add_child(banner)
	var label := Label3D.new()
	label.text = "META"
	label.font_size = 160
	label.pixel_size = 0.006
	label.outline_size = 20
	banner.add_child(label)
	label.position = Vector3(0, 0, 0.2)
	var back := label.duplicate()
	back.rotation.y = PI
	back.position = Vector3(0, 0, -0.2)
	banner.add_child(back)


# ============================================================= decoración

func _build_scenery() -> void:
	match theme_id:
		"desierto":
			_scatter(_cactus(), 70, 4.0, 70.0, 0.8, 1.5)
			_scatter(_rock(Color("b5651d")), 60, 4.0, 90.0, 0.6, 2.2)
			_scatter(_mesa(), 14, 90.0, 200.0, 0.8, 1.6)
		"nieve":
			_scatter(_pine(Color("2f5d50"), true), 140, 4.0, 90.0, 0.8, 1.6)
			_scatter(_rock(Color("c9d6e3")), 40, 4.0, 60.0, 0.5, 1.6)
			_scatter(_mountain(), 12, 110.0, 220.0, 1.0, 1.8)
		"bosque":
			_scatter(_pine(Color("1f5c2e"), false), 170, 3.5, 90.0, 0.8, 1.7)
			_scatter(_round_tree(), 90, 3.5, 80.0, 0.8, 1.5)
			_scatter(_rock(Color("7d7d7d")), 30, 3.5, 50.0, 0.5, 1.3)
		"pueblo":
			_scatter(_house(), 90, 5.0, 45.0, 0.9, 1.4, true)
			_scatter(_lamp(), 50, 1.5, 3.0, 1.0, 1.0, true)
			_scatter(_round_tree(), 50, 10.0, 80.0, 0.8, 1.3)


## Reparte copias de `mesh` fuera de la pista, entre `min_d` y `max_d` metros
## del muro. Con `face_road`, se orientan hacia el camino.
func _scatter(mesh: Mesh, total: int, min_d: float, max_d: float, smin: float, smax: float, face_road := false) -> void:
	var count := maxi(1, int(total * preload("res://scripts/settings.gd").scenery_density()))
	var mm := MultiMesh.new()
	mm.transform_format = MultiMesh.TRANSFORM_3D
	mm.mesh = mesh
	var transforms: Array[Transform3D] = []
	var tries := 0
	var edge := HALF + RUNOFF + WALL_T
	while transforms.size() < count and tries < count * 30:
		tries += 1
		var i := _rng.randi() % _pos.size()
		var side := -1.0 if _rng.randf() < 0.5 else 1.0
		var dist := edge + _rng.randf_range(min_d, max_d)
		var p := _pos[i] + _right[i] * dist * side
		# Que no caiga sobre otra parte de la pista.
		var j := _nearest_index(p)
		if Vector2(p.x - _pos[j].x, p.z - _pos[j].z).length() < edge + min_d - 0.1:
			continue
		var s := _rng.randf_range(smin, smax)
		var yaw := _rng.randf() * TAU
		if face_road:
			var to_road := -_right[i] * side
			yaw = atan2(to_road.x, to_road.z)
		transforms.append(Transform3D(Basis(Vector3.UP, yaw).scaled(Vector3.ONE * s), p))
	mm.instance_count = transforms.size()
	for k in transforms.size():
		mm.set_instance_transform(k, transforms[k])
	var inst := MultiMeshInstance3D.new()
	inst.multimesh = mm
	inst.material_override = _vertex_color_material()
	add_child(inst)


func _cactus() -> Mesh:
	var green := Color("4f772d")
	return _compound([
		[_cyl(0.35, 0.4, 4.0), Transform3D(Basis(), Vector3(0, 2.0, 0)), green],
		[_cyl(0.25, 0.25, 1.6), Transform3D(Basis(), Vector3(0.75, 2.6, 0)), green],
		[_cyl(0.25, 0.25, 0.6), Transform3D(Basis(Vector3.BACK, PI / 2.0), Vector3(0.4, 1.9, 0)), green],
		[_cyl(0.22, 0.22, 1.2), Transform3D(Basis(), Vector3(-0.7, 2.2, 0)), green],
		[_cyl(0.22, 0.22, 0.5), Transform3D(Basis(Vector3.BACK, PI / 2.0), Vector3(-0.35, 1.7, 0)), green],
	])


func _rock(color: Color) -> Mesh:
	var s := SphereMesh.new()
	s.radius = 1.0
	s.height = 1.3
	s.radial_segments = 6
	s.rings = 3
	return _compound([[s, Transform3D(Basis().scaled(Vector3(1.3, 1.0, 1.0)), Vector3(0, 0.3, 0)), color]])


func _mesa() -> Mesh:
	var c := Color("b5562b")
	return _compound([
		[_box(Vector3(30, 22, 24)), Transform3D(Basis(), Vector3(0, 11, 0)), c],
		[_box(Vector3(24, 4, 20)), Transform3D(Basis(), Vector3(2, 24, 1)), c.lightened(0.1)],
	])


func _mountain() -> Mesh:
	return _compound([
		[_cyl(0.0, 40.0, 50.0, 7), Transform3D(Basis(), Vector3(0, 25, 0)), Color("7d8ea3")],
		[_cyl(0.0, 16.0, 20.0, 7), Transform3D(Basis(), Vector3(0, 40.1, 0)), Color("ffffff")],
	])


func _pine(color: Color, snowy: bool) -> Mesh:
	var parts := [[_cyl(0.25, 0.3, 1.6, 6), Transform3D(Basis(), Vector3(0, 0.8, 0)), Color("5d4037")]]
	for k in 3:
		var r := 1.8 - k * 0.45
		var y := 2.0 + k * 1.3
		parts.append([_cyl(0.0, r, 2.2, 7), Transform3D(Basis(), Vector3(0, y, 0)), color])
		if snowy:
			parts.append([_cyl(0.0, r * 0.55, 1.0, 7), Transform3D(Basis(), Vector3(0, y + 0.65, 0)), Color("ffffff")])
	return _compound(parts)


func _round_tree() -> Mesh:
	var crown := SphereMesh.new()
	crown.radius = 1.8
	crown.height = 3.4
	crown.radial_segments = 8
	crown.rings = 4
	return _compound([
		[_cyl(0.25, 0.3, 2.4, 6), Transform3D(Basis(), Vector3(0, 1.2, 0)), Color("6d4c41")],
		[crown, Transform3D(Basis(), Vector3(0, 3.6, 0)), Color("6a9a3a")],
	])


func _house() -> Mesh:
	var palette := [Color("e76f51"), Color("f4a261"), Color("e9c46a"), Color("2a9d8f"), Color("90be6d"), Color("f28482")]
	var wall: Color = palette[_rng.randi() % palette.size()]
	var w := _rng.randf_range(6.0, 9.0)
	var h := _rng.randf_range(3.5, 6.0)
	var roof := PrismMesh.new()
	roof.size = Vector3(w + 0.6, 2.0, 6.6)
	return _compound([
		[_box(Vector3(w, h, 6.0)), Transform3D(Basis(), Vector3(0, h / 2.0, -3.0)), wall],
		[roof, Transform3D(Basis(), Vector3(0, h + 1.0, -3.0)), Color("8d3b2f")],
		[_box(Vector3(1.2, 2.2, 0.1)), Transform3D(Basis(), Vector3(0, 1.1, 0.02)), Color("5d4037")],
		[_box(Vector3(1.0, 1.0, 0.1)), Transform3D(Basis(), Vector3(-w * 0.3, h * 0.6, 0.02)), Color("bde0fe")],
		[_box(Vector3(1.0, 1.0, 0.1)), Transform3D(Basis(), Vector3(w * 0.3, h * 0.6, 0.02)), Color("bde0fe")],
	])


func _lamp() -> Mesh:
	return _compound([
		[_cyl(0.08, 0.1, 4.0, 6), Transform3D(Basis(), Vector3(0, 2.0, 0)), Color("333333")],
		[_box(Vector3(0.5, 0.3, 0.5)), Transform3D(Basis(), Vector3(0, 4.1, 0)), Color("ffe08a")],
	])


func _compound(parts: Array) -> ArrayMesh:
	var st := SurfaceTool.new()
	st.begin(Mesh.PRIMITIVE_TRIANGLES)
	for part in parts:
		var arrays: Array = part[0].get_mesh_arrays()
		var verts: PackedVector3Array = arrays[Mesh.ARRAY_VERTEX]
		var normals: PackedVector3Array = arrays[Mesh.ARRAY_NORMAL]
		var indices: PackedInt32Array = arrays[Mesh.ARRAY_INDEX]
		var xf: Transform3D = part[1]
		for idx in indices:
			st.set_color(part[2])
			st.set_normal((xf.basis * normals[idx]).normalized())
			st.add_vertex(xf * verts[idx])
	return st.commit()


func _cyl(top: float, bottom: float, h: float, seg := 8) -> CylinderMesh:
	var c := CylinderMesh.new()
	c.top_radius = top
	c.bottom_radius = bottom
	c.height = h
	c.radial_segments = seg
	c.rings = 1
	return c


func _box(size: Vector3) -> BoxMesh:
	var b := BoxMesh.new()
	b.size = size
	return b


func _quad(st: SurfaceTool, a: Vector3, b: Vector3, c: Vector3, d: Vector3, color: Color) -> void:
	for v in [a, b, c, a, c, d]:
		st.set_color(color)
		st.add_vertex(v)


func _vertex_color_material() -> StandardMaterial3D:
	var m := StandardMaterial3D.new()
	m.vertex_color_use_as_albedo = true
	return m


func _flat(color: Color) -> StandardMaterial3D:
	var m := StandardMaterial3D.new()
	m.albedo_color = color
	return m
