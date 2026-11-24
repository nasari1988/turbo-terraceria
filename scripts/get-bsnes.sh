#!/usr/bin/env bash
# Descarga bsnes-hd (núcleo de SNES con Mode 7 en HD, GPLv3) en app/src/main/cpp/bsnes-hd.
# No se guarda en el repositorio; se fija a un commit para que la compilación sea repetible.
set -euo pipefail
commit="${1:-fc26b25}"
root="$(cd "$(dirname "$0")/.." && pwd)"
dest="$root/app/src/main/cpp/bsnes-hd"
if [ -f "$dest/bsnes/target-libretro/libretro.cpp" ]; then
  echo "bsnes-hd ya está en $dest"
else
  rm -rf "$dest"
  git clone https://github.com/DerKoun/bsnes-hd "$dest"
  git -C "$dest" checkout -q "$commit"
  echo "bsnes-hd $commit listo"
fi
# Parches propios (p. ej. HD solo en cuadros con Mode 7)
for p in "$root"/patches/bsnes/*.patch; do
  [ -e "$p" ] || continue
  if git -C "$dest" apply --check "$p" 2>/dev/null; then
    git -C "$dest" apply "$p"
    echo "parche aplicado: $(basename "$p")"
  else
    echo "parche ya aplicado o no aplica: $(basename "$p")"
  fi
done
