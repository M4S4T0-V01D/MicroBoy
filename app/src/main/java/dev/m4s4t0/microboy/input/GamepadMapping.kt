package dev.m4s4t0.microboy.input

import android.view.KeyEvent
import dev.m4s4t0.microboy.emulation.GbaKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Things a physical controller button can be bound to. */
enum class PadAction(val label: String, val gbaKey: GbaKey? = null) {
    A("A", GbaKey.A),
    B("B", GbaKey.B),
    L("L", GbaKey.L),
    R("R", GbaKey.R),
    START("Start", GbaKey.START),
    SELECT("Select", GbaKey.SELECT),
    UP("Up", GbaKey.UP),
    DOWN("Down", GbaKey.DOWN),
    LEFT("Left", GbaKey.LEFT),
    RIGHT("Right", GbaKey.RIGHT),
    FAST_FORWARD("Fast-forward"),
    MENU("Open menu"),
    QUICK_SAVE("Quick save"),
    QUICK_LOAD("Quick load"),
}

/** Android keycode → action. Several keycodes may map to the same action. */
@Serializable
data class GamepadMapping(val bindings: Map<Int, PadAction>) {
    fun actionFor(keyCode: Int): PadAction? = bindings[keyCode]

    fun keysFor(action: PadAction): List<Int> = bindings.filterValues { it == action }.keys.sorted()

    /** Binds [keyCode] to [action], replacing whatever the key was bound to before. */
    fun bind(action: PadAction, keyCode: Int) = GamepadMapping(bindings + (keyCode to action))

    fun clear(action: PadAction) = GamepadMapping(bindings.filterValues { it != action })

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun decode(s: String): GamepadMapping? = runCatching { json.decodeFromString(serializer(), s) }.getOrNull()

        fun default() = GamepadMapping(
            mapOf(
                KeyEvent.KEYCODE_BUTTON_A to PadAction.A,
                KeyEvent.KEYCODE_BUTTON_B to PadAction.B,
                KeyEvent.KEYCODE_BUTTON_X to PadAction.B,
                KeyEvent.KEYCODE_BUTTON_Y to PadAction.A,
                KeyEvent.KEYCODE_BUTTON_L1 to PadAction.L,
                KeyEvent.KEYCODE_BUTTON_R1 to PadAction.R,
                KeyEvent.KEYCODE_BUTTON_START to PadAction.START,
                KeyEvent.KEYCODE_BUTTON_SELECT to PadAction.SELECT,
                KeyEvent.KEYCODE_DPAD_UP to PadAction.UP,
                KeyEvent.KEYCODE_DPAD_DOWN to PadAction.DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT to PadAction.LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT to PadAction.RIGHT,
                KeyEvent.KEYCODE_BUTTON_R2 to PadAction.FAST_FORWARD,
                KeyEvent.KEYCODE_BUTTON_L2 to PadAction.MENU,
                KeyEvent.KEYCODE_BUTTON_MODE to PadAction.MENU,
                KeyEvent.KEYCODE_BUTTON_THUMBL to PadAction.QUICK_SAVE,
                KeyEvent.KEYCODE_BUTTON_THUMBR to PadAction.QUICK_LOAD,
            )
        )

        fun keyName(keyCode: Int): String =
            KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_").replace("BUTTON_", "").replace('_', ' ')
    }
}
