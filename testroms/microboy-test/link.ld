ENTRY(_start)
MEMORY {
    ROM (rx)  : ORIGIN = 0x08000000, LENGTH = 32M
    IWRAM (rw): ORIGIN = 0x03000000, LENGTH = 32K
}
SECTIONS {
    .text : { KEEP(*(.text.start)) *(.text*) *(.rodata*) } > ROM
    .bss (NOLOAD) : { . = ALIGN(4); __bss_start = .; *(.bss*) *(COMMON) . = ALIGN(4); __bss_end = .; } > IWRAM
    /DISCARD/ : { *(.ARM.exidx*) *(.ARM.attributes) *(.comment) }
}
