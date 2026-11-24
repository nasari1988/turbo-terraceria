#!/usr/bin/env bash
# Descarga bsnes-hd (núcleo de SNES con Mode 7 en HD, GPLv3) en app/src/main/cpp/bsnes-hd.
# No se guarda en el repositorio; se fija a un commit para que la compilación sea repetible.
set -euo pipefail
commit="${1:-fc26b25}"
root="$(cd "$(dirname "$0")/.." && pwd)"
dest="$root/app/src/main/cpp/bsnes-hd"
if [ -f "$dest/bsnes/target-libretro/libretro.cpp" ]; then
  echo "bsnes-hd ya está en $dest"
  exit 0
fi
rm -rf "$dest"
git clone https://github.com/DerKoun/bsnes-hd "$dest"
git -C "$dest" checkout -q "$commit"
echo "bsnes-hd $commit listo"
