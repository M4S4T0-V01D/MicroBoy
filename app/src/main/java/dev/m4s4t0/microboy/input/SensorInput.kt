package dev.m4s4t0.microboy.input

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import dev.m4s4t0.microboy.emulation.NativeCore
import kotlin.math.cbrt

/**
 * Feeds the phone's sensors to cartridges with extra hardware: the
 * accelerometer to tilt carts (Yoshi Topsy-Turvy, Koro Koro Puzzle), the
 * gyroscope to WarioWare: Twisted, and the light sensor to Boktai's solar
 * sensor. Listeners are only registered for hardware the game actually has.
 */
class SensorInput(context: Context, private val displayRotation: () -> Int) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val light = manager.getDefaultSensor(Sensor.TYPE_LIGHT)

    val hasAccelerometer get() = accelerometer != null
    val hasGyroscope get() = gyroscope != null
    val hasLightSensor get() = light != null

    private var hardware = 0
    private var running = false

    /** -1 = use the light sensor; 0..10 = fixed sun level. */
    var sunSetting = -1
        set(value) {
            if (field == value) return
            field = value
            if (value >= 0) NativeCore.nativeSetSun(fixedSun(value))
            if (running) restart()
        }

    private var tiltX = 0f
    private var tiltY = 0f
    private var gyroZ = 0f
    // Gravity reading treated as "level"; set by recenter().
    private var centerX = 0f
    private var centerY = 0f
    private var centerPending = true

    fun start(cartHardware: Int) {
        hardware = cartHardware
        restart()
    }

    fun stop() {
        manager.unregisterListener(this)
        running = false
        NativeCore.nativeSetMotion(0f, 0f, 0f)
    }

    /** Makes the phone's current angle the game's level position. */
    fun recenter() {
        centerPending = true
    }

    private fun restart() {
        manager.unregisterListener(this)
        running = false
        val h = hardware
        if (h and NativeCore.HW_TILT != 0) accelerometer?.let { register(it) }
        if (h and NativeCore.HW_GYRO != 0) gyroscope?.let { register(it) }
        if (h and NativeCore.HW_LIGHT_SENSOR != 0) {
            if (sunSetting < 0 && light != null) register(light) else NativeCore.nativeSetSun(fixedSun(sunSetting.coerceAtLeast(0)))
        }
    }

    private fun register(sensor: Sensor) {
        manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        running = true
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                // Sensor axes follow the device's natural orientation; turn them
                // into screen axes for the current rotation.
                val (x, y) = when (displayRotation()) {
                    Surface.ROTATION_90 -> -e.values[1] to e.values[0]
                    Surface.ROTATION_180 -> -e.values[0] to -e.values[1]
                    Surface.ROTATION_270 -> e.values[1] to -e.values[0]
                    else -> e.values[0] to e.values[1]
                }
                if (centerPending) {
                    centerX = x
                    centerY = y
                    centerPending = false
                }
                tiltX = x - centerX
                tiltY = y - centerY
                NativeCore.nativeSetMotion(tiltX, tiltY, gyroZ)
            }
            Sensor.TYPE_GYROSCOPE -> {
                gyroZ = e.values[2]
                NativeCore.nativeSetMotion(tiltX, tiltY, gyroZ)
            }
            Sensor.TYPE_LIGHT -> {
                // Same curve as mGBA's libretro port: indoor light ≈ 50, direct sun saturates.
                NativeCore.nativeSetSun((cbrt(e.values[0]) * 8).toInt())
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        // mGBA's GBA_LUX_LEVELS, the steps of Boktai's in-game sun gauge.
        private val LUX_LEVELS = intArrayOf(5, 11, 18, 27, 42, 62, 84, 109, 139, 183)

        fun fixedSun(level: Int): Int = 0x16 + if (level > 0) LUX_LEVELS[(level - 1).coerceAtMost(9)] else 0
    }
}
