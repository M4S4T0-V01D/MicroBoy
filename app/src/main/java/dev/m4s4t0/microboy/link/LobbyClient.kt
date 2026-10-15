package dev.m4s4t0.microboy.link

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/** What this player shows in the lobby. */
data class LobbyProfile(
    val nick: String,
    val install: String,
    val visible: Boolean,
    val shareGame: Boolean,
    val gameTitle: String?,
    val gameCode: String?,
    val blocked: Set<String>,
)

data class LobbyPlayer(val id: String, val nick: String, val gameTitle: String? = null, val gameCode: String? = null)

data class LobbyState(
    val status: Status = Status.OFFLINE,
    val myId: String? = null,
    /** Players who share the game they're playing. */
    val games: List<LobbyPlayer> = emptyList(),
    /** Players who only show their nickname. */
    val open: List<LobbyPlayer> = emptyList(),
    val error: String? = null,
) {
    enum class Status { OFFLINE, CONNECTING, ONLINE }
}

sealed interface LobbyEvent {
    data class Invite(val from: String, val nick: String, val gameTitle: String?) : LobbyEvent
    data class Cancelled(val from: String) : LobbyEvent
    data class Declined(val from: String) : LobbyEvent
    data class Unavailable(val id: String) : LobbyEvent
    data class Matched(val room: String, val player: Int, val peerId: String, val peerNick: String) : LobbyEvent
    data class Reported(val id: String) : LobbyEvent
}

/**
 * Connection to the link server's lobby: the two player lists, invites,
 * blocking and reporting. Reconnects on its own while [connect]ed.
 */
class LobbyClient(private val scope: CoroutineScope) {
    private val http = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private val _state = MutableStateFlow(LobbyState())
    val state: StateFlow<LobbyState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<LobbyEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<LobbyEvent> = _events.asSharedFlow()

    private var socket: WebSocket? = null
    private var profile: LobbyProfile? = null
    private var wanted = false
    private var retry: Job? = null

    fun connect(p: LobbyProfile) {
        profile = p
        wanted = true
        if (socket == null) open()
    }

    fun disconnect() {
        wanted = false
        retry?.cancel()
        socket?.close(1000, "bye")
        socket = null
        _state.value = LobbyState()
    }

    /** Pushes profile changes (visibility, shared game, nickname) to the lobby. */
    fun update(p: LobbyProfile) {
        val old = profile
        profile = p
        if (old?.nick != p.nick) {
            send(buildJsonObject { put("t", "update"); put("nick", p.nick) })
        }
        send(buildJsonObject {
            put("t", "update")
            put("visible", p.visible)
            put("share", p.shareGame)
            putGame(p)
        })
    }

    fun invite(id: String) = send(buildJsonObject { put("t", "invite"); put("to", id) })
    fun cancel(id: String) = send(buildJsonObject { put("t", "cancel"); put("to", id) })
    fun reply(id: String, accept: Boolean) = send(buildJsonObject { put("t", "reply"); put("to", id); put("accept", accept) })
    fun block(id: String) = send(buildJsonObject { put("t", "block"); put("id", id) })
    fun report(id: String, reason: String) = send(buildJsonObject { put("t", "report"); put("id", id); put("reason", reason) })

    private fun send(obj: JsonObject) {
        socket?.send(obj.toString())
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putGame(p: LobbyProfile) {
        if (p.gameTitle != null) {
            putJsonObject("game") {
                put("title", p.gameTitle)
                put("code", p.gameCode ?: "")
            }
        } else {
            put("game", kotlinx.serialization.json.JsonNull)
        }
    }

    private fun open() {
        _state.update { it.copy(status = LobbyState.Status.CONNECTING, error = null) }
        val request = Request.Builder().url("${LinkConfig.SERVER}/lobby").build()
        socket = http.newWebSocket(request, listener)
    }

    private fun scheduleRetry() {
        socket = null
        if (!wanted) return
        retry?.cancel()
        retry = scope.launch {
            delay(3000)
            if (wanted && socket == null) open()
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val p = profile ?: return
            webSocket.send(buildJsonObject {
                put("t", "hello")
                put("nick", p.nick)
                put("install", p.install)
                put("link", LinkConfig.LINK_VERSION)
                put("visible", p.visible)
                put("share", p.shareGame)
                putGame(p)
                putJsonArray("blocked") { p.blocked.forEach { add(JsonPrimitive(it)) } }
            }.toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val m = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            when (m.str("t")) {
                "welcome" -> _state.update { it.copy(status = LobbyState.Status.ONLINE, myId = m.str("id"), error = null) }
                "lists" -> _state.update {
                    it.copy(games = players(m["games"]), open = players(m["open"]))
                }
                "error" -> {
                    _state.update { it.copy(error = m.str("message") ?: "The lobby refused the connection.") }
                    // Nickname and policy errors won't fix themselves by retrying.
                    wanted = false
                    webSocket.close(1000, null)
                }
                "invite" -> _events.tryEmit(LobbyEvent.Invite(m.str("from") ?: return, m.str("nick") ?: "?", m["game"]?.let { g -> (g as? JsonObject)?.str("title") }))
                "cancelled" -> _events.tryEmit(LobbyEvent.Cancelled(m.str("from") ?: return))
                "declined" -> _events.tryEmit(LobbyEvent.Declined(m.str("from") ?: return))
                "unavailable" -> _events.tryEmit(LobbyEvent.Unavailable(m.str("id") ?: return))
                "reported" -> _events.tryEmit(LobbyEvent.Reported(m.str("id") ?: return))
                "matched" -> {
                    val peer = m["peer"]?.jsonObject ?: return
                    _events.tryEmit(
                        LobbyEvent.Matched(
                            room = m.str("room") ?: return,
                            player = m["player"]?.jsonPrimitive?.intOrNull ?: return,
                            peerId = peer.str("id") ?: return,
                            peerNick = peer.str("nick") ?: "?",
                        )
                    )
                }
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket != socket) return
            _state.update { it.copy(status = LobbyState.Status.OFFLINE, games = emptyList(), open = emptyList()) }
            scheduleRetry()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (webSocket != socket) return
            Log.i(TAG, "Lobby connection failed: ${t.message}")
            _state.update {
                it.copy(status = LobbyState.Status.OFFLINE, games = emptyList(), open = emptyList(), error = it.error ?: "Can't reach the link server. Retrying…")
            }
            scheduleRetry()
        }
    }

    private fun players(e: JsonElement?): List<LobbyPlayer> = runCatching {
        e!!.jsonArray.map { p ->
            val o = p.jsonObject
            val g = o["game"] as? JsonObject
            LobbyPlayer(o.str("id")!!, o.str("nick") ?: "?", g?.str("title"), g?.str("code"))
        }.distinctBy { it.id }
    }.getOrDefault(emptyList())

    companion object {
        private const val TAG = "LobbyClient"
    }
}

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
