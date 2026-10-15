package dev.m4s4t0.microboy.ui.settings

import android.net.Uri
import android.os.Build
import android.view.KeyEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.m4s4t0.microboy.BuildConfig
import dev.m4s4t0.microboy.MicroBoyApp
import dev.m4s4t0.microboy.data.AppSettings
import dev.m4s4t0.microboy.data.FastForwardMode
import dev.m4s4t0.microboy.data.OrientationMode
import dev.m4s4t0.microboy.data.ScaleMode
import dev.m4s4t0.microboy.data.ScreenFilter
import dev.m4s4t0.microboy.ui.theme.Palettes
import dev.m4s4t0.microboy.input.ControlLayout
import dev.m4s4t0.microboy.input.GamepadMapping
import dev.m4s4t0.microboy.input.PadAction
import dev.m4s4t0.microboy.ui.common.Feedback
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import dev.m4s4t0.microboy.ui.game.FF_SPEEDS
import dev.m4s4t0.microboy.ui.game.ffLabel
import dev.m4s4t0.microboy.ui.game.SLOW_SPEEDS
import dev.m4s4t0.microboy.ui.game.slowLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val app = MicroBoyApp.instance
    val s by app.settings.settings.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    val set: Setter = { t -> scope.launch { app.settings.update { it.t() } } }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                scrollBehavior = scroll,
            )
        },
    ) { pad ->
        val cur = s ?: return@Scaffold
        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            themeSection(cur, set)
            fastForwardSection(cur, set)
            displaySection(cur, set)
            controlsSection(cur, set)
            item { MappingSection(cur, set) }
            audioSection(cur, set)
            savesSection(cur, set)
            item { BiosSection(cur, set) }
            overlaySection(cur, set)
            appearanceSection(cur, set)
            item { AboutSection(onReset = { scope.launch { app.settings.reset() } }) }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

private typealias Setter = (AppSettings.() -> AppSettings) -> Unit

private fun LazyListScope.header(title: String) = item {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp),
    )
}

@Composable
private fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun SliderRow(title: String, value: Float, range: ClosedFloatingPointRange<Float>, format: (Float) -> String, onChange: (Float) -> Unit) {
    // Local state while dragging so we don't write DataStore on every pixel.
    var local by remember(value) { mutableStateOf(value) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(format(local), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = local, onValueChange = { local = it }, onValueChangeFinished = { onChange(local) }, valueRange = range)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceRow(title: String, options: List<T>, selected: T, label: (T) -> String, subtitle: String? = null, onSelect: (T) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { o -> FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) }) }
        }
    }
}

private fun pct(v: Float) = "${(v * 100).toInt()}%"

private fun LazyListScope.fastForwardSection(s: AppSettings, set: Setter) {
    header("Speed")
    item {
        ChoiceRow("Fast-forward speed", FF_SPEEDS, s.fastForwardSpeed, ::ffLabel, "Emulation really runs this many times faster; “Max” runs as fast as the device allows. Audio is muted while fast-forwarding.") {
            set { copy(fastForwardSpeed = it) }
        }
    }
    item {
        ChoiceRow("Fast-forward button", FastForwardMode.entries, s.fastForwardMode, { if (it == FastForwardMode.HOLD) "Hold" else "Tap to toggle" }) {
            set { copy(fastForwardMode = it) }
        }
    }
    item {
        ChoiceRow("Slow motion speed", SLOW_SPEEDS, s.slowMotionSpeed, ::slowLabel, "Turn slow motion on from the pause menu, a controller button, or the ½× touch button (show it in Edit controls). Audio slows down with the game.") {
            set { copy(slowMotionSpeed = it) }
        }
    }
    item {
        ChoiceRow("Rewind", REWIND_CHOICES, s.rewindSeconds, { if (it == 0) "Off" else "$it s" }, "Hold ◀◀ to go back in time. Longer history uses more memory. Rewinding never undoes your in-game save.") {
            set { copy(rewindSeconds = it) }
        }
    }
}

private val REWIND_CHOICES = listOf(0, 10, 20, 30, 60)

private fun LazyListScope.displaySection(s: AppSettings, set: Setter) {
    header("Display")
    item {
        ChoiceRow("Scaling", ScaleMode.entries, s.scaleMode, {
            when (it) { ScaleMode.FIT -> "Fit (keep aspect)"; ScaleMode.INTEGER -> "Integer (crisp)"; ScaleMode.STRETCH -> "Stretch" }
        }) { set { copy(scaleMode = it) } }
    }
    item { SliderRow("Screen size", s.screenSize, 0.5f..1f, ::pct) { set { copy(screenSize = it) } } }
    item { SwitchRow("Smoothing", "Bilinear filtering instead of sharp pixels", s.smoothing) { set { copy(smoothing = it) } } }
    item {
        ChoiceRow("Filter", ScreenFilter.entries, s.filter, {
            when (it) { ScreenFilter.NONE -> "None"; ScreenFilter.SCANLINES -> "Scanlines"; ScreenFilter.LCD_GRID -> "LCD grid"; ScreenFilter.CRT -> "CRT" }
        }) { set { copy(filter = it) } }
    }
    if (s.filter != ScreenFilter.NONE) item { SliderRow("Filter strength", s.filterStrength, 0.1f..1f, ::pct) { set { copy(filterStrength = it) } } }
    item { SliderRow("Brightness", s.brightness, 0.5f..1.5f, ::pct) { set { copy(brightness = it) } } }
    item { SliderRow("Saturation", s.saturation, 0f..1.5f, ::pct) { set { copy(saturation = it) } } }
    item { SwitchRow("GBA color correction", "Approximates the colors of the original LCD", s.colorCorrection) { set { copy(colorCorrection = it) } } }
    item {
        ChoiceRow("Orientation", OrientationMode.entries, s.orientation, { it.name.lowercase().replaceFirstChar(Char::uppercase) }) {
            set { copy(orientation = it) }
        }
    }
}

private fun LazyListScope.controlsSection(s: AppSettings, set: Setter) {
    header("Touch controls")
    item { SliderRow("Opacity", s.controlsOpacity, 0.1f..1f, ::pct) { set { copy(controlsOpacity = it) } } }
    item { SliderRow("Size", s.controlsScale, 0.6f..1.6f, ::pct) { set { copy(controlsScale = it) } } }
    item { SwitchRow("Haptic feedback", checked = s.haptics) { set { copy(haptics = it) } } }
    item { SwitchRow("Rumble", "Vibrates when a game with a rumble cartridge uses it (Drill Dozer, WarioWare: Twisted)", s.rumble) { set { copy(rumble = it) } } }
    item {
        SwitchRow("Hide when a controller is connected", "Only the menu button stays visible", s.hideControlsWithGamepad) {
            set { copy(hideControlsWithGamepad = it) }
        }
    }
    item {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                "To move or resize buttons, open a game and choose “Edit controls” in the menu. Portrait and landscape layouts are saved separately.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = {
                set { copy(layoutPortrait = ControlLayout.defaultPortrait(), layoutLandscape = ControlLayout.defaultLandscape()) }
            }) { Text("Reset both layouts") }
        }
    }
}

@Composable
private fun MappingSection(s: AppSettings, set: Setter) {
    var capturing by remember { mutableStateOf<PadAction?>(null) }
    Column {
        Text(
            "Controller mapping",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp),
        )
        Text(
            "Bluetooth and USB controllers work automatically. Tap an action to bind a different button.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        PadAction.entries.forEach { action ->
            val keys = s.gamepadMapping.keysFor(action)
            Row(
                Modifier.fillMaxWidth().clickable { capturing = action }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(action.label, Modifier.weight(1f))
                Text(
                    if (keys.isEmpty()) "Unbound" else keys.joinToString(", ") { GamepadMapping.keyName(it) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TextButton(onClick = { set { copy(gamepadMapping = GamepadMapping.default()) } }, Modifier.padding(horizontal = 8.dp)) {
            Text("Restore default mapping")
        }
    }
    capturing?.let { action ->
        val focus = remember { FocusRequester() }
        AlertDialog(
            onDismissRequest = { capturing = null },
            title = { Text("Bind “${action.label}”") },
            text = {
                Box(
                    Modifier.fillMaxWidth().height(80.dp).focusRequester(focus).focusable()
                        .onPreviewKeyEvent { ev ->
                            val e = ev.nativeKeyEvent
                            if (e.keyCode == KeyEvent.KEYCODE_BACK) return@onPreviewKeyEvent false
                            if (e.action == KeyEvent.ACTION_DOWN) {
                                set { copy(gamepadMapping = gamepadMapping.bind(action, e.keyCode)) }
                                capturing = null
                            }
                            true
                        },
                    contentAlignment = Alignment.Center,
                ) { Text("Press a button on your controller…") }
                LaunchedEffect(Unit) { focus.requestFocus() }
            },
            confirmButton = {
                TextButton(onClick = { set { copy(gamepadMapping = gamepadMapping.clear(action)) }; capturing = null }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { capturing = null }) { Text("Cancel") } },
        )
    }
}

private fun LazyListScope.audioSection(s: AppSettings, set: Setter) {
    header("Audio")
    item { SwitchRow("Enable audio", "Turning audio off also stops the audio device to save battery", s.audioEnabled) { set { copy(audioEnabled = it) } } }
    item { SwitchRow("Mute", checked = s.muted, enabled = s.audioEnabled) { set { copy(muted = it) } } }
    item { SliderRow("Volume", s.volume, 0f..1f, ::pct) { set { copy(volume = it) } } }
}

private fun LazyListScope.savesSection(s: AppSettings, set: Setter) {
    header("Saves")
    item {
        Text(
            "In-game saves (SRAM, Flash, EEPROM) are written automatically, exactly like a real cartridge. Save states are separate snapshots you create from the menu.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
    item { SwitchRow("Auto-save state on exit", "Saved to the Auto slot whenever you leave or switch apps", s.autoSaveState) { set { copy(autoSaveState = it) } } }
    item { SwitchRow("Resume from auto-save", "Continue exactly where you left off when launching a game", s.autoLoadState) { set { copy(autoLoadState = it) } } }
    item { ChoiceRow("Quick save slot", (1..9).toList(), s.defaultSlot, { "$it" }) { set { copy(defaultSlot = it) } } }
}

@Composable
private fun BiosSection(s: AppSettings, set: Setter) {
    val ctx = LocalContext.current
    val storage = MicroBoyApp.instance.storage
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val hasBios = remember(version) { storage.biosFile.exists() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            error = withContext(Dispatchers.IO) {
                val bytes = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                when {
                    bytes == null -> "Couldn't read that file."
                    bytes.size != 16384 -> "A GBA BIOS is exactly 16 KB; that file is ${bytes.size} bytes."
                    else -> {
                        val tmp = File(storage.biosFile.path + ".tmp")
                        tmp.writeBytes(bytes)
                        tmp.renameTo(storage.biosFile)
                        null
                    }
                }
            }
            if (error == null) set { copy(useBios = true) }
            version++
        }
    }
    Column {
        Text(
            "BIOS",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp),
        )
        Text(
            "Not required: MicroBoy includes mGBA's open-source BIOS replacement. You may provide a BIOS dumped from your own console for maximum accuracy. No BIOS is included with the app.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        SwitchRow("Use BIOS file", if (hasBios) "gba_bios.bin installed" else "No BIOS file selected", s.useBios && hasBios, enabled = hasBios) {
            set { copy(useBios = it) }
        }
        SwitchRow("Skip BIOS intro", checked = s.skipBiosIntro, enabled = s.useBios && hasBios) { set { copy(skipBiosIntro = it) } }
        Row(Modifier.padding(horizontal = 8.dp)) {
            TextButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text(if (hasBios) "Replace BIOS…" else "Select BIOS file…") }
            if (hasBios) TextButton(onClick = { storage.biosFile.delete(); set { copy(useBios = false) }; version++ }) { Text("Remove") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
    }
}

private fun LazyListScope.overlaySection(s: AppSettings, set: Setter) {
    header("Performance overlay")
    item { SwitchRow("Show frame rate", checked = s.showFps) { set { copy(showFps = it) } } }
    item { SwitchRow("Show detailed statistics", "Frame time, audio buffer and underruns", s.showStats) { set { copy(showStats = it) } } }
}

private fun LazyListScope.themeSection(s: AppSettings, set: Setter) {
    header("Color theme")
    item {
        Text(
            "Colors the whole app and your on-screen controls.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
        )
        ThemeGallery(s.themeId, s.customTheme) { id -> set { copy(themeId = id) } }
    }
    if (s.themeId == Palettes.CUSTOM) item {
        CustomThemeEditor(s.customTheme) { seeds -> set { copy(customTheme = seeds) } }
    }
}

private fun LazyListScope.appearanceSection(s: AppSettings, set: Setter) {
    header("Library")
    item {
        SwitchRow("Download box art", "Looks up covers by file name in the libretro thumbnail database (uses the internet)", s.downloadBoxArt) {
            set { copy(downloadBoxArt = it) }
        }
    }
    item {
        SwitchRow("Copy ROMs into app storage", "Off: games stay where they are and are read in place", s.importRoms) {
            set { copy(importRoms = it) }
        }
    }
}

@Composable
private fun AboutSection(onReset: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.padding(top = 24.dp)) {
        HorizontalDivider()
        Column(Modifier.padding(16.dp)) {
            Text("MicroBoy ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
            Text("Developed by M4S4T0", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Emulation by mGBA 0.10.5 © Jeffrey Pfau and contributors, used under the Mozilla Public License 2.0. " +
                    "Source: github.com/mgba-emu/mgba.\n\nMicroBoy doesn't include any games or BIOS files. Only play games you legally own.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Found a bug or have an idea? Feedback goes straight to the developer. The testing guide lists what to try.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val context = LocalContext.current
            Row(Modifier.padding(top = 8.dp)) {
                Button(onClick = { Feedback.email(context) }) { Text("Send feedback") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { Feedback.openGuide(context) }) { Text("Testing guide") }
            }
            TextButton(onClick = { confirm = true }, Modifier.padding(top = 8.dp)) { Text("Reset all settings") }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Reset all settings?") },
            text = { Text("Display, controls, mappings and other preferences return to defaults. Games and saves are not affected.") },
            confirmButton = { TextButton(onClick = { confirm = false; onReset() }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}
