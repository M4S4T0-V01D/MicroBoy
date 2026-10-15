package dev.m4s4t0.microboy.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import kotlinx.serialization.Serializable

/** Three seed colors from which a whole UI + controller color scheme is derived. */
@Serializable
data class ThemeSeeds(val primary: Int, val accent: Int, val background: Int) {
    val isDark: Boolean get() = Color(background).luminance() < 0.4f
}

/** A named, ready-made theme. */
data class ThemePreset(val id: String, val name: String, val seeds: ThemeSeeds)

/** Colors used by the on-screen controller. */
data class ControllerColors(val body: Int, val button: Int, val accent: Int, val label: Int)

object Palettes {
    const val CUSTOM = "custom"
    const val WALLPAPER = "wallpaper"

    private fun p(id: String, name: String, primary: Long, accent: Long, bg: Long) =
        ThemePreset(id, name, ThemeSeeds(primary.toInt(), accent.toInt(), bg.toInt()))

    val presets = listOf(
        p("midnight", "Midnight", 0xFFB39DFF, 0xFFFF8A80, 0xFF14121F),
        p("glacier", "Glacier", 0xFF7FD8FF, 0xFFB8F2E6, 0xFF0E1A24),
        p("arcade", "Arcade", 0xFFFF4FD8, 0xFF3CF0FF, 0xFF07060C),
        p("ember", "Ember", 0xFFFF9248, 0xFFFFD166, 0xFF1C110D),
        p("lime", "Lime Soda", 0xFFC6F432, 0xFF4FE3A1, 0xFF111A0E),
        p("ocean", "Deep Sea", 0xFF4FC3F7, 0xFF7C83FF, 0xFF0A1428),
        p("pocket", "Pocket LCD", 0xFF9BBC0F, 0xFF8BAC0F, 0xFF0F380F),
        p("oled", "OLED Black", 0xFFE0E0E0, 0xFFB39DFF, 0xFF000000),
        p("sakura", "Sakura", 0xFFD9467A, 0xFF7B5EA7, 0xFFFFF4F7),
        p("paper", "Paper", 0xFF2F4FA2, 0xFFD9822B, 0xFFF7F3EA),
        p("stone", "Stone", 0xFF6A4BC4, 0xFFB0305C, 0xFFE6E4E0),
    )

    val defaultCustom = ThemeSeeds(0xFF00E5A0.toInt(), 0xFFFF6E40.toInt(), 0xFF101418.toInt())

    fun seedsFor(id: String, custom: ThemeSeeds): ThemeSeeds =
        if (id == CUSTOM) custom else (presets.firstOrNull { it.id == id } ?: presets.first()).seeds

    private fun onColor(c: Color): Color = if (c.luminance() > 0.45f) Color(0xFF111111) else Color.White

    private fun mix(a: Color, b: Color, t: Float) = lerp(a, b, t)

    /** Builds a complete Material 3 scheme from three seeds. */
    fun scheme(seeds: ThemeSeeds): ColorScheme {
        val primary = Color(seeds.primary)
        val accent = Color(seeds.accent)
        val bg = Color(seeds.background)
        val dark = seeds.isDark
        val ink = if (dark) mix(Color.White, primary, 0.08f) else mix(Color(0xFF15131A), primary, 0.10f)
        val inkDim = ink.copy(alpha = 0.72f).compositeOver(bg)
        val step = if (dark) 0.055f else 0.045f
        fun container(n: Int) = mix(bg, if (dark) mix(primary, Color.White, 0.15f) else mix(primary, Color.Black, 0.05f), step * n)
        val primaryContainer = mix(bg, primary, if (dark) 0.32f else 0.22f)
        val accentContainer = mix(bg, accent, if (dark) 0.30f else 0.22f)
        return if (dark) {
            darkColorScheme(
                primary = primary, onPrimary = onColor(primary),
                primaryContainer = primaryContainer, onPrimaryContainer = mix(Color.White, primary, 0.15f),
                secondary = accent, onSecondary = onColor(accent),
                secondaryContainer = accentContainer, onSecondaryContainer = mix(Color.White, accent, 0.15f),
                tertiary = mix(primary, accent, 0.5f),
                background = bg, onBackground = ink, surface = bg, onSurface = ink,
                surfaceVariant = container(3), onSurfaceVariant = inkDim,
                surfaceContainerLowest = mix(bg, Color.Black, 0.3f),
                surfaceContainerLow = container(1), surfaceContainer = container(2),
                surfaceContainerHigh = container(3), surfaceContainerHighest = container(4),
                inverseSurface = ink, inverseOnSurface = bg,
                outline = mix(bg, ink, 0.45f), outlineVariant = mix(bg, ink, 0.18f),
            )
        } else {
            lightColorScheme(
                primary = primary, onPrimary = onColor(primary),
                primaryContainer = primaryContainer, onPrimaryContainer = mix(Color.Black, primary, 0.35f),
                secondary = accent, onSecondary = onColor(accent),
                secondaryContainer = accentContainer, onSecondaryContainer = mix(Color.Black, accent, 0.35f),
                tertiary = mix(primary, accent, 0.5f),
                background = bg, onBackground = ink, surface = bg, onSurface = ink,
                surfaceVariant = container(3), onSurfaceVariant = inkDim,
                surfaceContainerLowest = mix(bg, Color.White, 0.6f),
                surfaceContainerLow = container(1), surfaceContainer = container(2),
                surfaceContainerHigh = container(3), surfaceContainerHighest = container(4),
                inverseSurface = ink, inverseOnSurface = bg,
                outline = mix(bg, ink, 0.45f), outlineVariant = mix(bg, ink, 0.18f),
            )
        }
    }

    /** Controller colors: dark translucent body, accent-tinted face buttons. */
    fun controller(seeds: ThemeSeeds): ControllerColors {
        val bg = Color(seeds.background)
        val body = if (seeds.isDark) mix(bg, Color(seeds.primary), 0.12f) else mix(Color(0xFF1A1820), Color(seeds.primary), 0.15f)
        return ControllerColors(
            body = body.toArgb(),
            button = mix(body, Color(seeds.accent), 0.55f).toArgb(),
            accent = Color(seeds.primary).toArgb(),
            label = Color.White.toArgb(),
        )
    }
}
