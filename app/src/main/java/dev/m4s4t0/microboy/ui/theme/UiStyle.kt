package dev.m4s4t0.microboy.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Corner style for every panel, button and dialog in the app. */
enum class UiCorners(val label: String) {
    ROUNDED("Rounded"),
    ROUNDER("Extra round"),
    SOFT("Soft"),
    SQUARE("Square"),
    CUT("Cut"),
}

/** How panels stand out from the background. */
enum class UiShadows(val label: String) {
    NONE("None"),
    OUTLINE("Outline"),
    SOFT("Soft shadow"),
    STRONG("Strong shadow"),
}

/** The app's shape language. MicroBoy's default is rounded with no shadows. */
data class UiStyle(val corners: UiCorners = UiCorners.ROUNDED, val shadows: UiShadows = UiShadows.NONE) {
    /** A panel shape whose design radius is [radius] in the default (rounded) style. */
    fun shape(radius: Dp): CornerBasedShape = when (corners) {
        UiCorners.ROUNDED -> RoundedCornerShape(radius)
        UiCorners.ROUNDER -> RoundedCornerShape(radius * 1.6f)
        UiCorners.SOFT -> RoundedCornerShape(radius * 0.4f)
        UiCorners.SQUARE -> RoundedCornerShape(0.dp)
        UiCorners.CUT -> CutCornerShape(radius * 0.45f)
    }

    /** Shape for pills and badges (fully round in the default style). */
    val pill: CornerBasedShape
        get() = when (corners) {
            UiCorners.ROUNDED, UiCorners.ROUNDER -> RoundedCornerShape(50)
            UiCorners.SOFT -> RoundedCornerShape(6.dp)
            UiCorners.SQUARE -> RoundedCornerShape(0.dp)
            UiCorners.CUT -> CutCornerShape(6.dp)
        }

    /** Shadow depth for raised panels. */
    val elevation: Dp
        get() = when (shadows) {
            UiShadows.NONE, UiShadows.OUTLINE -> 0.dp
            UiShadows.SOFT -> 4.dp
            UiShadows.STRONG -> 12.dp
        }

    /** Material's shape scale, so built-in buttons, chips, dialogs and fields follow the style. */
    fun materialShapes() = Shapes(
        extraSmall = shape(4.dp),
        small = shape(8.dp),
        medium = shape(12.dp),
        large = shape(16.dp),
        extraLarge = shape(28.dp),
    )
}

val LocalUiStyle = staticCompositionLocalOf { UiStyle() }

/** Panel shape in the current style; [radius] is the size in the default rounded style. */
@Composable
fun uiShape(radius: Dp): Shape = LocalUiStyle.current.shape(radius)

@Composable
fun uiPill(): Shape = LocalUiStyle.current.pill

@Composable
fun uiElevation(): Dp = LocalUiStyle.current.elevation

/** Thin outline for panels in the Outline style; null otherwise. */
@Composable
fun uiBorder(): BorderStroke? =
    if (LocalUiStyle.current.shadows == UiShadows.OUTLINE) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null
