#!/usr/bin/env bash
# Descarga el código de mGBA en app/src/main/cpp/mgba (no se guarda en el repositorio)
# y le aplica los parches de patches/ (p. ej. la máscara de capas afines del modo híbrido).
set -euo pipefail
version="${1:-0.10.5}"
root="$(cd "$(dirname "$0")/.." && pwd)"
dest="$root/app/src/main/cpp/mgba"
if [ -f "$dest/CMakeLists.txt" ]; then
  echo "mGBA ya está en $dest"
else
  rm -rf "$dest"
  git clone --depth 1 --branch "$version" https://github.com/mgba-emu/mgba "$dest"
fi
for p in "$root"/patches/*.patch; do
  [ -e "$p" ] || continue
  if git -C "$dest" apply --check "$p" 2>/dev/null; then
    git -C "$dest" apply "$p"
    echo "parche aplicado: $(basename "$p")"
  else
    echo "parche ya aplicado o no aplica: $(basename "$p")"
  fi
done
