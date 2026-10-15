#!/bin/sh
# Builds tools/romshot against a host build of the mGBA submodule.
#   tools/romshot.sh BUILD_DIR      → BUILD_DIR/romshot
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT=${1:?usage: romshot.sh BUILD_DIR}
mkdir -p "$OUT/mgba"
cd "$OUT/mgba"
[ -f libmgba.a ] || {
    cmake "$ROOT/third_party/mgba" -DCMAKE_POLICY_VERSION_MINIMUM=3.5 -DCMAKE_BUILD_TYPE=Release \
        -DLIBMGBA_ONLY=ON -DBUILD_STATIC=ON -DBUILD_SHARED=OFF -DM_CORE_GB=OFF -DUSE_DEBUGGERS=OFF \
        -DUSE_GDB_STUB=OFF -DUSE_LUA=OFF -DUSE_ELF=OFF -DUSE_LZMA=OFF -DUSE_MINIZIP=OFF -DUSE_LIBZIP=OFF \
        -DUSE_EPOXY=OFF -DBUILD_GL=OFF -DBUILD_GLES2=OFF -DBUILD_GLES3=OFF -DUSE_PNG=OFF -DUSE_ZLIB=OFF \
        -DUSE_SQLITE3=OFF -DUSE_FFMPEG=OFF -DUSE_DISCORD_RPC=OFF >/dev/null
    nice -n 10 make -j4 >/dev/null
}
cc -O2 -o "$OUT/romshot" "$ROOT/tools/romshot.c" -I"$ROOT/third_party/mgba/include" -I"$OUT/mgba/include" \
    "$OUT/mgba/libmgba.a" -lm -lpthread
echo "built $OUT/romshot"
