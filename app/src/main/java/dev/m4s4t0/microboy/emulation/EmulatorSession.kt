package dev.m4s4t0.microboy.emulation

import android.os.Process
import android.os.SystemClock
import android.util.Log
import dev.m4s4t0.microboy.link.LinkDriver
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Snapshot of runtime performance, refreshed twice a second. */
data class EmuStats(
    val fps: Float = 0f,
    val speed: Float = 0f,
    val frameTimeMs: Float = 0f,
    val audioRate: Int = 0,
    val audioQueued: Int = 0,
    val audioTarget: Int = 0,
    val underruns: Int = 0,
)

/**
 * Owns the emulation thread. Everything that touches the core runs on that
 * thread; other threads communicate through volatile fields and [post]/[call].
 *
 * Pacing: at normal speed one frame is emulated per GBA frame period
 * (≈16.74 ms) using a monotonic clock. Audio stays in sync through dynamic
 * rate control in the native layer.
 *
 * Fast-forward: N frames are genuinely emulated per period (or as many as fit
 * in the period for "max"); only the last one is displayed and the audio
 * produced while fast-forwarding is discarded to avoid distortion.
 *
 * Slow motion: one frame per stretched period, with the audio resampled to the
 * same speed (it plays slower and lower, like tape).
 *
 * Rewind: while held, each period steps one snapshot back. Snapshots are
 * recorded natively every [REWIND_INTERVAL] emulated frames.
 *
 * Online link: while [link] is set, frames are run by the link driver with
 * both players' buttons; fast-forward, slow motion and rewind are ignored
 * because both phones must run exactly the same frames.
 */
class EmulatorSession(
    val rom: ByteArray,
    private val savePath: String,
    private val biosPath: String?,
    private val skipBios: Boolean,
    private val onFrame: () -> Unit,
    private val onStarted: (Boolean) -> Unit,
) {
    private val touchKeys = AtomicInteger()
    private val padKeys = AtomicInteger()

    @Volatile var fastForward = false
    /** Frames per period while fast-forwarding; 0 = unthrottled. */
    @Volatile var fastForwardSpeed = 3
    @Volatile var slowMotion = false
    /** Game speed while slow motion is on, e.g. 0.5. */
    @Volatile var slowSpeed = 0.5f
    @Volatile var rewinding = false
    /** Seconds of rewind history; 0 turns recording off. */
    @Volatile var rewindSeconds = 0
        set(value) {
            if (field == value) return
            field = value
            post(::applyRewind)
        }
    /** Set while an online link is running; see [LinkDriver]. */
    @Volatile var link: LinkDriver? = null
    /** Holds emulation still (but keeps running commands) while a link is being set up. */
    @Volatile var linkHold = false
        set(value) {
            field = value
            wake()
        }

    /** Receives rumble motor changes on the emulation thread; null = ignore rumble. */
    @Volatile var onRumble: ((Boolean) -> Unit)? = null
    @Volatile var audioEnabled = true
    @Volatile var muted = false
    @Volatile var volume = 1f
    @Volatile var stats = EmuStats()
        private set

    private val lock = Object()
    private val commands = ConcurrentLinkedQueue<() -> Unit>()
    @Volatile private var running = true
    @Volatile private var userPaused = false
    @Volatile private var systemPaused = false
    private var thread: Thread? = null

    /** Wall-clock milliseconds spent actually running (not paused). */
    @Volatile var playedMs = 0L
        private set

    val isPaused: Boolean get() = userPaused || systemPaused || linkHold

    fun start() {
        thread = Thread(::run, "Emulation").also { it.start() }
    }

    fun setTouchKeys(mask: Int) = touchKeys.set(mask)
    fun setPadKeys(mask: Int) = padKeys.set(mask)

    fun setUserPaused(paused: Boolean) {
        userPaused = paused
        wake()
    }

    fun setSystemPaused(paused: Boolean) {
        systemPaused = paused
        wake()
    }

    /** Runs [block] on the emulation thread between frames (also while paused). */
    fun post(block: () -> Unit) {
        commands.add(block)
        wake()
    }

    suspend fun <T> call(block: () -> T): T = suspendCancellableCoroutine { cont ->
        post {
            try {
                cont.resume(block())
            } catch (e: Throwable) {
                cont.resumeWithException(e)
            }
        }
    }

    /** Stops the thread, flushing the cartridge save. Blocks up to [timeoutMs]. */
    fun stop(timeoutMs: Long = 2000) {
        running = false
        wake()
        thread?.join(timeoutMs)
        thread = null
    }

    private fun wake() {
        synchronized(lock) { lock.notifyAll() }
    }

    private fun drainCommands() {
        while (true) {
            val c = commands.poll() ?: return
            try {
                c()
            } catch (e: Throwable) {
                Log.e(TAG, "Command failed", e)
            }
        }
    }

    /** Called on the emulation thread after a link ends: restores solo-only features. */
    fun relinkFinished() {
        applyRewind()
        NativeCore.nativeSetSpeed(1f)
    }

    private fun applyRewind() {
        val entries = (rewindSeconds * NativeCore.FRAME_RATE / REWIND_INTERVAL).toInt()
        NativeCore.nativeSetRewind(entries > 0, entries, REWIND_INTERVAL)
    }

    private fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        val ok = NativeCore.nativeLoadRom(rom, savePath, biosPath, skipBios)
        onStarted(ok)
        if (!ok) {
            drainCommands()
            return
        }
        applyRewind()

        val period = (1_000_000_000.0 / NativeCore.FRAME_RATE).toLong()
        var next = System.nanoTime()
        var audioOpen = false
        var audioPaused = false
        var lastMode = Mode.NORMAL
        var rumbling = false
        var appliedVolume = -1f

        var statFrames = 0
        var statBusyNs = 0L
        var statStart = System.nanoTime()
        var lastTick = SystemClock.elapsedRealtime()
        val audioStats = IntArray(4)

        while (running) {
            drainCommands()
            if (!running) break

            if (isPaused) {
                if (rumbling) {
                    rumbling = false
                    onRumble?.invoke(false)
                }
                if (audioOpen && !audioPaused) {
                    NativeCore.nativeAudioPause()
                    audioPaused = true
                }
                NativeCore.nativeFlushSave()
                synchronized(lock) {
                    while (running && isPaused && commands.isEmpty()) lock.wait()
                }
                next = System.nanoTime()
                lastTick = SystemClock.elapsedRealtime()
                statStart = next
                statFrames = 0
                statBusyNs = 0
                continue
            }

            // Audio device lifecycle follows the "audio enabled" setting so a
            // disabled audio path costs no battery at all.
            if (audioEnabled && !audioOpen) {
                audioOpen = NativeCore.nativeAudioOpen()
                audioPaused = false
            } else if (!audioEnabled && audioOpen) {
                NativeCore.nativeAudioClose()
                audioOpen = false
            }
            if (audioOpen && audioPaused) {
                NativeCore.nativeAudioResume()
                audioPaused = false
            }
            val vol = if (muted) 0f else volume
            if (vol != appliedVolume) {
                NativeCore.nativeSetVolume(vol)
                appliedVolume = vol
            }

            val keys = touchKeys.get() or padKeys.get()
            val linkDriver = link
            val mode = when {
                linkDriver != null -> Mode.LINK
                rewinding && rewindSeconds > 0 -> Mode.REWIND
                fastForward -> Mode.FAST
                slowMotion -> Mode.SLOW
                else -> Mode.NORMAL
            }
            val slow = slowSpeed
            if (mode != lastMode) {
                NativeCore.nativeSetSpeed(if (mode == Mode.SLOW) slow else 1f)
                NativeCore.nativeAudioFlush()
                lastMode = mode
            }

            val t0 = System.nanoTime()
            var frames: Int
            if (mode == Mode.LINK && linkDriver != null) {
                frames = linkDriver.step(keys, audioOpen && !muted)
            } else if (mode == Mode.REWIND) {
                // When the history runs out the picture simply holds.
                NativeCore.nativeRewindStep()
                frames = 1
            } else if (mode != Mode.FAST) {
                NativeCore.nativeRunFrames(1, keys, audioOpen && !muted)
                frames = 1
            } else {
                val speed = fastForwardSpeed
                if (speed > 0) {
                    NativeCore.nativeRunFrames(speed, keys, false)
                    frames = speed
                } else {
                    // Unthrottled: emulate in small batches until this display period is used up.
                    frames = 0
                    val deadline = t0 + period - 1_000_000
                    do {
                        NativeCore.nativeRunFrames(2, keys, false)
                        frames += 2
                    } while (System.nanoTime() < deadline && running && commands.isEmpty())
                }
            }
            val t1 = System.nanoTime()
            onFrame()

            val rumbleTo = onRumble
            val r = rumbleTo != null && NativeCore.nativePollRumble()
            if (r != rumbling) {
                rumbling = r
                rumbleTo?.invoke(r)
            }

            statFrames += frames
            statBusyNs += t1 - t0
            val nowMs = SystemClock.elapsedRealtime()
            playedMs += nowMs - lastTick
            lastTick = nowMs
            if (t1 - statStart >= 500_000_000L) {
                val secs = (t1 - statStart) / 1e9f
                val fps = statFrames / secs
                NativeCore.nativeAudioStats(audioStats)
                stats = EmuStats(
                    fps = fps,
                    speed = fps / NativeCore.FRAME_RATE.toFloat(),
                    frameTimeMs = statBusyNs / 1e6f / statFrames.coerceAtLeast(1),
                    audioRate = audioStats[0],
                    audioQueued = audioStats[1],
                    audioTarget = audioStats[2],
                    underruns = audioStats[3],
                )
                statStart = t1
                statFrames = 0
                statBusyNs = 0
            }

            // Pace to real time (unthrottled fast-forward already consumed its period).
            next += if (mode == Mode.SLOW) (period / slow).toLong() else period
            val now = System.nanoTime()
            if (now - next > period * 4) {
                next = now // fell far behind (e.g. debugger, heavy load): don't burst to catch up
            } else if (next > now) {
                LockSupport.parkNanos(next - now)
            }
        }

        drainCommands()
        if (rumbling) onRumble?.invoke(false)
        NativeCore.nativeFlushSave()
        if (audioOpen) NativeCore.nativeAudioClose()
        NativeCore.nativeUnload()
    }

    private enum class Mode { NORMAL, FAST, SLOW, REWIND, LINK }

    companion object {
        private const val TAG = "EmulatorSession"
        /** Emulated frames between rewind snapshots (~30 per second). */
        const val REWIND_INTERVAL = 2
    }
}
