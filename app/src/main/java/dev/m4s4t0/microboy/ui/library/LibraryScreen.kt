package dev.m4s4t0.microboy.ui.library

import dev.m4s4t0.microboy.ui.theme.uiBorder
import dev.m4s4t0.microboy.ui.theme.uiElevation
import dev.m4s4t0.microboy.ui.theme.uiPill
import dev.m4s4t0.microboy.ui.theme.uiShape
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.Feedback
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.m4s4t0.microboy.MicroBoyApp
import dev.m4s4t0.microboy.data.AddResult
import dev.m4s4t0.microboy.data.AppSettings
import dev.m4s4t0.microboy.data.BoxArt
import dev.m4s4t0.microboy.data.Game
import dev.m4s4t0.microboy.ui.common.CoverArt
import dev.m4s4t0.microboy.ui.common.CoverRefresh
import dev.m4s4t0.microboy.ui.common.Format
import dev.m4s4t0.microboy.ui.game.GameActivity
import kotlinx.coroutines.launch

private enum class Sort { RECENT, NAME, PLAYTIME }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(onOpenInfo: (String) -> Unit, onOpenSettings: () -> Unit) {
    val app = MicroBoyApp.instance
    val ctx = LocalContext.current
    val games by app.library.games.collectAsState()
    val settings by app.settings.settings.collectAsState(AppSettings())
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var busy by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf(Sort.RECENT) }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var homebrew by rememberSaveable { mutableStateOf(false) }
    if (homebrew) {
        androidx.activity.compose.BackHandler { homebrew = false }
        HomebrewScreen(
            onBack = { homebrew = false },
            onMessage = { android.widget.Toast.makeText(ctx, it, android.widget.Toast.LENGTH_SHORT).show() },
        )
        return
    }

    fun play(game: Game) = ctx.startActivity(GameActivity.intent(ctx, game.id))

    fun report(r: AddResult) {
        val parts = mutableListOf<String>()
        if (r.added > 0) parts += "Added ${r.added} game${if (r.added == 1) "" else "s"}"
        if (r.duplicates > 0) parts += "${r.duplicates} already in library"
        if (r.failures.isNotEmpty()) {
            val (name, err) = r.failures.first()
            parts += if (r.failures.size == 1) "$name: ${err.message}" else "${r.failures.size} files skipped (${err.message})"
        }
        if (parts.isEmpty()) parts += "No .gba files found"
        scope.launch { snackbar.showSnackbar(parts.joinToString(" · ")) }
    }

    fun afterAdd(r: AddResult) {
        report(r)
        if (settings.downloadBoxArt && r.added > 0) {
            app.appScope.launch {
                for (g in app.library.games.value) {
                    val f = app.storage.coverFile(g.id)
                    if (!f.exists() && BoxArt.fetch(g.fileName, f)) CoverRefresh.version++
                }
            }
        }
    }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            afterAdd(app.library.addFiles(uris, settings.importRoms))
            busy = false
        }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            afterAdd(app.library.addFolder(uri, settings.importRoms))
            busy = false
        }
    }

    val sorted = remember(games, sort, query) {
        val filtered = if (query.isBlank()) games else games.filter {
            it.displayTitle.contains(query, true) || it.title.contains(query, true)
        }
        when (sort) {
            Sort.RECENT -> filtered.sortedWith(compareByDescending<Game> { it.lastPlayed }.thenByDescending { it.addedAt })
            Sort.NAME -> filtered.sortedBy { it.displayTitle.lowercase() }
            Sort.PLAYTIME -> filtered.sortedByDescending { it.playtimeMs }
        }
    }
    val continueGame = remember(games) { app.library.lastPlayed() }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("MicroBoy") },
                actions = {
                    if (games.isNotEmpty()) IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                        Icon(if (searching) Icons.Default.Close else Icons.Default.Search, "Search")
                    }
                    val context = androidx.compose.ui.platform.LocalContext.current
                    IconButton(onClick = { dev.m4s4t0.microboy.ui.common.Feedback.email(context) }) {
                        Icon(Icons.Default.Feedback, "Send feedback")
                    }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "Settings") }
                },
                scrollBehavior = scroll,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            Box {
                ExtendedFloatingActionButton(
                    onClick = { addMenu = true },
                    elevation = androidx.compose.material3.FloatingActionButtonDefaults.elevation(
                        defaultElevation = uiElevation(), pressedElevation = uiElevation(),
                        focusedElevation = uiElevation(), hoveredElevation = uiElevation(),
                    ),
                    icon = { if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Add, null) },
                    text = { Text(if (busy) "Adding…" else "Add games") },
                )
                DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Choose .gba files") },
                        leadingIcon = { Icon(Icons.Default.InsertDriveFile, null) },
                        onClick = { addMenu = false; pickFiles.launch(arrayOf("*/*")) },
                    )
                    DropdownMenuItem(
                        text = { Text("Scan a folder") },
                        leadingIcon = { Icon(Icons.Default.CreateNewFolder, null) },
                        onClick = { addMenu = false; pickFolder.launch(null) },
                    )
                    DropdownMenuItem(
                        text = { Text("MicroBoy homebrew (free)") },
                        leadingIcon = { Icon(Icons.Default.CloudDownload, null) },
                        onClick = { addMenu = false; homebrew = true },
                    )
                }
            }
        },
    ) { pad ->
        if (games.isEmpty()) {
            EmptyLibrary(
                Modifier.padding(pad),
                onAddFiles = { pickFiles.launch(arrayOf("*/*")) },
                onAddFolder = { pickFolder.launch(null) },
                onHomebrew = { homebrew = true },
            )
            return@Scaffold
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding(), bottom = 96.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (searching) item(span = { GridItemSpan(maxLineSpan) }) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search games") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (continueGame != null && query.isBlank()) item(span = { GridItemSpan(maxLineSpan) }) {
                ContinueCard(continueGame, onPlay = { play(continueGame) }, onInfo = { onOpenInfo(continueGame.id) })
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${sorted.size} games", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    FilterChip(sort == Sort.RECENT, { sort = Sort.RECENT }, { Text("Recent") })
                    FilterChip(sort == Sort.NAME, { sort = Sort.NAME }, { Text("A–Z") })
                    FilterChip(sort == Sort.PLAYTIME, { sort = Sort.PLAYTIME }, { Text("Most played") })
                }
            }
            items(sorted, key = { it.id }) { game ->
                GameCard(game, onClick = { play(game) }, onLongClick = { onOpenInfo(game.id) })
            }
        }
    }
}

@Composable
private fun ContinueCard(game: Game, onPlay: () -> Unit, onInfo: () -> Unit) {
    Card(
        onClick = onInfo,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = uiShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = uiElevation()),
        border = uiBorder(),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            CoverArt(game, Modifier.size(96.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("CONTINUE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(
                    game.displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    "${Format.relative(game.lastPlayed)} · ${Format.playtime(game.playtimeMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
                Spacer(Modifier.height(8.dp))
                Button(shape = uiPill(), onClick = onPlay) {
                    Icon(Icons.Default.PlayArrow, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Resume")
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GameCard(game: Game, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        CoverArt(game, Modifier.fillMaxWidth().aspectRatio(1f))
        Spacer(Modifier.height(6.dp))
        Text(game.displayTitle, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            if (game.lastPlayed > 0) Format.relative(game.lastPlayed) else "New",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyLibrary(modifier: Modifier, onAddFiles: () -> Unit, onAddFolder: () -> Unit, onHomebrew: () -> Unit) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.SportsEsports, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("Your library is empty", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Add Game Boy Advance games (.gba) you own. Files stay where they are — MicroBoy only remembers where to find them.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        FilledTonalButton(shape = uiPill(), onClick = onAddFiles) { Text("Choose .gba files") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(shape = uiPill(), onClick = onAddFolder) { Text("Scan a folder") }
        Spacer(Modifier.height(8.dp))
        TextButton(shape = uiPill(), onClick = onHomebrew) { Text("Get free MicroBoy homebrew games") }
    }
}
