package dev.m4s4t0.microboy.link

import android.os.SystemClock
import android.util.Log
import dev.m4s4t0.microboy.data.Game
import dev.m4s4t0.microboy.emulation.EmulatorSession
import dev.m4s4t0.microboy.emulation.NativeCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.math.ceil

/** How the emulation thread drives a linked session; see [NetplaySession.step]. */
interface LinkDriver {
    /** Runs the linked frames that are ready. Returns how many ran (0 = waiting for the other player). */
    fun step(localKeys: Int, audio: Boolean): Int
}

/**
 * One online link session between two matched players.
 *
 * Both phones run both consoles. Setup: exchange hellos and check each side has
 * the other's game (by SHA-1, never sending ROMs), measure the ping, agree on an
 * input delay and a clock, then swap compressed save states and start both
 * consoles from them. After that each frame only the players' buttons travel,
 * [inputDelay] frames ahead, and both phones compute identical frames.
 */
class NetplaySession(
    private val scope: CoroutineScope,
    private val emu: EmulatorSession,
    val player: Int,
    val peerNick: String,
    private val myNick: String,
    private val localGame: Game,
    private val localRom: ByteArray,
    private val savePath: String,
    private val findGame: (sha1: String) -> Game?,
    private val readRom: suspend (Game) -> ByteArray?,
    room: String,
) : LinkDriver, PeerConnection.Listener {

    sealed interface Status {
        data object Connecting : Status
        data object Checking : Status
        data class Syncing(val percent: Int) : Status
        data class Linked(val delay: Int, val direct: Boolean, val peerPaused: Boolean, val peerGame: String) : Status
        data class Ended(val reason: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Connecting)
    val status: StateFlow<Status> = _status.asStateFlow()
    private val _messages = MutableStateFlow<String?>(null)
    /** Latest quick message from the other player. */
    val messages: StateFlow<String?> = _messages.asStateFlow()

    private val conn = PeerConnection(room, player, this)

    // Handshake
    private var peerGameTitle = ""
    private var peerRom: ByteArray? = null
    private var localReady = false
    private var peerReady = false
    private var started = false
    private var epoch = 0L
    @Volatile var inputDelay = 3
        private set
    private val pings = mutableListOf<Long>()

    // State exchange
    private var localState: ByteArray? = null
    private var peerStateBuf: ByteArray? = null
    private var peerStateGot = 0

    // Inputs, indexed by tick % RING. Ticks are stored alongside to reject stale entries.
    private val localKeys = IntArray(RING)
    private val peerKeys = IntArray(RING)
    private val peerTick = IntArray(RING) { -1 }
    private val inputLock = Any()
    @Volatile private var peerLatest = -1
    private var nextTick = 0
    private var submittedThrough = -1
    private val myCrc = HashMap<Int, Int>()
    private val peerCrc = HashMap<Int, Int>()
    private var waitingSince = 0L
    @Volatile private var peerPaused = false
    @Volatile private var ended = false

    fun start() {
        _status.value = Status.Connecting
        conn.start()
    }

    /** Ends the session; this console carries on alone from where the link left it. */
    fun end(reason: String, notifyPeer: Boolean = true) {
        if (ended) return
        ended = true
        Log.i(TAG, "Link ended: $reason")
        if (notifyPeer) conn.sendText(buildJsonObject { put("t", "bye") })
        conn.close()
        emu.post {
            if (emu.link === this) {
                emu.link = null
                NativeCore.nativeLinkEnd()
                emu.relinkFinished()
            }
            emu.linkHold = false
        }
        _status.value = Status.Ended(reason)
    }

    private var localPaused = false

    fun setLocalPaused(paused: Boolean) {
        if (paused == localPaused || _status.value !is Status.Linked) return
        localPaused = paused
        conn.sendText(buildJsonObject { put("t", if (paused) "pause" else "resume") })
    }

    fun say(index: Int) {
        conn.sendText(buildJsonObject { put("t", "say"); put("i", index) })
    }

    // --- connection callbacks (network threads) ---------------------------------------------

    private var helloSent = false
    @Volatile private var peerGoneAt = 0L

    override fun onPeerJoined() {
        peerGoneAt = 0L
        // After a reconnect the room announces the peer again; the handshake only runs once.
        if (helloSent) return
        helloSent = true
        _status.value = Status.Checking
        conn.sendText(buildJsonObject {
            put("t", "hello")
            put("nick", myNick)
            put("link", LinkConfig.LINK_VERSION)
            put("rom", localGame.id)
            put("title", localGame.displayTitle)
        })
    }

    override fun onPeerLeft() {
        // Usually a dropped connection that comes back; give them a moment.
        val at = SystemClock.elapsedRealtime()
        peerGoneAt = at
        scope.launch {
            delay(PEER_GRACE_MS)
            if (peerGoneAt == at) end("$peerNick left", notifyPeer = false)
        }
    }

    override fun onFailed(reason: String) {
        end(reason, notifyPeer = false)
    }

    override fun onDirect(direct: Boolean) {
        val s = _status.value
        if (s is Status.Linked) _status.value = s.copy(direct = direct)
    }

    override fun onText(msg: JsonObject) {
        peerGoneAt = 0L  // anything from the peer means they're (back) online
        when (msg.str("t")) {
            "hello" -> onHello(msg)
            "missing" -> end("$peerNick doesn't have ${localGame.displayTitle}", notifyPeer = false)
            "ready" -> {
                peerReady = true
                maybeStart()
            }
            "ping" -> conn.sendText(buildJsonObject { put("t", "pong"); put("at", msg["at"]?.jsonPrimitive?.longOrNull ?: 0L) })
            "pong" -> onPong(msg)
            "start" -> onStart(
                msg["delay"]?.jsonPrimitive?.intOrNull ?: 3,
                msg["epoch"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis(),
            )
            "pause" -> setPeerPaused(true)
            "resume" -> setPeerPaused(false)
            "say" -> {
                val i = msg["i"]?.jsonPrimitive?.intOrNull ?: return
                LinkConfig.QUICK_MESSAGES.getOrNull(i)?.let { _messages.value = "$peerNick: $it" }
            }
            "bye" -> end("$peerNick ended the link", notifyPeer = false)
            "need" -> resendFrom(msg["from"]?.jsonPrimitive?.intOrNull ?: return)
        }
    }

    override fun onBinary(data: ByteArray) {
        if (data.isEmpty()) return
        peerGoneAt = 0L
        val bb = ByteBuffer.wrap(data)
        when (bb.get().toInt()) {
            MSG_STATE -> onStateChunk(bb)
            MSG_INPUT -> onInputs(bb)
            MSG_CRC -> onCrc(bb.int, bb.int)
        }
    }

    // --- handshake ------------------------------------------------------------------------

    private fun onHello(msg: JsonObject) {
        if (peerRom != null || localReady) return
        if (msg.str("link") != LinkConfig.LINK_VERSION) {
            return end("$peerNick has a different MicroBoy version. Both players need the same version.")
        }
        peerGameTitle = msg.str("title") ?: "their game"
        val sha1 = msg.str("rom") ?: return
        val game = findGame(sha1)
        if (game == null) {
            conn.sendText(buildJsonObject { put("t", "missing") })
            return end("To link with $peerNick you need $peerGameTitle in your library")
        }
        scope.launch(Dispatchers.IO) {
            val bytes = if (game.id == localGame.id) localRom else readRom(game)
            if (bytes == null) {
                conn.sendText(buildJsonObject { put("t", "missing") })
                end("Couldn't open your copy of $peerGameTitle")
                return@launch
            }
            peerRom = bytes
            localReady = true
            conn.sendText(buildJsonObject { put("t", "ready") })
            maybeStart()
        }
    }

    /** Player 1 measures the ping, then picks the input delay and the shared clock. */
    @Synchronized
    private fun maybeStart() {
        if (!localReady || !peerReady || started || player != 1) return
        started = true
        scope.launch {
            repeat(5) {
                conn.sendText(buildJsonObject { put("t", "ping"); put("at", SystemClock.elapsedRealtime()) })
                delay(120)
            }
            delay(400)
            val rtt = synchronized(pings) { pings.sorted().getOrNull(pings.size / 2) } ?: 150L
            // Enough frames to cover the one-way trip plus some jitter.
            val d = ceil((rtt / 2.0 + 20.0) / FRAME_MS).toInt().coerceIn(2, 12)
            val e = System.currentTimeMillis()
            conn.sendText(buildJsonObject { put("t", "start"); put("delay", d); put("epoch", e) })
            onStart(d, e)
        }
    }

    private fun onPong(msg: JsonObject) {
        val at = msg["at"]?.jsonPrimitive?.longOrNull ?: return
        synchronized(pings) { pings += SystemClock.elapsedRealtime() - at }
    }

    private fun onStart(d: Int, e: Long) {
        if (localState != null) return
        inputDelay = d
        epoch = e
        _status.value = Status.Syncing(0)
        // Freeze this console, snapshot it and send the snapshot.
        emu.linkHold = true
        scope.launch {
            val snap = emu.call { NativeCore.nativeLinkSnapshot() }
            if (snap == null) return@launch end("Couldn't snapshot your game")
            localState = snap
            val packed = deflate(snap)
            var off = 0
            while (off < packed.size) {
                val n = minOf(CHUNK, packed.size - off)
                val buf = ByteBuffer.allocate(9 + n).put(MSG_STATE.toByte()).putInt(packed.size).putInt(off).put(packed, off, n)
                conn.sendReliable(buf.array())
                off += n
            }
            maybeBegin()
        }
    }

    private fun onStateChunk(bb: ByteBuffer) {
        val total = bb.int
        val off = bb.int
        if (total <= 0 || total > MAX_STATE || off < 0) return
        val buf = synchronized(this) {
            peerStateBuf ?: ByteArray(total).also { peerStateBuf = it }
        }
        val n = bb.remaining()
        if (off + n > buf.size) return
        bb.get(buf, off, n)
        peerStateGot += n
        _status.value = Status.Syncing((peerStateGot * 100L / total).toInt())
        if (peerStateGot >= total) maybeBegin()
    }

    @Synchronized
    private fun maybeBegin() {
        val mine = localState ?: return
        val packed = peerStateBuf ?: return
        if (peerStateGot < packed.size || emu.link != null || ended) return
        val theirs = runCatching { inflate(packed) }.getOrNull() ?: return end("The other player's save state was damaged")
        val rom = peerRom ?: return
        // Both phones start every console with inputs 0 for the first `inputDelay` ticks.
        synchronized(inputLock) {
            for (t in 0 until inputDelay) {
                localKeys[t] = 0
                peerKeys[t] = 0
                peerTick[t] = t
            }
            peerLatest = maxOf(peerLatest, inputDelay - 1)
        }
        submittedThrough = inputDelay - 1
        emu.post {
            if (ended) return@post
            val ok = NativeCore.nativeLinkBegin(localRom, mine, rom, theirs, player - 1, epoch, savePath)
            if (ok) {
                emu.link = this
                _status.value = Status.Linked(inputDelay, conn.isDirect, peerPaused, peerGameTitle)
            } else {
                end("Couldn't start the linked games")
            }
            emu.linkHold = false
        }
    }

    // --- running (emulation thread) ---------------------------------------------------------

    override fun step(localKeys: Int, audio: Boolean): Int {
        var ran = 0
        while (ran < 3) {
            val t = nextTick
            // Our buttons for this frame are used `inputDelay` frames from now.
            val target = t + inputDelay
            if (submittedThrough < target) {
                this.localKeys[target % RING] = localKeys
                submittedThrough = target
                sendInputs(target)
            }
            val i = t % RING
            val theirs = synchronized(inputLock) { if (peerTick[i] == t) peerKeys[i] else -1 }
            if (theirs < 0) break
            val mine = this.localKeys[i]
            if (player == 1) NativeCore.nativeLinkRunTick(mine, theirs, audio && ran == 0)
            else NativeCore.nativeLinkRunTick(theirs, mine, audio && ran == 0)
            nextTick++
            ran++
            if (nextTick % CRC_EVERY == 0) {
                val crc = NativeCore.nativeLinkChecksum()
                synchronized(myCrc) { myCrc[nextTick] = crc }
                conn.sendFast(ByteBuffer.allocate(9).put(MSG_CRC.toByte()).putInt(nextTick).putInt(crc).array())
                compareCrc(nextTick)
            }
            // Only run extra frames to catch up when we've fallen behind the other player.
            if (peerLatest - nextTick <= inputDelay) break
        }
        if (ran > 0) {
            waitingSince = 0
        } else if (!peerPaused) {
            val now = SystemClock.elapsedRealtime()
            if (waitingSince == 0L) waitingSince = now
            else if (now - waitingSince > TIMEOUT_MS) end("Lost the connection to $peerNick")
            // Inputs went missing (dropped connection, lost packets): ask again.
            if (now - waitingSince > 500 && now - lastNeed > 500) {
                lastNeed = now
                conn.sendText(buildJsonObject { put("t", "need"); put("from", nextTick) })
            }
        }
        return ran
    }

    private var lastNeed = 0L

    /** Re-sends our inputs from [from] onwards, for a peer that missed some. */
    private fun resendFrom(from: Int) {
        val last = submittedThrough
        var t = maxOf(from, last - RING / 2)
        while (t <= last) {
            val end = minOf(last, t + 63)
            val count = end - t + 1
            val bb = ByteBuffer.allocate(6 + count * 2).put(MSG_INPUT.toByte()).putInt(end).put(count.toByte())
            for (k in t..end) bb.putShort(localKeys[k % RING].toShort())
            conn.sendReliable(bb.array())
            t = end + 1
        }
    }

    /** Sends our latest inputs plus a few earlier ones, so a lost packet costs nothing. */
    private fun sendInputs(latest: Int) {
        val count = minOf(REDUNDANCY, latest + 1)
        val bb = ByteBuffer.allocate(6 + count * 2).put(MSG_INPUT.toByte()).putInt(latest).put(count.toByte())
        for (k in count - 1 downTo 0) bb.putShort(localKeys[(latest - k) % RING].toShort())
        conn.sendFast(bb.array())
    }

    private fun onInputs(bb: ByteBuffer) {
        val latest = bb.int
        val count = bb.get().toInt() and 0xFF
        if (bb.remaining() < count * 2) return
        synchronized(inputLock) {
            for (k in count - 1 downTo 0) {
                val t = latest - k
                val keys = bb.short.toInt() and 0x3FF
                if (t < 0) continue
                val i = t % RING
                if (peerTick[i] != t) {
                    peerKeys[i] = keys
                    peerTick[i] = t
                }
            }
            if (latest > peerLatest) peerLatest = latest
        }
    }

    private fun onCrc(tick: Int, crc: Int) {
        synchronized(myCrc) { peerCrc[tick] = crc }
        compareCrc(tick)
    }

    private fun compareCrc(tick: Int) {
        val (a, b) = synchronized(myCrc) {
            val pair = myCrc[tick] to peerCrc[tick]
            // Forget old checks.
            myCrc.keys.removeAll { it < tick - CRC_EVERY * 4 }
            peerCrc.keys.removeAll { it < tick - CRC_EVERY * 4 }
            pair
        }
        if (a != null && b != null && a != b) {
            Log.w(TAG, "Desync at tick $tick: $a vs $b")
            end("The linked games fell out of sync. You keep your own game.")
        }
    }

    private fun setPeerPaused(paused: Boolean) {
        peerPaused = paused
        waitingSince = 0
        val s = _status.value
        if (s is Status.Linked) _status.value = s.copy(peerPaused = paused)
    }

    private fun deflate(data: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_SPEED)
        d.setInput(data)
        d.finish()
        val out = ByteArrayOutputStream(data.size / 3)
        val buf = ByteArray(1 shl 16)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    private fun inflate(data: ByteArray): ByteArray {
        val inf = Inflater()
        inf.setInput(data)
        val out = ByteArrayOutputStream(data.size * 3)
        val buf = ByteArray(1 shl 16)
        while (!inf.finished()) {
            val n = inf.inflate(buf)
            if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
            out.write(buf, 0, n)
            if (out.size() > MAX_STATE * 4) break
        }
        inf.end()
        return out.toByteArray()
    }

    companion object {
        private const val TAG = "NetplaySession"
        private const val RING = 1024
        private const val REDUNDANCY = 12
        private const val CRC_EVERY = 180
        private const val CHUNK = 48 * 1024
        private const val MAX_STATE = 4 * 1024 * 1024
        private const val TIMEOUT_MS = 20_000L
        private const val PEER_GRACE_MS = 15_000L
        private const val FRAME_MS = 1000.0 / 59.7275
        private const val MSG_STATE = 1
        private const val MSG_INPUT = 2
        private const val MSG_CRC = 3
    }
}
