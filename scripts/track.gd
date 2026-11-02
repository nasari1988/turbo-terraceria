extends Node3D
## Pista de prueba: un óvalo de terracería con muros, una rampa en la recta
## y un atajo por el centro con su propio salto. Todo se arma por código.

const OUTER := Vector2(70.0, 40.0)   # radios del borde exterior (x, z)
const INNER := Vector2(56.0, 26.0)   # radios del borde interior
const SHORTCUT_HALF := 5.0           # medio ancho del atajo central
const WALL_HEIGHT := 1.1
const WALL_THICK := 0.8

const DIRT := Color("b07a45")
const DIRT_DARK := Color("a8733f")
const GRASS := Color("3f7a2e")
const GRASS_IN := Color("4a8a38")

var start_position := Vector3.ZERO
var start_heading := -PI / 2.0       # mirando hacia +x

var _materials := {}


func _ready() -> void:
	start_position = Vector3(-30.0, 1.2, mid_z(-30.0))
	_build_ground()
	_build_walls()
	_build_ramps()
	_build_start_line()
	_build_scenery()


## Punto medio del camino (lado de z negativa) para una x dada.
func mid_z(x: float) -> float:
	var outer := OUTER.y * sqrt(maxf(0.0, 1.0 - pow(x / OUTER.x, 2)))
	var inner := INNER.y * sqrt(maxf(0.0, 1.0 - pow(x / INNER.x, 2)))
	return -(outer + inner) / 2.0


func is_on_grass(p: Vector3) -> bool:
	var inside_inner := pow(p.x / INNER.x, 2) + pow(p.z / INNER.y, 2) < 1.0
	return inside_inner and absf(p.z) > SHORTCUT_HALF


# ------------------------------------------------------------------ partes

func _build_ground() -> void:
	# Suelo físico: una caja grande cuya cara superior está en y = 0.
	var body := StaticBody3D.new()
	var col := CollisionShape3D.new()
	var box := BoxShape3D.new()
	box.size = Vector3(500, 2, 500)
	col.shape = box
	col.position.y = -1.0
	body.add_child(col)
	add_child(body)

	var grass := MeshInstance3D.new()
	var plane := PlaneMesh.new()
	plane.size = Vector2(500, 500)
	grass.mesh = plane
	grass.material_override = _mat(GRASS)
	add_child(grass)

	_flat_ellipse(OUTER, 0.02, DIRT)
	_flat_ellipse(INNER, 0.04, GRASS_IN)
	_flat_box(Vector3(INNER.x * 2.0, 0.02, SHORTCUT_HALF * 2.0), Vector3(0, 0.06, 0), DIRT_DARK)


func _build_walls() -> void:
	_ellipse_wall(OUTER + Vector2(WALL_THICK, WALL_THICK) * 0.5, 80, Color("e63946"), Color("f1faee"), false)
	_ellipse_wall(INNER - Vector2(WALL_THICK, WALL_THICK) * 0.5, 60, Color("ffd166"), Color("333333"), true)


func _ellipse_wall(radii: Vector2, segments: int, color_a: Color, color_b: Color, leave_gaps: bool) -> void:
	for i in segments:
		var a0 := TAU * i / segments
		var a1 := TAU * (i + 1) / segments
		var p0 := Vector3(cos(a0) * radii.x, 0, sin(a0) * radii.y)
		var p1 := Vector3(cos(a1) * radii.x, 0, sin(a1) * radii.y)
		var mid := (p0 + p1) / 2.0
		# Huecos en el muro interior para entrar y salir del atajo.
		if leave_gaps and absf(mid.z) < SHORTCUT_HALF + 2.0:
			continue
		var d := p1 - p0
		var length := d.length() + 0.3
		var t := Transform3D(Basis(Vector3.UP, atan2(d.x, d.z)), mid + Vector3(0, WALL_HEIGHT / 2.0, 0))
		_static_box(Vector3(WALL_THICK, WALL_HEIGHT, length), t, color_a if i % 2 == 0 else color_b)


func _build_ramps() -> void:
	# Rampa en la recta principal: sube hacia +x.
	_ramp(Vector3(15.0, 0, mid_z(15.0)), 1.0, Vector2(10.0, 9.0), deg_to_rad(14.0))
	# Rampa en el atajo: sube hacia -x (la dirección en que se recorre).
	_ramp(Vector3(0, 0, 0), -1.0, Vector2(10.0, SHORTCUT_HALF * 2.0 - 1.0), deg_to_rad(12.0))


## Rampa hecha con una caja inclinada cuyo borde bajo queda al ras del suelo.
func _ramp(center: Vector3, direction: float, size: Vector2, angle: float) -> void:
	var thick := 1.0
	var y := (size.x / 2.0) * sin(angle) - (thick / 2.0) * cos(angle)
	var tilt := Basis(Vector3.BACK, angle * direction)
	var t := Transform3D(tilt, Vector3(center.x, y, center.z))
	_static_box(Vector3(size.x, thick, size.y), t, Color("8d6e63"))
	# Franjas amarillas para que se vea desde lejos.
	for k in 3:
		var stripe := MeshInstance3D.new()
		var b := BoxMesh.new()
		b.size = Vector3(0.6, 0.05, size.y)
		stripe.mesh = b
		stripe.material_override = _mat(Color("ffd166"))
		stripe.transform = t * Transform3D(Basis(), Vector3((k - 1) * 3.0, thick / 2.0, 0))
		add_child(stripe)


func _build_start_line() -> void:
	var x := -22.0
	var outer := -OUTER.y * sqrt(1.0 - pow(x / OUTER.x, 2))
	var inner := -INNER.y * sqrt(1.0 - pow(x / INNER.x, 2))
	var width := absf(outer - inner)
	var cells := 10
	for i in cells:
		for j in 2:
			var c := Color.WHITE if (i + j) % 2 == 0 else Color("222222")
			var cell := width / cells
			_flat_box(Vector3(0.8, 0.02, cell), Vector3(x + j * 0.8, 0.07, inner - cell * (i + 0.5)), c)


func _build_scenery() -> void:
	# Árboles alrededor (sin colisión: quedan fuera de los muros).
	var rng := RandomNumberGenerator.new()
	rng.seed = 2026
	for i in 70:
		var ang := rng.randf() * TAU
		var r := rng.randf_range(1.15, 1.9)
		var pos := Vector3(cos(ang) * OUTER.x * r, 0, sin(ang) * OUTER.y * r * 1.3)
		_tree(pos, rng.randf_range(0.8, 1.4))
	# Algunos árboles en el pasto del centro.
	for p in [Vector3(-30, 0, 14), Vector3(25, 0, -15), Vector3(35, 0, 13), Vector3(-20, 0, -14)]:
		_tree(p, 1.0)


func _tree(pos: Vector3, s: float) -> void:
	var trunk := MeshInstance3D.new()
	var tc := CylinderMesh.new()
	tc.top_radius = 0.25 * s
	tc.bottom_radius = 0.3 * s
	tc.height = 1.6 * s
	tc.radial_segments = 6
	trunk.mesh = tc
	trunk.position = pos + Vector3(0, 0.8 * s, 0)
	trunk.material_override = _mat(Color("6d4c41"))
	add_child(trunk)
	var top := MeshInstance3D.new()
	var cone := CylinderMesh.new()
	cone.top_radius = 0.0
	cone.bottom_radius = 1.6 * s
	cone.height = 3.6 * s
	cone.radial_segments = 7
	top.mesh = cone
	top.position = pos + Vector3(0, 3.2 * s, 0)
	top.material_override = _mat(Color("2e7d32"))
	add_child(top)


# --------------------------------------------------------------- ayudas

func _static_box(size: Vector3, t: Transform3D, color: Color) -> void:
	var body := StaticBody3D.new()
	body.transform = t
	var col := CollisionShape3D.new()
	var shape := BoxShape3D.new()
	shape.size = size
	col.shape = shape
	body.add_child(col)
	var mesh := MeshInstance3D.new()
	var box := BoxMesh.new()
	box.size = size
	mesh.mesh = box
	mesh.material_override = _mat(color)
	body.add_child(mesh)
	add_child(body)


func _flat_ellipse(radii: Vector2, y: float, color: Color) -> void:
	var m := MeshInstance3D.new()
	var cyl := CylinderMesh.new()
	cyl.top_radius = 1.0
	cyl.bottom_radius = 1.0
	cyl.height = 0.02
	cyl.radial_segments = 96
	cyl.rings = 1
	m.mesh = cyl
	m.scale = Vector3(radii.x, 1.0, radii.y)
	m.position.y = y
	m.material_override = _mat(color)
	add_child(m)


func _flat_box(size: Vector3, pos: Vector3, color: Color) -> void:
	var m := MeshInstance3D.new()
	var box := BoxMesh.new()
	box.size = size
	m.mesh = box
	m.position = pos
	m.material_override = _mat(color)
	add_child(m)


func _mat(color: Color) -> StandardMaterial3D:
	var key := color.to_html()
	if not _materials.has(key):
		var m := StandardMaterial3D.new()
		m.albedo_color = color
		_materials[key] = m
	return _materials[key]
