#!/usr/bin/env bash
# Compila las demos: necesita arm-none-eabi-gcc (apt install gcc-arm-none-eabi)
#   mode7demo.gba:  un solo modo de video en todo el cuadro
#   mode7mixto.gba: cambia de modo a mitad de pantalla (como Mario Kart): cielo y barra en modo 0, suelo en modo 1
set -euo pipefail
cd "$(dirname "$0")"
python3 gen_assets.py
for name in mode7demo mode7mixto; do
  flags=""
  if [ "$name" = mode7mixto ]; then flags="-DMIXTO"; fi
  arm-none-eabi-gcc $flags -mcpu=arm7tdmi -mthumb -mthumb-interwork -O2 -ffreestanding -nostdlib \
    -Wall -T gba.ld crt0.s main.c -lgcc -o "$name.elf"
  arm-none-eabi-objcopy -O binary "$name.elf" "$name.gba"
  # Checksum de cabecera (como gbafix)
  python3 -c '
import sys
p = sys.argv[1]
d = bytearray(open(p, "rb").read())
c = 0
for b in d[0xA0:0xBD]:
    c -= b
d[0xBD] = (c - 0x19) & 0xFF
open(p, "wb").write(d)
print(p, len(d), "bytes")
' "$name.gba"
done
