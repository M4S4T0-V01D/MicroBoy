package dev.m4s4t0.microboy.ui.theme

import androidx.compose.runtime.CompositionLocalProvider
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.m4s4t0.microboy.data.AppSettings

private val AppTypography = Typography().let { t ->
    t.copy(
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineLarge = t.headlineLarge.copy(fontWeight = FontWeight.Black, letterSpacing = (-1).sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 0.4.sp),
    )
}

/** Applies the user's chosen preset, custom colors, or wallpaper colors. */
@Composable
fun MicroBoyTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val colors = if (settings.themeId == Palettes.WALLPAPER && Build.VERSION.SDK_INT >= 31) {
        if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else {
        Palettes.scheme(Palettes.seedsFor(settings.themeId, settings.customTheme))
    }
    val style = UiStyle(settings.uiCorners, settings.uiShadows)
    CompositionLocalProvider(LocalUiStyle provides style) {
        MaterialTheme(colorScheme = colors, typography = AppTypography, shapes = style.materialShapes(), content = content)
    }
}
