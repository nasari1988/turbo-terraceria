#!/usr/bin/env bash
# Descarga el código de mGBA en app/src/main/cpp/mgba (no se guarda en el repositorio).
set -euo pipefail
version="${1:-0.10.5}"
dest="$(dirname "$0")/../app/src/main/cpp/mgba"
if [ -f "$dest/CMakeLists.txt" ]; then
  echo "mGBA ya está en $dest"
  exit 0
fi
rm -rf "$dest"
git clone --depth 1 --branch "$version" https://github.com/mgba-emu/mgba "$dest"
