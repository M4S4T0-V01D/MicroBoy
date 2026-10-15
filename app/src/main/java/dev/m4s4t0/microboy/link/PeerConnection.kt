package dev.m4s4t0.microboy.link

import android.os.SystemClock
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The connection between two matched players.
 *
 * Everything reliable (handshake, save states, control messages) goes through
 * the link server's room relay. Button presses go peer to peer over UDP when
 * the two phones can reach each other directly (same Wi-Fi, or most home
 * routers via STUN hole punching), and through the relay otherwise.
 */
class PeerConnection(private val room: String, private val player: Int, private val listener: Listener) {
    interface Listener {
        fun onPeerJoined()
        fun onPeerLeft()
        fun onText(msg: JsonObject)
        fun onBinary(data: ByteArray)
        fun onDirect(direct: Boolean)
        fun onFailed(reason: String)
    }

    private val http = OkHttpClient.Builder().pingInterval(15, TimeUnit.SECONDS).build()
    @Volatile private var ws: WebSocket? = null
    @Volatile private var closed = false
    // Mobile connections drop; the relay link reconnects for a while before giving up.
    @Volatile private var droppedAt = 0L
    private var attempt = 0

    // UDP
    private var udp: DatagramSocket? = null
    private val key = room.take(8).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    @Volatile private var direct: InetSocketAddress? = null
    @Volatile private var lastUdpRx = 0L
    private val peerCandidates = mutableListOf<InetSocketAddress>()

    val isDirect: Boolean get() = direct != null

    fun start() {
        openRelay()
        thread(name = "LinkUdp", isDaemon = true) { runCatching { udpSetup() }.onFailure { Log.i(TAG, "No direct path: ${it.message}") } }
    }

    fun close() {
        closed = true
        ws?.close(1000, "bye")
        udp?.close()
    }

    /** Reliable, ordered: through the relay. */
    fun sendText(obj: JsonObject) {
        ws?.send(obj.toString())
    }

    fun sendReliable(data: ByteArray) {
        ws?.send(data.toByteString())
    }

    /** Low latency: direct UDP when available, otherwise the relay. */
    fun sendFast(data: ByteArray) {
        val d = direct
        val socket = udp
        if (d != null && socket != null) {
            val packet = ByteArray(7 + data.size)
            header(packet, KIND_DATA)
            data.copyInto(packet, 7)
            runCatching { socket.send(DatagramPacket(packet, packet.size, d)) }
            // Until the peer has heard from us directly for a while, send both ways.
            if (SystemClock.elapsedRealtime() - lastUdpRx > 1000) sendReliable(data)
        } else {
            sendReliable(data)
        }
    }

    private fun openRelay() {
        val request = Request.Builder().url("${LinkConfig.SERVER}/room/$room?p=$player").build()
        ws = http.newWebSocket(request, wsListener)
    }

    private fun relayDropped(webSocket: WebSocket, why: String) {
        if (closed || webSocket !== ws) return
        Log.i(TAG, "Relay dropped: $why")
        val now = SystemClock.elapsedRealtime()
        if (droppedAt == 0L) droppedAt = now
        if (now - droppedAt > RECONNECT_FOR_MS) {
            listener.onFailed("Lost the connection to the link server")
            return
        }
        ws = null
        val wait = minOf(4000L, 250L shl minOf(attempt++, 4))
        thread(name = "LinkReconnect", isDaemon = true) {
            Thread.sleep(wait)
            if (!closed && ws == null) openRelay()
        }
    }

    private val wsListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (droppedAt != 0L) Log.i(TAG, "Relay reconnected")
            droppedAt = 0L
            attempt = 0
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val m = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            when (m.str("t")) {
                "room" -> when (m.str("event")) {
                    "joined" -> {
                        listener.onPeerJoined()
                        sendCandidates()
                    }
                    "left" -> listener.onPeerLeft()
                }
                "cand" -> onCandidates(m)
                else -> listener.onText(m)
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            listener.onBinary(bytes.toByteArray())
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            relayDropped(webSocket, t.message ?: t.javaClass.simpleName)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            relayDropped(webSocket, "closed $code $reason")
        }
    }

    // --- UDP: candidates, hole punching, data ----------------------------------------

    private var myCandidates: List<String> = emptyList()

    private fun udpSetup() {
        val socket = DatagramSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(0))
            soTimeout = 250
        }
        udp = socket
        val cands = mutableListOf<String>()
        // Local addresses first: same Wi-Fi is the fastest path.
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .forEach { cands += "${it.hostAddress}:${socket.localPort}" }
        stunPublicAddress(socket)?.let { cands += it }
        myCandidates = cands
        sendCandidates()
        receiveLoop(socket)
    }

    private fun sendCandidates() {
        if (myCandidates.isEmpty()) return
        sendText(buildJsonObject {
            put("t", "cand")
            putJsonArray("list") { myCandidates.forEach { add(JsonPrimitive(it)) } }
        })
    }

    private fun onCandidates(m: JsonObject) {
        val list = runCatching { m["list"]!!.jsonArray.mapNotNull { (it as? JsonPrimitive)?.content } }.getOrDefault(emptyList())
        val addrs = list.mapNotNull { s ->
            val host = s.substringBeforeLast(':')
            val port = s.substringAfterLast(':').toIntOrNull() ?: return@mapNotNull null
            runCatching { InetSocketAddress(InetAddress.getByName(host), port) }.getOrNull()
        }
        synchronized(peerCandidates) {
            peerCandidates.clear()
            peerCandidates += addrs
        }
        thread(name = "LinkPunch", isDaemon = true) { punch() }
    }

    /** Both sides probe every candidate for a few seconds; the first answer wins. */
    private fun punch() {
        val socket = waitForSocket() ?: return
        val probe = ByteArray(7).also { header(it, KIND_PROBE) }
        val deadline = SystemClock.elapsedRealtime() + 6000
        while (!closed && direct == null && SystemClock.elapsedRealtime() < deadline) {
            val targets = synchronized(peerCandidates) { peerCandidates.toList() }
            for (t in targets) runCatching { socket.send(DatagramPacket(probe, probe.size, t)) }
            Thread.sleep(100)
        }
    }

    private fun waitForSocket(): DatagramSocket? {
        repeat(50) {
            udp?.let { return it }
            Thread.sleep(100)
        }
        return null
    }

    private fun receiveLoop(socket: DatagramSocket) {
        val buf = ByteArray(2048)
        while (!closed) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                socket.receive(packet)
            } catch (_: SocketTimeoutException) {
                checkDirectAlive()
                continue
            } catch (_: Exception) {
                break
            }
            val n = packet.length
            if (n < 7 || buf[0] != 'M'.code.toByte() || buf[1] != 'B'.code.toByte()) continue
            if (buf[3] != key[0] || buf[4] != key[1] || buf[5] != key[2] || buf[6] != key[3]) continue
            val from = packet.socketAddress as InetSocketAddress
            lastUdpRx = SystemClock.elapsedRealtime()
            when (buf[2].toInt()) {
                KIND_PROBE -> {
                    val ack = ByteArray(7).also { header(it, KIND_ACK) }
                    runCatching { socket.send(DatagramPacket(ack, ack.size, from)) }
                    setDirect(from)
                }
                KIND_ACK -> setDirect(from)
                KIND_DATA -> listener.onBinary(buf.copyOfRange(7, n))
            }
        }
    }

    private fun setDirect(addr: InetSocketAddress) {
        if (direct == null) {
            direct = addr
            listener.onDirect(true)
        }
    }

    private fun checkDirectAlive() {
        // Direct path went quiet (network change, NAT timeout): fall back to the relay.
        if (direct != null && SystemClock.elapsedRealtime() - lastUdpRx > 3000) {
            direct = null
            listener.onDirect(false)
        }
    }

    private fun header(packet: ByteArray, kind: Int) {
        packet[0] = 'M'.code.toByte()
        packet[1] = 'B'.code.toByte()
        packet[2] = kind.toByte()
        key.copyInto(packet, 3, 0, 4)
    }

    /** RFC 5389 binding request; returns "ip:port" as seen from the internet. */
    private fun stunPublicAddress(socket: DatagramSocket): String? {
        val server = runCatching {
            InetAddress.getAllByName(STUN_HOST).firstOrNull { it is Inet4Address }
        }.getOrNull() ?: return null
        val txn = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val req = ByteBuffer.allocate(20).putShort(0x0001).putShort(0).putInt(MAGIC).put(txn).array()
        val buf = ByteArray(512)
        repeat(3) {
            runCatching { socket.send(DatagramPacket(req, req.size, InetSocketAddress(server, STUN_PORT))) }
            val packet = DatagramPacket(buf, buf.size)
            try {
                socket.receive(packet)
            } catch (_: Exception) {
                return@repeat
            }
            parseStun(buf, packet.length, txn)?.let { return it }
        }
        return null
    }

    private fun parseStun(b: ByteArray, len: Int, txn: ByteArray): String? {
        if (len < 20) return null
        val bb = ByteBuffer.wrap(b, 0, len)
        if (bb.short.toInt() != 0x0101) return null
        bb.short
        if (bb.int != MAGIC) return null
        val t = ByteArray(12).also { bb.get(it) }
        if (!t.contentEquals(txn)) return null
        while (bb.remaining() >= 4) {
            val type = bb.short.toInt() and 0xFFFF
            val alen = bb.short.toInt() and 0xFFFF
            if (bb.remaining() < alen) break
            val start = bb.position()
            if ((type == 0x0020 || type == 0x0001) && alen >= 8) {
                bb.get()
                val family = bb.get().toInt()
                var port = bb.short.toInt() and 0xFFFF
                var ip = bb.int
                if (type == 0x0020) {
                    port = port xor (MAGIC ushr 16)
                    ip = ip xor MAGIC
                }
                if (family == 1) {
                    val addr = "${ip ushr 24 and 255}.${ip ushr 16 and 255}.${ip ushr 8 and 255}.${ip and 255}"
                    return "$addr:$port"
                }
            }
            bb.position(start + ((alen + 3) and 3.inv()))
        }
        return null
    }

    companion object {
        private const val TAG = "PeerConnection"
        private const val STUN_HOST = "stun.cloudflare.com"
        private const val STUN_PORT = 3478
        private const val MAGIC = 0x2112A442
        private const val RECONNECT_FOR_MS = 15_000L
        private const val KIND_DATA = 0x02
        private const val KIND_PROBE = 0x09
        private const val KIND_ACK = 0x0A
    }
}
