package dev.m4s4t0.microboy.ui.saves

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.m4s4t0.microboy.MicroBoyApp
import dev.m4s4t0.microboy.data.AppSettings
import dev.m4s4t0.microboy.data.SaveTransfer
import dev.m4s4t0.microboy.ui.common.slotName
import dev.m4s4t0.microboy.ui.game.GameActivity
import kotlinx.coroutines.launch

/** Browse a game's save states outside of gameplay. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaveManagerScreen(id: String, onBack: () -> Unit) {
    val app = MicroBoyApp.instance
    val storage = app.storage
    val ctx = LocalContext.current
    val game = app.library.get(id)
    val settings by app.settings.settings.collectAsState(AppSettings())
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var version by remember { mutableIntStateOf(0) }
    var pendingSlot by remember { mutableIntStateOf(-1) }
    val slots = remember(version) { storage.stateSlots(id) }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val ok = SaveTransfer.export(ctx, storage.stateFile(id, pendingSlot), uri)
            snackbar.showSnackbar(if (ok) "Exported ${slotName(pendingSlot)}" else "Export failed")
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val err = SaveTransfer.importState(ctx, uri, storage.stateFile(id, pendingSlot))
            version++
            snackbar.showSnackbar(err ?: "Imported into ${slotName(pendingSlot)}")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Save states")
                        Text(game?.displayTitle ?: "", style = MaterialTheme.typography.bodySmall)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            Text(
                "Tap a state to launch the game from it. The Auto slot is written whenever you leave a game.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            SaveSlotGrid(
                slots = slots,
                mode = SlotMode.BROWSE,
                defaultSlot = settings.defaultSlot,
                actions = SlotActions(
                    onLoad = { ctx.startActivity(GameActivity.intent(ctx, id, loadSlot = it)) },
                    onDelete = {
                        storage.stateFile(id, it).delete()
                        storage.stateThumbnail(id, it).delete()
                        version++
                    },
                    onExport = {
                        pendingSlot = it
                        export.launch("${game?.displayTitle ?: "game"} - ${slotName(it)}.state")
                    },
                    onImport = {
                        pendingSlot = it
                        import.launch(arrayOf("*/*"))
                    },
                ),
            )
        }
    }
}
