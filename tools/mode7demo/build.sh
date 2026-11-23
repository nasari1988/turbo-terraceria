#!/usr/bin/env bash
# Compila la demo: necesita arm-none-eabi-gcc (apt install gcc-arm-none-eabi)
set -euo pipefail
cd "$(dirname "$0")"
python3 gen_assets.py
arm-none-eabi-gcc -mcpu=arm7tdmi -mthumb -mthumb-interwork -O2 -ffreestanding -nostdlib \
  -Wall -T gba.ld crt0.s main.c -lgcc -o mode7demo.elf
arm-none-eabi-objcopy -O binary mode7demo.elf mode7demo.gba
python3 - <<'EOF'
# Checksum de cabecera (como gbafix)
d = bytearray(open("mode7demo.gba", "rb").read())
c = 0
for b in d[0xA0:0xBD]:
    c -= b
d[0xBD] = (c - 0x19) & 0xFF
open("mode7demo.gba", "wb").write(d)
print("mode7demo.gba", len(d), "bytes")
EOF
