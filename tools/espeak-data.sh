#!/usr/bin/env bash
# Builds eSpeak NG from third_party/espeak-ng for this computer, compiles its data, and copies the
# English files the app needs into espeak/src/main/assets/espeak-ng-data. Also builds the host JNI
# library used by the JVM tests (espeak/build/host/libespeakjni.so).
#   tools/espeak-data.sh            build both
#   tools/espeak-data.sh --check    fail if the committed data differs from a fresh build (still builds the library)
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
src=$root/third_party/espeak-ng
work=$root/espeak/build/espeak-ng-host
assets=$root/espeak/src/main/assets/espeak-ng-data

cmake -S "$src" -B "$work" -G Ninja -DCMAKE_BUILD_TYPE=Release -DUSE_ASYNC=OFF -DUSE_MBROLA=OFF \
  -DUSE_LIBSONIC=OFF -DUSE_LIBPCAUDIO=OFF -DUSE_KLATT=OFF -DUSE_SPEECHPLAYER=OFF -DEXTRA_cmn=OFF \
  -DEXTRA_ru=OFF -DEXTRA_yue=OFF -DBUILD_SHARED_LIBS=OFF >/dev/null
ninja -C "$work" data >/dev/null

fresh=$root/espeak/build/espeak-ng-data-english
rm -rf "$fresh" && mkdir -p "$fresh/lang/gmw"
for file in phontab phonindex phondata intonations en_dict; do cp "$work/espeak-ng-data/$file" "$fresh/"; done
cp "$work"/espeak-ng-data/lang/gmw/en* "$fresh/lang/gmw/"

if [ "${1:-}" = "--check" ]; then
  diff -r "$fresh" "$assets" || { echo "The committed eSpeak data is out of date; run tools/espeak-data.sh."; exit 1; }
  echo "eSpeak data is up to date."
else
  rm -rf "$assets" && mkdir -p "$(dirname "$assets")" && cp -r "$fresh" "$assets"
  echo "Copied English eSpeak data to $assets"
fi

cmake -S "$root/espeak/src/main/cpp" -B "$root/espeak/build/host-cmake" -G Ninja -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_LIBRARY_OUTPUT_DIRECTORY="$root/espeak/build/host" >/dev/null
ninja -C "$root/espeak/build/host-cmake" >/dev/null
echo "Built $root/espeak/build/host/libespeakjni.so"
