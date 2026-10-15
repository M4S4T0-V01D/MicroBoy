package dev.m4s4t0.microboy.input

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Every on-screen control. Base sizes are in dp before scaling. */
enum class ControlId(val baseWidthDp: Float, val baseHeightDp: Float, val label: String) {
    DPAD(150f, 150f, "D-Pad"),
    A(66f, 66f, "A"),
    B(66f, 66f, "B"),
    L(92f, 42f, "L"),
    R(92f, 42f, "R"),
    SELECT(64f, 28f, "SELECT"),
    START(64f, 28f, "START"),
    FAST_FORWARD(56f, 30f, "FF"),
    MENU(42f, 42f, "MENU"),
}

/**
 * Position (normalized to the controller view, 0..1) and scale of one control.
 */
@Serializable
data class ControlPlacement(val x: Float, val y: Float, val scale: Float = 1f, val visible: Boolean = true)

/** A complete touch layout for one orientation. */
@Serializable
data class ControlLayout(val placements: Map<ControlId, ControlPlacement>) {
    operator fun get(id: ControlId): ControlPlacement = placements[id] ?: ControlPlacement(0.5f, 0.5f)

    fun with(id: ControlId, p: ControlPlacement) = ControlLayout(placements + (id to p))

    /** Fills in controls missing from an older saved layout. */
    fun withDefaults(defaults: ControlLayout) = ControlLayout(defaults.placements + placements)

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun decode(s: String): ControlLayout? = runCatching { json.decodeFromString(serializer(), s) }.getOrNull()

        /** Portrait: game at the top, controls in the bottom half. */
        fun defaultPortrait() = ControlLayout(
            mapOf(
                ControlId.L to ControlPlacement(0.14f, 0.50f),
                ControlId.R to ControlPlacement(0.86f, 0.50f),
                ControlId.DPAD to ControlPlacement(0.25f, 0.68f),
                ControlId.A to ControlPlacement(0.86f, 0.64f),
                ControlId.B to ControlPlacement(0.68f, 0.72f),
                ControlId.SELECT to ControlPlacement(0.38f, 0.87f),
                ControlId.START to ControlPlacement(0.62f, 0.87f),
                ControlId.FAST_FORWARD to ControlPlacement(0.86f, 0.87f),
                ControlId.MENU to ControlPlacement(0.12f, 0.87f),
            )
        )

        /** Landscape: game centered, controls in the side gutters. */
        fun defaultLandscape() = ControlLayout(
            mapOf(
                ControlId.L to ControlPlacement(0.08f, 0.10f),
                ControlId.R to ControlPlacement(0.92f, 0.10f),
                ControlId.MENU to ControlPlacement(0.05f, 0.30f),
                ControlId.FAST_FORWARD to ControlPlacement(0.93f, 0.30f),
                ControlId.DPAD to ControlPlacement(0.11f, 0.62f),
                ControlId.A to ControlPlacement(0.93f, 0.55f),
                ControlId.B to ControlPlacement(0.84f, 0.72f),
                ControlId.SELECT to ControlPlacement(0.07f, 0.94f),
                ControlId.START to ControlPlacement(0.93f, 0.94f),
            )
        )
    }
}
