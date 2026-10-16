#!/bin/sh
# Builds microboy-jukebox.gba with clang + lld (no devkitARM needed) and copies it
# to docs/ for the website download.
set -e
cd "$(dirname "$0")"
OBJCOPY=${OBJCOPY:-$(ls "$ANDROID_HOME"/ndk/*/toolchains/llvm/prebuilt/*/bin/llvm-objcopy 2>/dev/null | head -1)}
FLAGS="-Wall --target=armv4t-none-eabi -mcpu=arm7tdmi -ffreestanding -fno-builtin -nostdlib -O2"
python3 gen.py
clang --target=armv4t-none-eabi -mcpu=arm7tdmi -c crt0.s -o crt0.o
clang $FLAGS -mthumb -c main.c -o main.o
ld.lld -T link.ld crt0.o main.o -o microboy-jukebox.elf
"$OBJCOPY" -O binary microboy-jukebox.elf microboy-jukebox.gba
# Header complement check at 0xBD.
python3 - <<'PY'
d = bytearray(open("microboy-jukebox.gba", "rb").read())
d[0xBD] = (-(sum(d[0xA0:0xBD]) + 0x19)) & 0xFF
open("microboy-jukebox.gba", "wb").write(d)
PY
rm -f crt0.o main.o microboy-jukebox.elf
cp microboy-jukebox.gba ../../docs/microboy-jukebox.gba
echo "built microboy-jukebox.gba ($(stat -c %s microboy-jukebox.gba) bytes)"
