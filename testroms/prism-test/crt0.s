@ Minimal GBA cartridge header + startup. No Nintendo logo is included; the
@ emulator skips the BIOS logo check when booting without the BIOS intro.
    .section .text.start, "ax"
    .arm
    .global _start
_start:
    b       rom_start
    .fill   156, 1, 0           @ 0x04-0x9F logo area (left blank)
    .ascii  "PRISMTEST\0\0\0"   @ 0xA0 title
    .ascii  "PRSM"              @ 0xAC game code
    .ascii  "00"                @ 0xB0 maker code
    .byte   0x96                @ 0xB2 fixed value
    .byte   0, 0                @ 0xB3 unit code, 0xB4 device type
    .fill   7, 1, 0             @ 0xB5-0xBB reserved
    .byte   0                   @ 0xBC version
    .byte   0                   @ 0xBD header checksum (patched by build.sh)
    .fill   2, 1, 0             @ 0xBE-0xBF reserved
rom_start:
    mov     r0, #0x12           @ IRQ mode stack
    msr     cpsr_c, r0
    ldr     sp, =0x03007FA0
    mov     r0, #0x1F           @ system mode stack
    msr     cpsr_c, r0
    ldr     sp, =0x03007F00
    ldr     r0, =__bss_start
    ldr     r1, =__bss_end
    mov     r2, #0
1:  cmp     r0, r1
    strlt   r2, [r0], #4
    blt     1b
    ldr     r0, =main
    mov     lr, pc
    bx      r0
2:  b       2b
    .pool
