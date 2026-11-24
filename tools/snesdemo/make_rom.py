#!/usr/bin/env python3
"""Demo propia de SNES en Mode 7 (CC0): suelo en perspectiva con cielo y un kart (sprite).
Genera snesdemo.sfc sin ensamblador: el código 65816 va escrito a mano en bytes.
Uso: python3 make_rom.py [salida.sfc]"""
import struct, sys

ROM = bytearray(0x8000)          # 32 KB LoROM, banco $00 en $8000–$FFFF
BASE = 0x8000

# ---------- datos ----------
def bgr(r, g, b):
    return (r >> 3) | ((g >> 3) << 5) | ((b >> 3) << 10)

# Paleta: 0 cielo (fondo), 1–8 suelo, 128+ sprite
pal = [0] * 256
pal[0] = bgr(110, 170, 255)
pal[1] = bgr(70, 150, 60)    # pasto claro
pal[2] = bgr(50, 120, 45)    # pasto oscuro
pal[3] = bgr(120, 120, 130)  # asfalto
pal[4] = bgr(235, 235, 235)  # línea blanca
pal[5] = bgr(200, 40, 40)    # borde rojo
pal[6] = bgr(95, 95, 105)    # asfalto oscuro
pal[128] = 0                 # transparente
pal[129] = bgr(30, 30, 40)   # llantas
pal[130] = bgr(40, 110, 220) # carrocería azul
pal[131] = bgr(250, 210, 60) # amarillo
pal[132] = bgr(240, 240, 240)
palette = b''.join(struct.pack('<H', c) for c in pal)

# Mosaicos de Mode 7: 8bpp, 64 bytes cada uno
def tile(f):
    return bytes(f(x, y) for y in range(8) for x in range(8))
tiles = [
    tile(lambda x, y: 1 if (x + y) % 7 else 2),          # 0 pasto claro con textura
    tile(lambda x, y: 2 if (x * 3 + y) % 9 else 1),      # 1 pasto oscuro
    tile(lambda x, y: 3 if (x + 2 * y) % 5 else 6),      # 2 asfalto
    tile(lambda x, y: 4 if 2 <= x <= 5 else 3),          # 3 línea blanca
    tile(lambda x, y: 5 if (y // 2) % 2 else 4),         # 4 borde rojo/blanco
]
chars = b''.join(tiles)

# Mapa 128x128: cuadros de pasto, una pista vertical con bordes y línea central discontinua
m = bytearray(128 * 128)
for ty in range(128):
    for tx in range(128):
        t = 0 if ((tx // 4) + (ty // 4)) % 2 else 1
        rx = tx % 64
        if 24 <= rx <= 39:
            t = 2
            if rx in (24, 39): t = 4
            if rx == 31 and (ty // 3) % 2 == 0: t = 3
        m[ty * 128 + tx] = t
tilemap = bytes(m)

# Sprite 16x16 de 4bpp (kart visto desde atrás), mosaicos 0,1,16,17 → bloque de 18 mosaicos
kart = [
    "................",
    "......3333......",
    ".....322223.....",
    "....32222223....",
    "....22222222....",
    "...2222222222...",
    "...2244444422...",
    "...2222222222...",
    ".112222222222211",
    ".112222222222211",
    ".112233333322211",
    ".111222222222111",
    ".111.........111",
    ".111.........111",
    "................",
    "................",
]
def tile4(px):  # px[y][x] 0–15 → 32 bytes en formato planar de SNES
    out = bytearray(32)
    for y in range(8):
        for x in range(8):
            v = px[y][x]
            for p in range(4):
                if v >> p & 1:
                    off = y * 2 + (p & 1) + (p >> 1) * 16
                    out[off] |= 0x80 >> x
    return bytes(out)
def sub(ox, oy):
    return [[0 if kart[oy + y][ox + x] == '.' else int(kart[oy + y][ox + x]) for x in range(8)] for y in range(8)]
objtiles = bytearray(18 * 32)
objtiles[0:32] = tile4(sub(0, 0)); objtiles[32:64] = tile4(sub(8, 0))
objtiles[16 * 32:17 * 32] = tile4(sub(0, 8)); objtiles[17 * 32:18 * 32] = tile4(sub(8, 8))

# OAM: sprite 0 = kart (16x16, prioridad 3), los demás fuera de pantalla
oam = bytearray()
oam += bytes([120, 184, 0, 0x30])
for _ in range(127):
    oam += bytes([0, 240, 0, 0])
high = bytearray(32)
high[0] = 0x02          # sprite 0 grande (16x16)
oam += high

# HDMA: cielo en las primeras líneas (TM=0), suelo en perspectiva abajo
HORIZON = 64
def rep_table(values, width):
    out = bytearray()
    i = 0
    while i < len(values):
        n = min(127, len(values) - i)
        out.append(0x80 | n)
        for v in values[i:i + n]:
            out += struct.pack('<H', v & 0xFFFF) if width == 2 else bytes([v & 0xFF])
        i += n
    out.append(0)
    return bytes(out)
m7a, m7y, tm = [], [], []
for y in range(224):
    if y < HORIZON:
        m7a.append(0x0100); m7y.append(0); tm.append(0x00)
    else:
        d = y - HORIZON + 2
        m7a.append(min(0x7FFF, int(0x40 * 160 / d)))
        m7y.append(int(1000 - 6400 / d) % 1024)
        tm.append(0x11)
hdma_a, hdma_y, hdma_tm = rep_table(m7a, 2), rep_table(m7y, 2), rep_table(tm, 1)

# ---------- código 65816 ----------
code = bytearray()
labels, fix = {}, []
def emit(*b): code.extend(b)
def addr(off): return BASE + off
data_off = {}

def sta(a): emit(0x8D, a & 0xFF, a >> 8)
def stz(a): emit(0x9C, a & 0xFF, a >> 8)
def lda_i(v): emit(0xA9, v & 0xFF)
def dma(ch_src_key, dest, size, mode=0):
    # DMA canal 0: A→B, fuente creciente
    lda_i(mode); sta(0x4300)
    lda_i(dest); sta(0x4301)
    fix.append((len(code) + 1, ch_src_key)); emit(0xA2, 0, 0)       # LDX #fuente (16 bits)
    emit(0x8E, 0x02, 0x43)                                          # STX $4302
    stz(0x4304)                                                     # banco 0
    emit(0xA2, size & 0xFF, size >> 8); emit(0x8E, 0x05, 0x43)       # tamaño
    lda_i(1); sta(0x420B)
def hdma(ch, mode, reg, key):
    b = 0x4300 + ch * 16
    lda_i(mode); sta(b)
    lda_i(reg); sta(b + 1)
    fix.append((len(code) + 1, key)); emit(0xA2, 0, 0); emit(0x8E, (b + 2) & 0xFF, (b + 2) >> 8)
    stz(b + 4)

emit(0x78, 0x18, 0xFB)            # SEI; CLC; XCE (modo nativo)
emit(0xC2, 0x18)                  # REP #$18: X/Y de 16 bits, sin decimal
emit(0xE2, 0x20)                  # SEP #$20: A de 8 bits
emit(0xA2, 0xFF, 0x1F, 0x9A)      # LDX #$1FFF; TXS
lda_i(0x80); sta(0x2100)          # pantalla apagada
stz(0x4200)
lda_i(0x07); sta(0x2105)          # Mode 7
stz(0x211A)                       # M7SEL: repetir el mapa
for r in (0x211C, 0x211D, 0x211E): stz(r); stz(r)   # M7B, M7C, M7D = 0
stz(0x210E); stz(0x210E)          # VOFS = 0
# mapa (byte bajo de VRAM) y mosaicos (byte alto)
stz(0x2115); stz(0x2116); stz(0x2117)
dma('map', 0x18, len(tilemap))
lda_i(0x80); sta(0x2115); stz(0x2116); stz(0x2117)
dma('chars', 0x19, len(chars))
# mosaicos del sprite en $4000 (palabras)
lda_i(0x80); sta(0x2115); stz(0x2116); lda_i(0x40); sta(0x2117)
dma('obj', 0x18, len(objtiles), mode=1)
lda_i(0x02); sta(0x2101)          # OBSEL: base $4000, tamaños 8/16
stz(0x2121); dma('pal', 0x22, len(palette))
stz(0x2102); stz(0x2103); dma('oam', 0x04, len(oam))
# HDMA: canal 1 → M7A, canal 2 → M7Y, canal 3 → TM
hdma(1, 0x02, 0x1B, 'hdma_a')
hdma(2, 0x02, 0x20, 'hdma_y')
hdma(3, 0x00, 0x2C, 'hdma_tm')
lda_i(0x0E); sta(0x420C)
emit(0x64, 0x00, 0x64, 0x01)      # STZ $00; STZ $01 (contador)
lda_i(0x0F); sta(0x2100)          # pantalla encendida
labels['loop'] = len(code)
emit(0xAD, 0x12, 0x42, 0x10, 0xFB)  # espera VBlank: LDA $4212; BPL -5
emit(0xC2, 0x20)                  # REP #$20 (A de 16 bits)
emit(0xE6, 0x00)                  # INC $00
emit(0xA5, 0x00, 0x18, 0x69, 0x80, 0x00, 0x85, 0x02)  # LDA $00; CLC; ADC #$0080; STA $02
emit(0xE2, 0x20)                  # SEP #$20
emit(0xA5, 0x00); sta(0x210D); emit(0xA5, 0x01); sta(0x210D)   # HOFS = contador
emit(0xA5, 0x02); sta(0x211F); emit(0xA5, 0x03); sta(0x211F)   # M7X = contador + 128
emit(0xAD, 0x12, 0x42, 0x30, 0xFB)  # espera fin de VBlank: LDA $4212; BMI -5
rel = labels['loop'] - (len(code) + 2)
emit(0x80, rel & 0xFF)            # BRA loop
labels['rti'] = len(code); emit(0x40)

blobs = [('map', tilemap), ('chars', chars), ('obj', bytes(objtiles)), ('pal', palette), ('oam', bytes(oam)),
         ('hdma_a', hdma_a), ('hdma_y', hdma_y), ('hdma_tm', hdma_tm)]
ROM[0:len(code)] = code
off = (len(code) + 0xFF) & ~0xFF
for k, b in blobs:
    data_off[k] = off
    ROM[off:off + len(b)] = b
    off += len(b)
assert off < 0x7FC0, hex(off)
for pos, k in fix:
    struct.pack_into('<H', ROM, pos, addr(data_off[k]))

# cabecera LoROM
title = b'GBA IA MODE7 DEMO'.ljust(21)
ROM[0x7FC0:0x7FD5] = title
ROM[0x7FD5] = 0x20; ROM[0x7FD6] = 0x00; ROM[0x7FD7] = 0x05; ROM[0x7FD8] = 0x00
ROM[0x7FD9] = 0x01; ROM[0x7FDA] = 0x00; ROM[0x7FDB] = 0x00
rti = addr(labels['rti'])
for v in range(0x7FE4, 0x7FF0, 2): struct.pack_into('<H', ROM, v, rti)   # vectores nativos
for v in range(0x7FF4, 0x7FFE, 2): struct.pack_into('<H', ROM, v, rti)   # vectores de emulación
struct.pack_into('<H', ROM, 0x7FFC, BASE)                                 # RESET
struct.pack_into('<HH', ROM, 0x7FDC, 0xFFFF, 0)
s = sum(ROM) & 0xFFFF
struct.pack_into('<HH', ROM, 0x7FDC, s ^ 0xFFFF, s)
out = sys.argv[1] if len(sys.argv) > 1 else 'snesdemo.sfc'
open(out, 'wb').write(ROM)
print(out, len(ROM), 'bytes; código', len(code), 'bytes; datos hasta', hex(off))
