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

private val LightScheme = lightColorScheme(
    primary = Palette.InkBlue,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    surface = Palette.SurfaceLight,
    onSurface = Palette.OnSurfaceLight,
    background = Palette.SurfaceLight,
    onBackground = Palette.OnSurfaceLight,
)

/** A true dark, not an inverted grey. Elevation is expressed by tonal lift. */
private val DarkScheme = darkColorScheme(
    primary = Palette.InkBlueLight,
    onPrimary = Palette.OnSurfaceLight,
    surface = Palette.SurfaceDark,
    onSurface = Palette.OnSurfaceDark,
    background = Palette.SurfaceDark,
    onBackground = Palette.OnSurfaceDark,
)

/**
 * The app's visual language.
 *
 * Dynamic colour is supported and on by default, because matching the user's device is part
 * of feeling native. It is applied to surfaces and primary only — **the confidence colours
 * never take part**, so a wallpaper can never make "needs attention" resemble "ready".
 */
@Composable
fun DocActionTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColour: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = when {
        dynamicColour && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkScheme
        else -> LightScheme
    }

    val type = DocActionType.Default
    val radius = Radius()

    CompositionLocalProvider(
        LocalConfidenceColours provides if (darkTheme) ConfidenceColours.Dark else ConfidenceColours.Light,
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
}
