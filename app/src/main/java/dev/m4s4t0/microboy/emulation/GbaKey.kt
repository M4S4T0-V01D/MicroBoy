package dev.m4s4t0.microboy.emulation

/** GBA KEYINPUT bit positions, as expected by mCore.setKeys. */
enum class GbaKey(val bit: Int, val label: String) {
    A(0, "A"),
    B(1, "B"),
    SELECT(2, "Select"),
    START(3, "Start"),
    RIGHT(4, "Right"),
    LEFT(5, "Left"),
    UP(6, "Up"),
    DOWN(7, "Down"),
    R(8, "R"),
    L(9, "L");

    val mask: Int get() = 1 shl bit
}
