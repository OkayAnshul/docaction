package com.okayanshul.docaction.core.designsystem

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

/**
 * Ink mapped onto Material's roles.
 *
 * `primary` is ink rather than a hue, so the primary button is a filled block of near-black
 * on paper. `outline` and `outlineVariant` are kept distinct — a control's outline has to
 * clear 3:1 to satisfy WCAG 1.4.11, while a row separator is decoration and must stay quiet.
 */
private fun schemeFor(ink: InkColours, dark: Boolean) = with(ink) {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    base.copy(
        primary = this.ink,
        onPrimary = surface,
        secondary = accent,
        onSecondary = surface,
        // The navigation bar's selected-item pill. Left to Material it becomes a wallpaper
        // tint — a lavender lozenge in a palette whose whole premise is that colour means
        // something. A quiet neutral instead, with the accent carried by the label and icon,
        // so selection still reads by shape *and* colour rather than by hue alone.
        secondaryContainer = if (dark) surfaceRaised else surfaceSunken,
        onSecondaryContainer = accent,
        surface = surface,
        onSurface = this.ink,
        surfaceVariant = surfaceSunken,
        onSurfaceVariant = inkMuted,
        surfaceContainer = surfaceRaised,
        surfaceContainerHigh = surfaceRaised,
        background = surface,
        onBackground = this.ink,
        outline = border,
        outlineVariant = hairline,
    )
}

private val LightScheme = schemeFor(InkColours.Light, dark = false)

/** A true dark on a warm axis, not an inverted grey. Elevation is expressed by tonal lift. */
private val DarkScheme = schemeFor(InkColours.Dark, dark = true)

/**
 * The app's visual language.
 *
 * **Dynamic colour retints the accent only.** An achromatic identity and wallpaper-tinted
 * surfaces are not compatible: the whole premise here is that chroma means something, and
 * letting a wallpaper wash the page in colour would break that on every screen at once. So
 * the user's palette shows up exactly where it is harmless and genuinely pleasant — the
 * selection, the focus ring, the active nav item — while paper stays paper, ink stays ink,
 * and the confidence colours never take part at all.
 *
 * Material's dynamic `primary` is tone 40 in light and tone 80 in dark, so an accent taken
 * from it clears the 3:1 a focus indicator needs against either surface, whatever the
 * wallpaper is.
 */
@Composable
fun DocActionTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColour: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val base = if (darkTheme) InkColours.Dark else InkColours.Light

    val ink = if (dynamicColour && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val wallpaper =
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        base.copy(accent = wallpaper.primary)
    } else {
        base
    }

    val scheme = if (ink === base) {
        if (darkTheme) DarkScheme else LightScheme
    } else {
        schemeFor(ink, darkTheme)
    }

    val type = DocActionType.Default
    val radius = Radius()

    CompositionLocalProvider(
        LocalConfidenceColours provides if (darkTheme) ConfidenceColours.Dark else ConfidenceColours.Light,
        LocalInkColours provides ink,
        LocalType provides type,
        LocalSpace provides Space(),
        LocalRadius provides radius,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = materialTypography(type),
            shapes = materialShapes(radius),
            content = content,
        )
    }
}

/**
 * Centres content and stops it growing past a readable measure.
 *
 * Applied once around the whole flow rather than screen by screen, so a screen added later
 * cannot forget it. Below [ReadableWidth] this is exactly a full-width box, which is the
 * case on every phone held upright.
 */
@Composable
fun ReadableColumn(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(modifier = Modifier.widthIn(max = ReadableWidth).fillMaxHeight()) {
            content()
        }
    }
}

/** Shorthand accessors, so feature code never reaches for raw values. */
object DocAction {
    val type: DocActionType
        @Composable get() = LocalType.current

    val space: Space
        @Composable get() = LocalSpace.current

    val radius: Radius
        @Composable get() = LocalRadius.current

    val confidence: ConfidenceColours
        @Composable get() = LocalConfidenceColours.current

    val ink: InkColours
        @Composable get() = LocalInkColours.current
}
