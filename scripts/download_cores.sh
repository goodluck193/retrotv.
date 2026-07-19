#!/usr/bin/env bash
# Скачивает ядра libretro с официального buildbot и кладёт их в jniLibs
# под нужными именами. Запускается автоматически в GitHub Actions.
set -euo pipefail

BASE="https://buildbot.libretro.com/nightly/android/latest"

download_core () {
  local abi="$1" src="$2" dest="$3"
  local dir="app/src/main/jniLibs/$abi"
  mkdir -p "$dir"
  echo ">> $abi / $src -> $dest"
  curl -fL --retry 3 -o "/tmp/${abi}_${src}.zip" "$BASE/$abi/${src}.zip"
  local tmp="/tmp/unz_${abi}_${src}"
  rm -rf "$tmp" && mkdir -p "$tmp"
  unzip -oq "/tmp/${abi}_${src}.zip" -d "$tmp"
  mv "$tmp/$src" "$dir/$dest"
}

for ABI in arm64-v8a armeabi-v7a; do
  download_core "$ABI" "fceumm_libretro_android.so"           "libfceumm.so"
  download_core "$ABI" "snes9x_libretro_android.so"           "libsnes9x.so"
  download_core "$ABI" "genesis_plus_gx_libretro_android.so"  "libgenesis.so"
done

echo "Ядра на месте:"
find app/src/main/jniLibs -name '*.so'
