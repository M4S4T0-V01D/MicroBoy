package dev.m4s4t0.microboy.emulation

import java.nio.ByteBuffer

/**
 * Thin JNI surface over the mGBA core (see app/src/main/cpp/jni_bridge.c).
 *
 * All methods except [nativeCopyFrame], [nativeSetMotion] and [nativeSetSun]
 * must be called from the emulation thread.
 */
object NativeCore {
    const val WIDTH = 240
    const val HEIGHT = 160
    const val FRAME_BYTES = WIDTH * HEIGHT * 4

    // Cartridge hardware flags (mGBA's GBAHardwareDevice).
    const val HW_RTC = 1
    const val HW_RUMBLE = 2
    const val HW_LIGHT_SENSOR = 4
    const val HW_GYRO = 8
    const val HW_TILT = 16

    /** GBA refresh rate: 16.78 MHz / 280896 cycles per frame. */
    const val FRAME_RATE = 16_777_216.0 / 280_896.0

    init {
        System.loadLibrary("microboy")
    }

    @JvmStatic external fun nativeLoadRom(rom: ByteArray, savePath: String, biosPath: String?, skipBios: Boolean): Boolean
    @JvmStatic external fun nativeUnload()
    @JvmStatic external fun nativeReset()
    @JvmStatic external fun nativeGameTitle(): String
    @JvmStatic external fun nativeGameCode(): String

    @JvmStatic external fun nativeRunFrames(count: Int, keys: Int, audio: Boolean)
    @JvmStatic external fun nativeCopyFrame(dst: ByteBuffer, force: Boolean): Boolean

    @JvmStatic external fun nativeFlushSave()
    @JvmStatic external fun nativeImportSave(data: ByteArray): Boolean
    @JvmStatic external fun nativeSaveState(path: String): Boolean
    @JvmStatic external fun nativeLoadState(path: String): Boolean

    /** Cartridge extras as a bitmask of the HW_* flags below. */
    @JvmStatic external fun nativeCartHardware(): Int
    /** Safe from any thread. Gravity in m/s² along screen x/y; gyro in rad/s. */
    @JvmStatic external fun nativeSetMotion(tiltX: Float, tiltY: Float, gyroZ: Float)
    /** Safe from any thread. 0 = dark .. 255 = full sun. */
    @JvmStatic external fun nativeSetSun(level: Int)
    @JvmStatic external fun nativePollRumble(): Boolean

    // Online link (see jni_bridge.c). Both phones run both consoles.
    @JvmStatic external fun nativeLinkSnapshot(): ByteArray?
    @JvmStatic external fun nativeLinkBegin(
        localRom: ByteArray, localState: ByteArray, peerRom: ByteArray, peerState: ByteArray,
        localPlayer: Int, epochMs: Long, savePath: String,
    ): Boolean
    @JvmStatic external fun nativeLinkRunTick(keysP1: Int, keysP2: Int, audio: Boolean)
    @JvmStatic external fun nativeLinkChecksum(): Int
    @JvmStatic external fun nativeLinkEnd()
    @JvmStatic external fun nativeLinkActive(): Boolean

    @JvmStatic external fun nativeSetRewind(enabled: Boolean, entries: Int, interval: Int)
    @JvmStatic external fun nativeRewindStep(): Boolean
    @JvmStatic external fun nativeSetSpeed(speed: Float)

    @JvmStatic external fun nativeAudioOpen(): Boolean
    @JvmStatic external fun nativeAudioClose()
    @JvmStatic external fun nativeAudioPause()
    @JvmStatic external fun nativeAudioResume()
    @JvmStatic external fun nativeAudioFlush()
    @JvmStatic external fun nativeSetVolume(volume: Float)
    @JvmStatic external fun nativeAudioStats(out: IntArray)
}
