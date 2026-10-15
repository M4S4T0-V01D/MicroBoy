package dev.m4s4t0.microboy.input

import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import dev.m4s4t0.microboy.emulation.GbaKey

/**
 * Translates physical controller events into a GBA key mask plus emulator
 * actions (fast-forward, menu, quick save/load) using a [GamepadMapping].
 */
class GamepadInput(
    private val onKeys: (Int) -> Unit,
    private val onFastForward: (Boolean) -> Unit,
    private val onAction: (PadAction) -> Unit,
) {
    var mapping: GamepadMapping = GamepadMapping.default()

    private var buttonMask = 0
    private var axisMask = 0
    private var ffFromButton = false
    private var ffFromTrigger = false

    /** Returns true if the event was consumed. */
    fun onKey(event: KeyEvent): Boolean {
        if (!isGameController(event.device) && !KeyEvent.isGamepadButton(event.keyCode) && !isDpadKey(event.keyCode)) {
            return false
        }
        val action = mapping.actionFor(event.keyCode) ?: return false
        val down = event.action == KeyEvent.ACTION_DOWN
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return true
        val key = action.gbaKey
        when {
            key != null -> {
                buttonMask = if (down) buttonMask or key.mask else buttonMask and key.mask.inv()
                publish()
            }
            action == PadAction.FAST_FORWARD -> {
                ffFromButton = down
                onFastForward(ffFromButton || ffFromTrigger)
            }
            down && event.repeatCount == 0 -> onAction(action)
        }
        return true
    }

    /** Analog sticks, hats and analog triggers. */
    fun onMotion(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK ||
            event.action != MotionEvent.ACTION_MOVE
        ) return false

        val x = pick(event, MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_X)
        val y = pick(event, MotionEvent.AXIS_HAT_Y, MotionEvent.AXIS_Y)
        var m = 0
        if (x < -THRESHOLD) m = m or GbaKey.LEFT.mask
        if (x > THRESHOLD) m = m or GbaKey.RIGHT.mask
        if (y < -THRESHOLD) m = m or GbaKey.UP.mask
        if (y > THRESHOLD) m = m or GbaKey.DOWN.mask
        if (m != axisMask) {
            axisMask = m
            publish()
        }

        // Analog right trigger acts as hold-to-fast-forward when R2 is bound to it.
        if (mapping.actionFor(KeyEvent.KEYCODE_BUTTON_R2) == PadAction.FAST_FORWARD) {
            val rt = maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS))
            val pressed = rt > 0.5f
            if (pressed != ffFromTrigger) {
                ffFromTrigger = pressed
                onFastForward(ffFromButton || ffFromTrigger)
            }
        }
        return true
    }

    fun reset() {
        buttonMask = 0
        axisMask = 0
        ffFromButton = false
        ffFromTrigger = false
        publish()
    }

    private fun publish() = onKeys(buttonMask or axisMask)

    private fun pick(e: MotionEvent, hat: Int, stick: Int): Float {
        val h = e.getAxisValue(hat)
        if (h != 0f) return h
        val dev = e.device ?: return 0f
        val range = dev.getMotionRange(stick, e.source) ?: return 0f
        val v = e.getAxisValue(stick)
        return if (kotlin.math.abs(v) > range.flat) v else 0f
    }

    companion object {
        private const val THRESHOLD = 0.5f

        private fun isDpadKey(code: Int) = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN ||
            code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT

        fun isGameController(device: InputDevice?): Boolean {
            if (device == null || device.isVirtual) return false
            val s = device.sources
            return s and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                s and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
        }

        fun anyControllerConnected(im: InputManager): Boolean =
            im.inputDeviceIds.any { isGameController(im.getInputDevice(it)) }
    }
}
