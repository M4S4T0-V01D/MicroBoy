package dev.m4s4t0.microboy.ui.game

import dev.m4s4t0.microboy.ui.theme.uiBorder
import dev.m4s4t0.microboy.ui.theme.uiElevation
import dev.m4s4t0.microboy.ui.theme.uiPill
import dev.m4s4t0.microboy.ui.theme.uiShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.m4s4t0.microboy.MicroBoyApp
import dev.m4s4t0.microboy.link.LinkConfig
import dev.m4s4t0.microboy.link.LobbyPlayer
import dev.m4s4t0.microboy.link.LobbyState
import dev.m4s4t0.microboy.link.NetplaySession

/** Full-screen Link cable page: who's online, invites and the current link. */
@Composable
fun LinkSheet(a: GameActivity) {
    val link = a.link
    val lobby by link.lobby.state.collectAsState()
    val s = a.settings
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { link.sheetOpen = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text("Link cable", style = MaterialTheme.typography.titleLarge)
            }
            if (s.linkNick.isBlank()) {
                NickSetup(a)
                return@Column
            }
            LazyColumn(Modifier.fillMaxSize()) {
                item { SessionCard(a) }
                item { OnlineCard(a, lobby) }
                if (link.online && lobby.status == LobbyState.Status.ONLINE) {
                    playerSection(
                        a,
                        "Playing a game",
                        "Players showing what they're playing. You need the same game in your library, unless you both have both games.",
                        lobby.games,
                    )
                    playerSection(
                        a,
                        "Open to link",
                        "Players showing only their nickname. Connect, see each other's games, and pick one together.",
                        lobby.open,
                    )
                }
                item { Spacer(Modifier.height(32.dp)) }
            }
        }
    }
}

@Composable
private fun NickSetup(a: GameActivity) {
    var nick by remember { mutableStateOf("") }
    Column(Modifier.padding(20.dp).widthIn(max = 520.dp)) {
        Text("Play together online", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Trade and battle like with a link cable, over Wi-Fi or the internet. Both players need the game they're linking with, and the same MicroBoy version.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = nick,
            onValueChange = { nick = it.take(16) },
            label = { Text("Nickname") },
            supportingText = { Text("Other players see this. 2–16 letters, numbers, spaces, . _ -") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        val context = androidx.compose.ui.platform.LocalContext.current
        Text(
            "While you're online, other players see your nickname, and your game's title if you choose. Linking sends save states and button presses only to the player you link with.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(shape = uiPill(), onClick = {
            runCatching {
                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://m4s4t0-v01d.github.io/MicroBoy/privacy.html")))
            }
        }) { Text("Privacy policy") }
        Button(shape = uiPill(), onClick = { a.link.setNick(nick) }, enabled = nick.trim().length >= 2) { Text("Go online") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionCard(a: GameActivity) {
    val link = a.link
    val st = link.status ?: return
    val session = link.session ?: return
    Surface(
        shape = uiShape(20.dp),
        shadowElevation = uiElevation(),
        border = uiBorder(),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Link, null)
                Spacer(Modifier.width(8.dp))
                Text("Linking with ${session.peerNick}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(6.dp))
            when (st) {
                NetplaySession.Status.Connecting -> Text("Connecting…")
                NetplaySession.Status.Checking -> Text("Checking that you both have the games…")
                is NetplaySession.Status.Syncing -> {
                    Text("Swapping save states… ${st.percent}%")
                    LinearProgressIndicator(progress = { st.percent / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                }
                is NetplaySession.Status.Linked -> Text(
                    "Linked. ${session.peerNick} is playing ${st.peerGame}. " +
                        "${if (st.direct) "Direct connection" else "Via relay"}, ${st.delay}-frame input delay." +
                        if (st.peerPaused) " ${session.peerNick} paused." else ""
                )
                is NetplaySession.Status.Ended -> Text(st.reason)
            }
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LinkConfig.QUICK_MESSAGES.forEachIndexed { i, m -> AssistChip(onClick = { link.say(i) }, label = { Text(m) }) }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(shape = uiPill(), onClick = { link.disconnect() }) { Text("Disconnect") }
        }
    }
}

@Composable
private fun OnlineCard(a: GameActivity, lobby: LobbyState) {
    val link = a.link
    val s = a.settings
    var editNick by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Online as ${s.linkNick}", style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        !link.online -> "Offline. Nobody can see or invite you."
                        lobby.error != null -> lobby.error
                        lobby.status == LobbyState.Status.ONLINE -> "In the lobby"
                        else -> "Connecting…"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = link.online, onCheckedChange = { if (it) link.goOnline() else link.goOffline() })
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Text("Show my game (${a.game.displayTitle})", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Switch(checked = s.linkShareGame, onCheckedChange = { link.setShareGame(it) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Visible in the lobby", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = s.linkVisible, onCheckedChange = { link.setVisible(it) })
        }
        TextButton(shape = uiPill(), onClick = { editNick = true }) { Text("Change nickname") }
        HorizontalDivider()
    }
    if (editNick) {
        var nick by remember { mutableStateOf(s.linkNick) }
        AlertDialog(
            onDismissRequest = { editNick = false },
            title = { Text("Nickname") },
            text = { OutlinedTextField(value = nick, onValueChange = { nick = it.take(16) }, singleLine = true) },
            confirmButton = {
                TextButton(shape = uiPill(), enabled = nick.trim().length >= 2, onClick = { editNick = false; link.setNick(nick) }) { Text("Save") }
            },
            dismissButton = { TextButton(shape = uiPill(), onClick = { editNick = false }) { Text("Cancel") } },
        )
    }
}

private fun LazyListScope.playerSection(a: GameActivity, title: String, subtitle: String, players: List<LobbyPlayer>) {
    item {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (players.isEmpty()) {
        item {
            Text("Nobody here right now.", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    items(players, key = { "$title/${it.id}" }) { p -> PlayerRow(a, p) }
}

@Composable
private fun PlayerRow(a: GameActivity, p: LobbyPlayer) {
    val link = a.link
    var menu by remember { mutableStateOf(false) }
    val haveGame = p.gameCode != null && MicroBoyApp.instance.library.games.collectAsState().value.any { it.gameCode == p.gameCode }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(p.nick, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (p.gameTitle != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.gameTitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (haveGame) {
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Default.CheckCircle, "You have this game", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        if (link.outgoing == p.id) {
            OutlinedButton(shape = uiPill(), onClick = { link.cancelInvite() }) { Text("Cancel") }
        } else {
            Button(shape = uiPill(), onClick = { link.invite(p) }, enabled = !link.busy) { Text("Invite") }
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Block") }, onClick = { menu = false; link.block(p) })
                DropdownMenuItem(text = { Text("Report") }, onClick = { menu = false; link.reporting = p })
            }
        }
    }
}

/** The invite prompt can appear over the game at any time while online. */
@Composable
fun LinkDialogs(a: GameActivity) {
    val link = a.link
    link.incoming?.let { inv ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("${inv.nick} wants to link") },
            text = {
                Text(
                    (if (inv.gameTitle != null) "They're playing ${inv.gameTitle}. " else "") +
                        "Linking pauses your game briefly while you swap save states."
                )
            },
            confirmButton = { TextButton(shape = uiPill(), onClick = { link.accept() }) { Text("Link") } },
            dismissButton = { TextButton(shape = uiPill(), onClick = { link.decline() }) { Text("No thanks") } },
        )
    }
    link.reporting?.let { p ->
        val reasons = listOf("Offensive nickname", "Harassment", "Cheating or griefing", "Something else")
        var pick by remember(p.id) { mutableStateOf(reasons[0]) }
        AlertDialog(
            onDismissRequest = { link.reporting = null },
            title = { Text("Report ${p.nick}") },
            text = {
                Column {
                    reasons.forEach { r ->
                        Row(
                            Modifier.fillMaxWidth().selectable(selected = pick == r, role = Role.RadioButton) { pick = r }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = pick == r, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(r)
                        }
                    }
                    Text("They'll also be blocked for you.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(shape = uiPill(), onClick = { link.report(p, pick); link.reporting = null }) { Text("Report") } },
            dismissButton = { TextButton(shape = uiPill(), onClick = { link.reporting = null }) { Text("Cancel") } },
        )
    }
}

/** Small "linked" badge over the game. */
@Composable
fun LinkBadge(a: GameActivity, modifier: Modifier) {
    val st = a.link.status as? NetplaySession.Status.Linked ?: return
    val session = a.link.session ?: return
    Row(
        modifier.background(Color.Black.copy(alpha = 0.55f), uiPill()).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Link, null, Modifier.size(14.dp), tint = Color(0xFF9CFFB0))
        Spacer(Modifier.width(4.dp))
        Text(
            if (st.peerPaused) "${session.peerNick} paused" else session.peerNick,
            color = Color.White,
            fontSize = 12.sp,
        )
    }
}
