#!/bin/sh
# Builds microboy-demo.gba with clang + lld (no devkitARM needed) and copies it
# to docs/ for the website download.
set -e
cd "$(dirname "$0")"
OBJCOPY=${OBJCOPY:-$(ls "$ANDROID_HOME"/ndk/*/toolchains/llvm/prebuilt/*/bin/llvm-objcopy 2>/dev/null | head -1)}
FLAGS="-Wall --target=armv4t-none-eabi -mcpu=arm7tdmi -ffreestanding -fno-builtin -nostdlib -O2"
python3 gen.py
clang --target=armv4t-none-eabi -mcpu=arm7tdmi -c crt0.s -o crt0.o
clang $FLAGS -mthumb -c main.c -o main.o
ld.lld -T link.ld crt0.o main.o -o microboy-demo.elf
"$OBJCOPY" -O binary microboy-demo.elf microboy-demo.gba
# Header complement check at 0xBD.
python3 - <<'PY'
d = bytearray(open("microboy-demo.gba", "rb").read())
d[0xBD] = (-(sum(d[0xA0:0xBD]) + 0x19)) & 0xFF
open("microboy-demo.gba", "wb").write(d)
PY
rm -f crt0.o main.o microboy-demo.elf
cp microboy-demo.gba ../../docs/microboy-demo.gba
echo "built microboy-demo.gba ($(stat -c %s microboy-demo.gba) bytes)"
