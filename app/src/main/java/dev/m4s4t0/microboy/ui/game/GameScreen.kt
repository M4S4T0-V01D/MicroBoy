package dev.m4s4t0.microboy.ui.game

import android.opengl.GLSurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.m4s4t0.microboy.MicroBoyApp
import dev.m4s4t0.microboy.input.TouchControllerView
import dev.m4s4t0.microboy.ui.common.Format
import dev.m4s4t0.microboy.ui.common.slotName
import dev.m4s4t0.microboy.ui.saves.SaveSlotGrid
import dev.m4s4t0.microboy.ui.saves.SlotActions
import dev.m4s4t0.microboy.ui.saves.SlotMode
import kotlinx.coroutines.delay

val FF_SPEEDS = listOf(2, 3, 4, 8, 0)
fun ffLabel(speed: Int) = if (speed == 0) "Max" else "$speed×"

@Composable
fun GameScreen(a: GameActivity, glView: GLSurfaceView, controller: TouchControllerView) {
    val s = a.settings
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView({ glView }, Modifier.fillMaxSize())
        AndroidView({ controller }, Modifier.fillMaxSize())

        // Performance overlay
        if ((s.showFps || s.showStats) && a.started) {
            val st = a.stats
            Column(
                Modifier.safeDrawingPadding().padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                val style = MaterialTheme.typography.labelSmall.copy(color = Color(0xFF9CFFB0))
                Text("%.1f FPS  %d%%".format(st.fps, (st.speed * 100).toInt()), style = style)
                if (s.showStats) {
                    Text("emu %.2f ms/frame".format(st.frameTimeMs), style = style)
                    Text("audio %d Hz  q %d/%d".format(st.audioRate, st.audioQueued, st.audioTarget), style = style)
                    Text("underruns %d".format(st.underruns), style = style)
                }
            }
        }

        // Fast-forward indicator
        AnimatedVisibility(a.ffActive, Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(10.dp), enter = fadeIn(), exit = fadeOut()) {
            Row(
                Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f), RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.FastForward, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(4.dp))
                Text(ffLabel(s.fastForwardSpeed), color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }

        if (a.editing) EditToolbar(a)

        AnimatedVisibility(a.menuOpen && a.slotMode == null, enter = fadeIn(), exit = fadeOut()) {
            QuickMenu(a)
        }

        a.slotMode?.let { mode -> SlotSheet(a, mode) }

        // Transient message
        val msg = a.message
        LaunchedEffect(msg) {
            if (msg != null) {
                delay(2200)
                a.message = null
            }
        }
        AnimatedVisibility(
            msg != null,
            Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = 24.dp),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.inverseSurface) {
                Text(msg ?: "", Modifier.padding(horizontal = 18.dp, vertical = 10.dp), color = MaterialTheme.colorScheme.inverseOnSurface)
            }
        }

        a.fatalError?.let { err ->
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Can't start ${a.game.displayTitle}") },
                text = { Text("$err\n\nYou can re-select the file from the game's info page in the library.") },
                confirmButton = { TextButton(onClick = { a.exit() }) { Text("Back to library") } },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickMenu(a: GameActivity) {
    val s = a.settings
    var confirmReset by remember { mutableStateOf(false) }
    Box(
        Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(remember { MutableInteractionSource() }, null) { a.closeMenu() },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.safeDrawingPadding().padding(16.dp).widthIn(max = 560.dp)
                .clickable(remember { MutableInteractionSource() }, null) {},
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
                Text(a.game.displayTitle, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "Paused · ${Format.playtime(a.game.playtimeMs)} played",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { a.closeMenu() }, Modifier.fillMaxWidth().height(52.dp)) {
                    Icon(Icons.Default.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Resume", fontSize = 16.sp)
                }
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MenuTile(Icons.Default.Save, "Save state") { a.slotMode = SlotMode.SAVE }
                    MenuTile(Icons.Default.FileOpen, "Load state") { a.slotMode = SlotMode.LOAD }
                    MenuTile(Icons.Default.Download, "Quick save\n${slotName(s.defaultSlot)}") { a.saveState(s.defaultSlot) }
                    MenuTile(Icons.Default.Upload, "Quick load\n${slotName(s.defaultSlot)}") { a.loadState(s.defaultSlot) }
                    MenuTile(Icons.Default.RestartAlt, "Reset") { confirmReset = true }
                    MenuTile(if (s.muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, if (s.muted) "Unmute" else "Mute") { a.toggleMute() }
                    MenuTile(Icons.Default.Edit, "Edit controls") { a.startEditing() }
                    MenuTile(Icons.Default.Settings, "Settings") { a.openSettings() }
                    MenuTile(Icons.AutoMirrored.Filled.ExitToApp, "Exit game") { a.exit() }
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.FastForward, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Fast-forward", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text(if (a.ffActive) "On" else "Off", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = a.ffActive, onCheckedChange = { a.toggleFastForward() })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FF_SPEEDS.forEach { sp ->
                        FilterChip(selected = s.fastForwardSpeed == sp, onClick = { a.setFastForwardSpeed(sp) }, label = { Text(ffLabel(sp)) })
                    }
                }
            }
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset game?") },
            text = { Text("The game restarts from boot. Unsaved progress is lost; your in-game save file is kept.") },
            confirmButton = { TextButton(onClick = { confirmReset = false; a.reset() }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MenuTile(icon: ImageVector, label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.width(118.dp).height(78.dp),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.labelLarge, lineHeight = 15.sp, maxLines = 2)
        }
    }
}

@Composable
private fun SlotSheet(a: GameActivity, mode: SlotMode) {
    val storage = MicroBoyApp.instance.storage
    val slots = remember(a.slotsVersion, a.game.id) { storage.stateSlots(a.game.id) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { a.slotMode = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Column {
                    Text(if (mode == SlotMode.SAVE) "Save state" else "Load state", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Save states are snapshots of the emulator. Your in-game save is separate and always kept.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            SaveSlotGrid(
                slots = slots,
                mode = mode,
                defaultSlot = a.settings.defaultSlot,
                actions = SlotActions(
                    onSave = { a.saveState(it) },
                    onLoad = { a.loadState(it) },
                    onDelete = { a.deleteState(it) },
                    onExport = { a.exportState(it) },
                    onImport = { a.importState(it) },
                ),
            )
        }
    }
}

@Composable
private fun EditToolbar(a: GameActivity) {
    val sel = a.selectedControl
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f),
            modifier = Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(8.dp).widthIn(max = 460.dp),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    if (sel == null) "Drag a control to move it. Pinch to resize." else "Editing ${sel.label}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Default,
                )
                if (sel != null) {
                    @Suppress("UNUSED_VARIABLE") val v = a.layoutVersion
                    val p = a.controller.layout[sel]
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Size", Modifier.width(44.dp))
                        Slider(
                            value = p.scale,
                            onValueChange = { v -> a.controller.updateSelected { it.copy(scale = v) } },
                            valueRange = 0.5f..2.5f,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Visible", Modifier.weight(1f))
                        Switch(checked = p.visible, onCheckedChange = { v -> a.controller.updateSelected { it.copy(visible = v) } })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { a.resetLayout() }) { Text("Reset layout") }
                    Button(onClick = { a.finishEditing() }) { Text("Done") }
                }
            }
        }
    }
}
