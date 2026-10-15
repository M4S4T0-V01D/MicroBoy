package dev.m4s4t0.microboy.emulation

import java.nio.ByteBuffer

/**
 * Thin JNI surface over the mGBA core (see app/src/main/cpp/jni_bridge.c).
 *
 * All methods except [nativeCopyFrame] must be called from the emulation thread.
 */
object NativeCore {
    const val WIDTH = 240
    const val HEIGHT = 160
    const val FRAME_BYTES = WIDTH * HEIGHT * 4

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

    @JvmStatic external fun nativeAudioOpen(): Boolean
    @JvmStatic external fun nativeAudioClose()
    @JvmStatic external fun nativeAudioPause()
    @JvmStatic external fun nativeAudioResume()
    @JvmStatic external fun nativeAudioFlush()
    @JvmStatic external fun nativeSetVolume(volume: Float)
    @JvmStatic external fun nativeAudioStats(out: IntArray)
}
