package dev.m4s4t0.prism.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.m4s4t0.prism.data.ThemeMode

// "Prism" palette: indigo shell, lavender and coral accents — an original
// scheme loosely evoking translucent handheld plastics.
val Lavender = Color(0xFFB39DFF)
val Coral = Color(0xFFFF8A80)
val Mint = Color(0xFF7FE0C2)
val Indigo900 = Color(0xFF14121F)
val Indigo800 = Color(0xFF1E1B2E)
val Indigo700 = Color(0xFF2A2640)
val Indigo600 = Color(0xFF3A3556)

private val DarkColors = darkColorScheme(
    primary = Lavender,
    onPrimary = Color(0xFF241A4D),
    primaryContainer = Color(0xFF3B2F73),
    onPrimaryContainer = Color(0xFFE6DEFF),
    secondary = Coral,
    onSecondary = Color(0xFF4A0E0B),
    secondaryContainer = Color(0xFF5C2A27),
    onSecondaryContainer = Color(0xFFFFDAD5),
    tertiary = Mint,
    background = Indigo900,
    onBackground = Color(0xFFE7E3F3),
    surface = Indigo900,
    onSurface = Color(0xFFE7E3F3),
    surfaceVariant = Indigo700,
    onSurfaceVariant = Color(0xFFC9C3DC),
    surfaceContainer = Indigo800,
    surfaceContainerHigh = Indigo700,
    surfaceContainerHighest = Indigo600,
    surfaceContainerLow = Color(0xFF191626),
    outline = Color(0xFF8E88A6),
    outlineVariant = Color(0xFF443F5C),
)

private val BlackColors = DarkColors.copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceContainerLow = Color(0xFF0B0A10),
    surfaceContainer = Color(0xFF121019),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF5B47B8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6DEFF),
    onPrimaryContainer = Color(0xFF1A0063),
    secondary = Color(0xFFB3261E),
    secondaryContainer = Color(0xFFFFDAD5),
    tertiary = Color(0xFF006B55),
    background = Color(0xFFFBF8FF),
    surface = Color(0xFFFBF8FF),
    surfaceVariant = Color(0xFFE6E0F3),
    surfaceContainer = Color(0xFFF1ECFA),
    surfaceContainerHigh = Color(0xFFEAE5F5),
    surfaceContainerHighest = Color(0xFFE3DEEF),
    surfaceContainerLow = Color(0xFFF6F2FD),
)

private val AppTypography = Typography().let { t ->
    t.copy(
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 0.4.sp),
    )
}

@Composable
fun PrismTheme(mode: ThemeMode = ThemeMode.SYSTEM, dynamic: Boolean = false, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.BLACK -> true
    }
    val ctx = LocalContext.current
    val colors = when {
        dynamic && Build.VERSION.SDK_INT >= 31 ->
            if (dark) dynamicDarkColorScheme(ctx).let { if (mode == ThemeMode.BLACK) it.copy(background = Color.Black, surface = Color.Black) else it }
            else dynamicLightColorScheme(ctx)
        mode == ThemeMode.BLACK -> BlackColors
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, typography = AppTypography, content = content)
}
