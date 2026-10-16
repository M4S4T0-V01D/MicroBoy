package dev.m4s4t0.microboy.ui.settings

import dev.m4s4t0.microboy.ui.theme.uiBorder
import dev.m4s4t0.microboy.ui.theme.uiElevation
import dev.m4s4t0.microboy.ui.theme.uiPill
import dev.m4s4t0.microboy.ui.theme.uiShape
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.m4s4t0.microboy.ui.theme.Palettes
import dev.m4s4t0.microboy.ui.theme.ThemeSeeds
import kotlin.random.Random

/** Horizontal gallery of theme cards, each previewing the palette on a tiny handheld. */
@Composable
fun ThemeGallery(selectedId: String, custom: ThemeSeeds, onSelect: (String) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(Palettes.presets, key = { it.id }) { p ->
            ThemeCard(p.name, p.seeds, selectedId == p.id) { onSelect(p.id) }
        }
        item(key = Palettes.CUSTOM) {
            ThemeCard("Custom", custom, selectedId == Palettes.CUSTOM, badge = Icons.Default.Palette) { onSelect(Palettes.CUSTOM) }
        }
        if (Build.VERSION.SDK_INT >= 31) item(key = Palettes.WALLPAPER) {
            val cs = MaterialTheme.colorScheme
            ThemeCard(
                "Wallpaper",
                ThemeSeeds(cs.primary.toArgb(), cs.secondary.toArgb(), cs.background.toArgb()),
                selectedId == Palettes.WALLPAPER,
                badge = Icons.Default.Wallpaper,
            ) { onSelect(Palettes.WALLPAPER) }
        }
    }
}

@Composable
private fun ThemeCard(name: String, seeds: ThemeSeeds, selected: Boolean, badge: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(96.dp).clickable(onClick = onClick)) {
        Box(
            Modifier.border(
                BorderStroke(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                uiShape(18.dp),
            ).padding(5.dp)
        ) {
            HandheldPreview(seeds, Modifier.width(84.dp).aspectRatio(0.66f))
            if (selected) {
                Box(
                    Modifier.align(Alignment.TopEnd).offset(4.dp, (-4).dp).size(22.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.Check, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onPrimary) }
            } else if (badge != null) {
                Icon(badge, null, Modifier.align(Alignment.TopEnd).padding(2.dp).size(16.dp), tint = Color(seeds.primary))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(name, style = MaterialTheme.typography.labelLarge, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

/** A tiny vertical handheld drawn in the theme's colors. */
@Composable
fun HandheldPreview(seeds: ThemeSeeds, modifier: Modifier = Modifier) {
    val scheme = remember(seeds) { Palettes.scheme(seeds) }
    val ctrl = remember(seeds) { Palettes.controller(seeds) }
    Canvas(modifier.clip(uiShape(14.dp)).background(scheme.surfaceContainerHigh)) {
        val w = size.width
        val h = size.height
        // screen bezel + screen with a primary→accent glow
        drawRoundRect(Color(ctrl.body), Offset(w * 0.1f, h * 0.07f), Size(w * 0.8f, h * 0.42f), androidx.compose.ui.geometry.CornerRadius(w * 0.06f))
        drawRect(
            Brush.linearGradient(listOf(scheme.primary, scheme.secondary), Offset(w * 0.18f, h * 0.12f), Offset(w * 0.82f, h * 0.44f)),
            Offset(w * 0.18f, h * 0.12f), Size(w * 0.64f, h * 0.32f),
        )
        // d-pad
        val cx = w * 0.3f
        val cy = h * 0.68f
        val a = w * 0.055f
        val l = w * 0.15f
        drawRect(Color(ctrl.body), Offset(cx - a, cy - l), Size(a * 2, l * 2))
        drawRect(Color(ctrl.body), Offset(cx - l, cy - a), Size(l * 2, a * 2))
        // A / B
        drawCircle(Color(ctrl.button), w * 0.075f, Offset(w * 0.78f, h * 0.63f))
        drawCircle(Color(ctrl.button), w * 0.075f, Offset(w * 0.62f, h * 0.71f))
        // start / select
        drawRoundRect(scheme.outline, Offset(w * 0.34f, h * 0.88f), Size(w * 0.13f, h * 0.025f), androidx.compose.ui.geometry.CornerRadius(h))
        drawRoundRect(scheme.outline, Offset(w * 0.53f, h * 0.88f), Size(w * 0.13f, h * 0.025f), androidx.compose.ui.geometry.CornerRadius(h))
    }
}

/** Editor for the three custom-theme seeds, with a randomizer. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CustomThemeEditor(seeds: ThemeSeeds, onChange: (ThemeSeeds) -> Unit) {
    var editing by remember { mutableStateOf<String?>(null) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Custom colors", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Light or dark mode follows your background color.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SeedChip("Primary", seeds.primary) { editing = "primary" }
            SeedChip("Accent", seeds.accent) { editing = "accent" }
            SeedChip("Background", seeds.background) { editing = "background" }
            FilledTonalButton(shape = uiPill(), onClick = { onChange(randomSeeds()) }) {
                Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Surprise me")
            }
        }
    }
    editing?.let { which ->
        val initial = when (which) { "primary" -> seeds.primary; "accent" -> seeds.accent; else -> seeds.background }
        ColorPickerDialog(
            title = "${which.replaceFirstChar(Char::uppercase)} color",
            initial = Color(initial),
            onDismiss = { editing = null },
            onPick = { c ->
                val v = c.toArgb()
                onChange(when (which) { "primary" -> seeds.copy(primary = v); "accent" -> seeds.copy(accent = v); else -> seeds.copy(background = v) })
                editing = null
            },
        )
    }
}

@Composable
private fun SeedChip(label: String, color: Int, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = uiPill(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(26.dp).clip(CircleShape).background(Color(color)).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(label)
        }
    }
}

/** A harmonious random palette: split-complementary accent, deep tinted background. */
private fun randomSeeds(): ThemeSeeds {
    val hue = Random.nextFloat() * 360f
    val accentHue = (hue + 150f + Random.nextFloat() * 60f) % 360f
    val light = Random.nextFloat() < 0.25f
    val bg = if (light) Color.hsv(hue, 0.06f, 0.97f) else Color.hsv(hue, 0.35f, 0.07f + Random.nextFloat() * 0.05f)
    val primary = if (light) Color.hsv(hue, 0.7f, 0.55f) else Color.hsv(hue, 0.5f + Random.nextFloat() * 0.3f, 1f)
    val accent = if (light) Color.hsv(accentHue, 0.7f, 0.6f) else Color.hsv(accentHue, 0.55f, 1f)
    return ThemeSeeds(primary.toArgb(), accent.toArgb(), bg.toArgb())
}

private val SWATCHES = listOf(
    0xFFFF5370, 0xFFFF8A80, 0xFFFF9248, 0xFFFFD166, 0xFFC6F432, 0xFF4FE3A1, 0xFF00E5A0, 0xFF3CF0FF,
    0xFF4FC3F7, 0xFF7C83FF, 0xFFB39DFF, 0xFFFF4FD8, 0xFFFFFFFF, 0xFFB0B0B0, 0xFF2A2640, 0xFF000000,
).map { Color(it) }

/** HSV picker: saturation/value square, hue bar, swatches and a hex field. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorPickerDialog(title: String, initial: Color, onDismiss: () -> Unit, onPick: (Color) -> Unit) {
    val hsv = remember {
        FloatArray(3).also { android.graphics.Color.colorToHSV(initial.toArgb(), it) }
    }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var sat by remember { mutableFloatStateOf(hsv[1]) }
    var value by remember { mutableFloatStateOf(hsv[2]) }
    val color = Color.hsv(hue, sat, value)
    var hex by remember(color) { mutableStateOf("%06X".format(color.toArgb() and 0xFFFFFF)) }

    fun setColor(c: Color) {
        val out = FloatArray(3)
        android.graphics.Color.colorToHSV(c.toArgb(), out)
        hue = out[0]; sat = out[1]; value = out[2]
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                // Saturation (x) / value (y) square
                Box(
                    Modifier.fillMaxWidth().aspectRatio(1.6f).clip(uiShape(12.dp))
                        .pointerInput(Unit) {
                            fun upd(o: Offset) {
                                sat = (o.x / size.width).coerceIn(0f, 1f)
                                value = 1f - (o.y / size.height).coerceIn(0f, 1f)
                            }
                            detectTapGestures { upd(it) }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                sat = (change.position.x / size.width).coerceIn(0f, 1f)
                                value = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                            }
                        }
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawRect(Brush.horizontalGradient(listOf(Color.White, Color.hsv(hue, 1f, 1f))))
                        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                        val p = Offset(sat * size.width, (1f - value) * size.height)
                        drawCircle(Color.White, 9.dp.toPx(), p, style = Stroke(3.dp.toPx()))
                        drawCircle(Color.Black.copy(alpha = 0.4f), 11.dp.toPx(), p, style = Stroke(1.dp.toPx()))
                    }
                }
                Spacer(Modifier.height(12.dp))
                // Hue bar
                val density = LocalDensity.current
                Box(
                    Modifier.fillMaxWidth().height(28.dp).clip(uiPill())
                        .pointerInput(Unit) { detectTapGestures { hue = (it.x / size.width * 360f).coerceIn(0f, 359.9f) } }
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ -> hue = (change.position.x / size.width * 360f).coerceIn(0f, 359.9f) }
                        }
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawRect(Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f % 360f, 1f, 1f) }))
                        val x = hue / 360f * size.width
                        drawCircle(Color.White, size.height / 2.4f, Offset(x, size.height / 2), style = Stroke(with(density) { 3.dp.toPx() }))
                    }
                }
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SWATCHES.forEach { c ->
                        Box(
                            Modifier.size(28.dp).clip(CircleShape).background(c)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                .clickable { setColor(c) }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp).clip(uiShape(12.dp)).background(color))
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = hex,
                        onValueChange = { t ->
                            val clean = t.removePrefix("#").uppercase().filter { it in "0123456789ABCDEF" }.take(6)
                            hex = clean
                            if (clean.length == 6) setColor(Color(0xFF000000 or clean.toLong(16)))
                        },
                        prefix = { Text("#") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = { TextButton(shape = uiPill(), onClick = { onPick(color) }) { Text("Use color") } },
        dismissButton = { TextButton(shape = uiPill(), onClick = onDismiss) { Text("Cancel") } },
    )
}
