#!/bin/sh
# Builds tools/linktest against the host libmgba that romshot.sh builds.
#   tools/linktest.sh BUILD_DIR      → BUILD_DIR/linktest
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT=${1:?usage: linktest.sh BUILD_DIR}
[ -f "$OUT/mgba/libmgba.a" ] || "$ROOT/tools/romshot.sh" "$OUT"
cc -O2 -o "$OUT/linktest" "$ROOT/tools/linktest.c" "$ROOT/app/src/main/cpp/link.c" -I"$ROOT/app/src/main/cpp" \
    -I"$ROOT/third_party/mgba/include" -I"$ROOT/third_party/mgba/src" -I"$OUT/mgba/include" "$OUT/mgba/libmgba.a" -lm -lpthread
echo "built $OUT/linktest"
