package dev.m4s4t0.prism.ui.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.m4s4t0.prism.PrismApp
import dev.m4s4t0.prism.data.BoxArt
import dev.m4s4t0.prism.data.SaveTransfer
import dev.m4s4t0.prism.ui.common.CoverArt
import dev.m4s4t0.prism.ui.common.CoverRefresh
import dev.m4s4t0.prism.ui.common.Format
import dev.m4s4t0.prism.ui.game.GameActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GameInfoScreen(id: String, onBack: () -> Unit, onOpenSaves: () -> Unit) {
    val app = PrismApp.instance
    val storage = app.storage
    val ctx = LocalContext.current
    val games by app.library.games.collectAsState()
    val game = games.firstOrNull { it.id == id }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var version by remember { mutableIntStateOf(0) }
    var confirmRemove by remember { mutableStateOf(false) }
    var confirmImportSav by remember { mutableStateOf<ByteArray?>(null) }

    if (game == null) {
        LaunchedEffect(Unit) { onBack() } // removed from the library
        return
    }
    fun say(msg: String) = scope.launch { snackbar.showSnackbar(msg) }

    val sav = remember(version, game.lastPlayed) { storage.saveFile(id) }
    val savExists = remember(version, game.lastPlayed) { sav.exists() && sav.length() > 0 }
    val stateCount = remember(version, game.lastPlayed) { storage.stateSlots(id).count { it.exists } }

    val exportSav = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch { say(if (SaveTransfer.export(ctx, sav, uri)) "In-game save exported" else "Export failed") }
    }
    val importSav = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val data = SaveTransfer.readSav(ctx, uri)
            if (data == null) say("That file can't be used as a save (empty or too large)")
            else if (savExists) confirmImportSav = data
            else {
                SaveTransfer.writeSav(sav, data); version++; say("In-game save imported")
            }
        }
    }
    val relocate = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch { say(app.library.relocate(id, uri)?.message ?: "File location updated") }
    }
    val pickCover = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return@runCatching false
                    val scale = minOf(1f, 512f / maxOf(bmp.width, bmp.height))
                    val out = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true)
                    val tmp = File(storage.coverFile(id).path + ".tmp")
                    tmp.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    tmp.renameTo(storage.coverFile(id))
                }.getOrDefault(false)
            }
            if (ok) CoverRefresh.version++
            say(if (ok) "Cover updated" else "Couldn't use that image")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Game info") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CoverArt(game, Modifier.size(132.dp))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(game.displayTitle, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${game.title.ifEmpty { "—" }} · ${game.gameCode.ifEmpty { "—" }}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { ctx.startActivity(GameActivity.intent(ctx, id)) }) {
                        Icon(Icons.Default.PlayArrow, null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (game.lastPlayed > 0) "Continue" else "Play")
                    }
                }
            }

            Section("Activity") {
                Info("Playtime", Format.playtime(game.playtimeMs))
                Info("Last played", Format.relative(game.lastPlayed))
                Info("Added", Format.dateTime(game.addedAt))
            }

            Section("In-game save (cartridge)") {
                Text(
                    "Written by the game itself, like on a real cartridge (SRAM, Flash or EEPROM).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Info("Status", if (savExists) "${Format.size(sav.length())} · ${Format.dateTime(sav.lastModified())}" else "No save yet")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = savExists, onClick = { exportSav.launch("${game.displayTitle}.sav") }) { Text("Export .sav") }
                    OutlinedButton(onClick = { importSav.launch(arrayOf("*/*")) }) { Text("Import .sav") }
                }
            }

            Section("Save states") {
                Info("Used slots", "$stateCount of 10")
                OutlinedButton(onClick = onOpenSaves) { Text("Manage save states") }
            }

            Section("Cover") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickCover.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                        Text("Choose image")
                    }
                    OutlinedButton(onClick = {
                        scope.launch {
                            val ok = BoxArt.fetch(game.fileName, storage.coverFile(id))
                            if (ok) CoverRefresh.version++
                            say(if (ok) "Box art downloaded" else "No box art found for this file name")
                        }
                    }) { Text("Download box art") }
                    if (storage.coverFile(id).exists()) TextButton(onClick = { storage.coverFile(id).delete(); CoverRefresh.version++ }) { Text("Remove") }
                }
            }

            Section("File") {
                Info("Name", game.fileName)
                Info("Size", Format.size(game.size))
                Info("Location", if (game.imported) "Copied into app storage" else "Original location (${Uri.parse(game.uri).authority ?: "device"})")
                Info("SHA-1", game.id, mono = true)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { relocate.launch(arrayOf("*/*")) }) { Text("Locate file…") }
                    TextButton(onClick = { confirmRemove = true }) { Text("Remove from library", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }

    if (confirmRemove) {
        var deleteData by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove ${game.displayTitle}?") },
            text = {
                Column {
                    Text("The ROM file itself is never deleted${if (game.imported) " (except Prism's imported copy)" else ""}.")
                    Row(Modifier.clickable { deleteData = !deleteData }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(deleteData, { deleteData = it })
                        Text("Also delete in-game save and save states")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    scope.launch { app.library.remove(id, deleteData) }
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
    confirmImportSav?.let { data ->
        AlertDialog(
            onDismissRequest = { confirmImportSav = null },
            title = { Text("Replace in-game save?") },
            text = { Text("Your current in-game save will be replaced. A backup copy (.bak) is kept in app storage.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmImportSav = null
                    scope.launch { SaveTransfer.writeSav(sav, data); version++; say("In-game save imported") }
                }) { Text("Replace") }
            },
            dismissButton = { TextButton(onClick = { confirmImportSav = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Spacer(Modifier.height(20.dp))
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun Info(label: String, value: String, mono: Boolean = false) {
    Row {
        Text(label, Modifier.width(110.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = if (mono) FontFamily.Monospace else null)
    }
}
