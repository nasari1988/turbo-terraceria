extends Node
## Todos los sonidos del juego se generan aquí por código (no hay archivos
## de audio): motor, derrape, golpes, power-ups, cuenta regresiva y una
## pista de música original que se compone al arrancar.

const RATE := 22050

var streams := {}
static var _cache := {}   # se conservan al recargar la escena
var music_player: AudioStreamPlayer
var _pool: Array[AudioStreamPlayer] = []
var _pool3d: Array[AudioStreamPlayer3D] = []
var _rng := RandomNumberGenerator.new()


func _ready() -> void:
	add_to_group("sfx")
	_rng.seed = 7
	if not _cache.is_empty():
		streams = _cache
	else:
		_generate()
		_cache = streams
	_make_players()


func _generate() -> void:
	streams.motor = _wav(_make_engine(), true)
	streams.derrape = _wav(_make_skid(), true)
	streams.golpe = _wav(_make_hit())
	streams.turbo = _wav(_make_whoosh())
	streams.item = _wav(_make_blips([0, 4, 7, 12], 0.06, 660.0))
	streams.beep = _wav(_make_tone(660.0, 0.2))
	streams.ya = _wav(_make_tone(990.0, 0.55))
	streams.onda = _wav(_make_boom())
	streams.aceite = _wav(_make_splat())
	streams.escudo = _wav(_make_shimmer())
	streams.meta = _wav(_make_blips([0, 4, 7, 12, 16, 19, 24], 0.09, 523.0))


func _make_players() -> void:
	for i in 8:
		var p := AudioStreamPlayer.new()
		add_child(p)
		_pool.append(p)
	for i in 10:
		var p3 := AudioStreamPlayer3D.new()
		p3.unit_size = 12.0
		add_child(p3)
		_pool3d.append(p3)

	music_player = AudioStreamPlayer.new()
	music_player.volume_db = -13.0
	add_child(music_player)
	if streams.has("musica"):
		music_player.stream = streams.musica
		music_player.play()
	else:
		WorkerThreadPool.add_task(_build_music)


# ------------------------------------------------------------- reproducir

func play(sound: String, volume_db := 0.0, pitch := 1.0) -> void:
	for p in _pool:
		if not p.playing:
			p.stream = streams[sound]
			p.volume_db = volume_db
			p.pitch_scale = pitch
			p.play()
			return


func play_at(sound: String, pos: Vector3, volume_db := 0.0, pitch := 1.0) -> void:
	for p in _pool3d:
		if not p.playing:
			p.stream = streams[sound]
			p.global_position = pos
			p.volume_db = volume_db
			p.pitch_scale = pitch
			p.play()
			return


func set_muted(muted: bool) -> void:
	AudioServer.set_bus_mute(0, muted)


# ---------------------------------------------------------------- sonidos

func _make_engine() -> PackedFloat32Array:
	# 1 segundo con frecuencias enteras para que el ciclo empate sin "clic".
	var n := RATE
	var out := PackedFloat32Array()
	out.resize(n)
	var lp := 0.0
	for i in n:
		var t := float(i) / RATE
		var s := 0.0
		for k in range(1, 9):
			s += sin(TAU * 50.0 * k * t + k * 0.7) / pow(k, 0.8)
		var pulse := 0.6 + 0.4 * sin(TAU * 25.0 * t)
		lp += (_rng.randf_range(-1, 1) - lp) * 0.15
		out[i] = (s * 0.22 * pulse + lp * 0.25)
	return out


func _make_skid() -> PackedFloat32Array:
	var n := RATE
	var out := PackedFloat32Array()
	out.resize(n)
	var lp := 0.0
	var lp2 := 0.0
	for i in n:
		var t := float(i) / RATE
		lp += (_rng.randf_range(-1, 1) - lp) * 0.5
		lp2 += (lp - lp2) * 0.05
		var squeal := sin(TAU * 1100.0 * t + 2.0 * sin(TAU * 7.0 * t))
		out[i] = squeal * 0.18 + (lp - lp2) * 0.6
	return out


func _make_hit() -> PackedFloat32Array:
	var n := int(RATE * 0.35)
	var out := PackedFloat32Array()
	out.resize(n)
	var lp := 0.0
	for i in n:
		var t := float(i) / RATE
		lp += (_rng.randf_range(-1, 1) - lp) * 0.35
		out[i] = lp * exp(-t * 14.0) * 1.2 + sin(TAU * 70.0 * t) * exp(-t * 20.0) * 0.8
	return out


func _make_whoosh() -> PackedFloat32Array:
	var n := int(RATE * 0.8)
	var out := PackedFloat32Array()
	out.resize(n)
	var lp := 0.0
	for i in n:
		var f := float(i) / n
		lp += (_rng.randf_range(-1, 1) - lp) * (0.03 + 0.5 * f)
		out[i] = lp * sin(PI * f) * 1.4
	return out


func _make_tone(freq: float, dur: float) -> PackedFloat32Array:
	var n := int(RATE * dur)
	var out := PackedFloat32Array()
	out.resize(n)
	for i in n:
		var t := float(i) / RATE
		var s := sin(TAU * freq * t) + 0.3 * sin(TAU * freq * 2.0 * t)
		out[i] = s * 0.45 * minf(1.0, t * 200.0) * exp(-t * 3.0)
	return out


func _make_blips(semitones: Array, step: float, base: float) -> PackedFloat32Array:
	var n := int(RATE * (step * semitones.size() + 0.25))
	var out := PackedFloat32Array()
	out.resize(n)
	for j in semitones.size():
		var freq := base * pow(2.0, semitones[j] / 12.0)
		var start := int(j * step * RATE)
		for i in int(0.25 * RATE):
			if start + i >= n:
				break
			var t := float(i) / RATE
			var sq := 1.0 if sin(TAU * freq * t) > 0.0 else -1.0
			out[start + i] += sq * 0.18 * exp(-t * 12.0)
	return out


func _make_boom() -> PackedFloat32Array:
	var n := int(RATE * 0.7)
	var out := PackedFloat32Array()
	out.resize(n)
	var phase := 0.0
	var lp := 0.0
	for i in n:
		var t := float(i) / RATE
		phase += TAU * lerpf(120.0, 35.0, t / 0.7) / RATE
		lp += (_rng.randf_range(-1, 1) - lp) * 0.08
		out[i] = (sin(phase) * 0.9 + lp * 0.8) * exp(-t * 5.0)
	return out


func _make_splat() -> PackedFloat32Array:
	var n := int(RATE * 0.4)
	var out := PackedFloat32Array()
	out.resize(n)
	var phase := 0.0
	var lp := 0.0
	for i in n:
		var t := float(i) / RATE
		phase += TAU * lerpf(220.0, 70.0, t / 0.4) / RATE
		lp += (_rng.randf_range(-1, 1) - lp) * 0.2
		out[i] = (sin(phase) * 0.6 + lp * 0.7) * exp(-t * 9.0)
	return out


func _make_shimmer() -> PackedFloat32Array:
	var n := int(RATE * 0.7)
	var out := PackedFloat32Array()
	out.resize(n)
	for i in n:
		var t := float(i) / RATE
		var s := sin(TAU * 1200.0 * t) + sin(TAU * 1500.0 * t) + sin(TAU * 1800.0 * t)
		out[i] = s * 0.12 * (0.6 + 0.4 * sin(TAU * 18.0 * t)) * exp(-t * 4.0)
	return out


# ----------------------------------------------------------------- música

## Compone una pista original de rock arcade (bajo, batería y arpegios)
## en un hilo aparte para no trabar el arranque.
func _build_music() -> void:
	var bpm := 132.0
	var beat := 60.0 / bpm
	var bars := 8
	var n := int(bars * 4 * beat * RATE)
	var buf := PackedFloat32Array()
	buf.resize(n)
	var rng := RandomNumberGenerator.new()
	rng.seed = 2026

	# Progresión en La menor: Am F C G Am F G E (raíz MIDI y si es menor).
	var chords := [[57, true], [53, false], [48, false], [55, false],
		[57, true], [53, false], [55, false], [52, false]]
	var pattern := [0, 1, 2, 1, 2, 3, 2, 1]
	for bar in bars:
		var root: int = chords[bar][0]
		var minor: bool = chords[bar][1]
		var tones := [0, 3 if minor else 4, 7, 12]
		var t0 := bar * 4 * beat
		# Bajo en corcheas.
		for e in 8:
			var note: int = root - 12 + (12 if e % 4 == 3 else 0)
			_note(buf, t0 + e * beat / 2.0, beat / 2.0 * 0.9, _midi(note), "bajo", 0.32)
		# Batería: bombo, tarola y platillo.
		for b in 4:
			var tb := t0 + b * beat
			if b % 2 == 0:
				_drum(buf, tb, "bombo")
			else:
				_drum(buf, tb, "tarola")
			_drum(buf, tb, "hat")
			_drum(buf, tb + beat / 2.0, "hat")
		if bar % 2 == 1:
			_drum(buf, t0 + 3.5 * beat, "bombo")
		# Arpegio en semicorcheas con pequeñas variaciones.
		for s in 16:
			var idx: int = pattern[s % 8]
			if rng.randf() < 0.15:
				idx = rng.randi_range(0, 3)
			var note: int = root + 12 + tones[idx]
			_note(buf, t0 + s * beat / 4.0, beat / 4.0 * 0.8, _midi(note), "lead", 0.09)

	var peak := 0.001
	for v in buf:
		peak = maxf(peak, absf(v))
	var gain := 0.85 / peak
	for i in n:
		buf[i] *= gain
	call_deferred("_music_ready", _to_bytes(buf))


func _music_ready(data: PackedByteArray) -> void:
	var w := AudioStreamWAV.new()
	w.format = AudioStreamWAV.FORMAT_16_BITS
	w.mix_rate = RATE
	w.data = data
	w.loop_mode = AudioStreamWAV.LOOP_FORWARD
	w.loop_end = data.size() / 2
	streams.musica = w
	music_player.stream = w
	music_player.play()


func _midi(note: int) -> float:
	return 440.0 * pow(2.0, (note - 69) / 12.0)


func _note(buf: PackedFloat32Array, start: float, dur: float, freq: float, kind: String, vol: float) -> void:
	var i0 := int(start * RATE)
	var count := int(dur * RATE)
	var phase := 0.0
	for i in count:
		if i0 + i >= buf.size():
			return
		var t := float(i) / RATE
		phase = fmod(phase + freq / RATE, 1.0)
		var s := 0.0
		if kind == "bajo":
			s = (phase * 2.0 - 1.0) * 0.7 + sin(TAU * phase) * 0.5
		else:
			s = 1.0 if phase < 0.5 else -1.0
		var env := minf(1.0, t * 300.0) * minf(1.0, (dur - t) * 60.0) * exp(-t * 2.5)
		buf[i0 + i] += s * vol * env


func _drum(buf: PackedFloat32Array, start: float, kind: String) -> void:
	var i0 := int(start * RATE)
	var dur := 0.18 if kind == "bombo" else (0.14 if kind == "tarola" else 0.035)
	var count := int(dur * RATE)
	var phase := 0.0
	var lp := 0.0
	for i in count:
		if i0 + i >= buf.size():
			return
		var t := float(i) / RATE
		var s := 0.0
		match kind:
			"bombo":
				phase += TAU * lerpf(150.0, 45.0, t / dur) / RATE
				s = sin(phase) * exp(-t * 18.0) * 0.9
			"tarola":
				lp += (_rng.randf_range(-1, 1) - lp) * 0.6
				s = lp * exp(-t * 22.0) * 0.45 + sin(TAU * 190.0 * t) * exp(-t * 30.0) * 0.2
			_:
				var nz := _rng.randf_range(-1, 1)
				s = (nz - lp) * exp(-t * 80.0) * 0.12
				lp = nz
		buf[i0 + i] += s


# ------------------------------------------------------------------ ayudas

func _wav(samples: PackedFloat32Array, loop := false) -> AudioStreamWAV:
	var w := AudioStreamWAV.new()
	w.format = AudioStreamWAV.FORMAT_16_BITS
	w.mix_rate = RATE
	w.data = _to_bytes(samples)
	if loop:
		w.loop_mode = AudioStreamWAV.LOOP_FORWARD
		w.loop_end = samples.size()
	return w


func _to_bytes(samples: PackedFloat32Array) -> PackedByteArray:
	var data := PackedByteArray()
	data.resize(samples.size() * 2)
	for i in samples.size():
		data.encode_s16(i * 2, int(clampf(samples[i], -1.0, 1.0) * 32767.0))
	return data
