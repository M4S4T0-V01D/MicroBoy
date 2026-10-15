package dev.m4s4t0.microboy.ui.game

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import dev.m4s4t0.microboy.MicroBoyApp
import dev.m4s4t0.microboy.data.RomResult
import dev.m4s4t0.microboy.link.LinkConfig
import dev.m4s4t0.microboy.link.LobbyClient
import dev.m4s4t0.microboy.link.LobbyEvent
import dev.m4s4t0.microboy.link.LobbyPlayer
import dev.m4s4t0.microboy.link.LobbyProfile
import dev.m4s4t0.microboy.link.NetplaySession
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The game screen's side of the online link cable: lobby presence, invites,
 * blocking/reporting, and the active [NetplaySession].
 */
class LinkController(private val a: GameActivity) {
    private val app get() = MicroBoyApp.instance
    val lobby = LobbyClient(a.lifecycleScope)

    var sheetOpen by mutableStateOf(false)
    var online by mutableStateOf(false)
        private set
    var session by mutableStateOf<NetplaySession?>(null)
        private set
    var status by mutableStateOf<NetplaySession.Status?>(null)
        private set
    var incoming by mutableStateOf<LobbyEvent.Invite?>(null)
        private set
    /** Lobby id of the player we invited, while waiting for an answer. */
    var outgoing by mutableStateOf<String?>(null)
        private set
    var reporting by mutableStateOf<LobbyPlayer?>(null)

    /** True from the moment a match is made until the session ends. */
    val busy: Boolean get() = session != null
    val linked: Boolean get() = status is NetplaySession.Status.Linked

    fun start() {
        a.lifecycleScope.launch {
            lobby.events.collect { e ->
                when (e) {
                    is LobbyEvent.Invite -> if (!busy && e.from !in a.settings.linkBlocked) incoming = e else lobby.reply(e.from, false)
                    is LobbyEvent.Cancelled -> if (incoming?.from == e.from) incoming = null
                    is LobbyEvent.Declined -> if (outgoing == e.from) {
                        outgoing = null
                        a.message = "Invite declined"
                    }
                    is LobbyEvent.Unavailable -> {
                        if (outgoing == e.id) outgoing = null
                        a.message = "That player isn't available"
                    }
                    is LobbyEvent.Reported -> a.message = "Reported and blocked. Thanks for keeping the lobby friendly."
                    is LobbyEvent.Matched -> begin(e)
                }
            }
        }
    }

    fun profile(): LobbyProfile {
        val s = a.settings
        return LobbyProfile(
            nick = s.linkNick,
            install = LinkConfig.installId(a),
            visible = s.linkVisible,
            shareGame = s.linkShareGame,
            gameTitle = a.game.displayTitle,
            gameCode = a.game.gameCode,
            blocked = s.linkBlocked,
        )
    }

    /** Rejoins the lobby when a game opens, if the player left Online switched on. */
    fun restore() {
        if (a.settings.linkOnline && !online) goOnline()
    }

    fun goOnline() {
        if (a.settings.linkNick.isBlank()) return
        online = true
        lobby.connect(profile())
        if (!a.settings.linkOnline) a.lifecycleScope.launch { app.settings.update { it.copy(linkOnline = true) } }
    }

    fun goOffline() {
        online = false
        a.lifecycleScope.launch { app.settings.update { it.copy(linkOnline = false) } }
        outgoing?.let { lobby.cancel(it) }
        outgoing = null
        lobby.disconnect()
    }

    fun onSettingsChanged() {
        if (online) lobby.update(profile())
    }

    fun setNick(nick: String) {
        a.lifecycleScope.launch {
            app.settings.update { it.copy(linkNick = nick.trim()) }
            if (online) lobby.update(profile()) else goOnline()
        }
    }

    fun setVisible(v: Boolean) = a.lifecycleScope.launch { app.settings.update { it.copy(linkVisible = v) } }
    fun setShareGame(v: Boolean) = a.lifecycleScope.launch { app.settings.update { it.copy(linkShareGame = v) } }

    fun invite(p: LobbyPlayer) {
        outgoing?.let { lobby.cancel(it) }
        outgoing = p.id
        lobby.invite(p.id)
    }

    fun cancelInvite() {
        outgoing?.let { lobby.cancel(it) }
        outgoing = null
    }

    fun accept() {
        val i = incoming ?: return
        incoming = null
        lobby.reply(i.from, true)
    }

    fun decline() {
        val i = incoming ?: return
        incoming = null
        lobby.reply(i.from, false)
    }

    fun block(p: LobbyPlayer) {
        lobby.block(p.id)
        a.lifecycleScope.launch { app.settings.update { it.copy(linkBlocked = it.linkBlocked + p.id) } }
        a.message = "${p.nick} is blocked"
    }

    fun report(p: LobbyPlayer, reason: String) {
        lobby.report(p.id, reason)
        a.lifecycleScope.launch { app.settings.update { it.copy(linkBlocked = it.linkBlocked + p.id) } }
    }

    fun say(index: Int) = session?.say(index)

    fun disconnect() {
        session?.end("You ended the link")
    }

    fun onLocalPaused(paused: Boolean) {
        session?.setLocalPaused(paused)
    }

    fun shutdown() {
        session?.end("The game was closed")
        lobby.disconnect()
    }

    private fun begin(m: LobbyEvent.Matched) {
        outgoing = null
        incoming = null
        val emu = a.emulator ?: return
        if (busy) return
        val s = NetplaySession(
            scope = a.lifecycleScope,
            emu = emu,
            player = m.player,
            peerNick = m.peerNick,
            myNick = a.settings.linkNick,
            localGame = a.game,
            localRom = emu.rom,
            savePath = app.storage.saveFile(a.game.id).path,
            findGame = { app.library.get(it) },
            readRom = { g -> (app.library.readRom(g) as? RomResult.Ok)?.value },
            room = m.room,
        )
        session = s
        status = NetplaySession.Status.Connecting
        a.message = "Linking with ${m.peerNick}…"
        a.lifecycleScope.launch {
            s.status.collect { st ->
                val wasLinked = status is NetplaySession.Status.Linked
                status = st
                when (st) {
                    is NetplaySession.Status.Linked -> if (!wasLinked) {
                        // Just linked: get back to the game.
                        if (a.menuOpen) a.closeMenu()
                        sheetOpen = false
                        a.message = "Linked with ${s.peerNick}"
                        s.setLocalPaused(a.menuOpen)
                    }
                    is NetplaySession.Status.Ended -> {
                        a.message = st.reason
                        session = null
                        status = null
                        cancel()
                    }
                    else -> Unit
                }
            }
        }
        a.lifecycleScope.launch {
            s.messages.collect { msg ->
                if (session !== s) cancel()
                if (msg != null) a.message = msg
            }
        }
        s.start()
    }
}
