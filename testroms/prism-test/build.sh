#!/bin/sh
# Builds prism-test.gba with clang + lld (no devkitARM needed).
set -e
cd "$(dirname "$0")"
OBJCOPY=${OBJCOPY:-$(ls "$ANDROID_HOME"/ndk/*/toolchains/llvm/prebuilt/*/bin/llvm-objcopy 2>/dev/null | head -1)}
FLAGS="--target=armv4t-none-eabi -mcpu=arm7tdmi -ffreestanding -fno-builtin -nostdlib -O2"
clang $FLAGS -c crt0.s -o crt0.o
clang $FLAGS -mthumb -c main.c -o main.o
ld.lld -T link.ld crt0.o main.o -o prism-test.elf
"$OBJCOPY" -O binary prism-test.elf prism-test.gba
# Header complement check at 0xBD.
python3 - <<'PY'
d = bytearray(open("prism-test.gba", "rb").read())
d[0xBD] = (-(sum(d[0xA0:0xBD]) + 0x19)) & 0xFF
open("prism-test.gba", "wb").write(d)
PY
rm -f crt0.o main.o prism-test.elf
echo "built prism-test.gba ($(stat -c %s prism-test.gba) bytes)"
