package com.okayanshul.docaction.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A 4dp grid, named by intent rather than by size so usage stays consistent.
 *
 * Nothing in the app is ever indented to a value outside this list — that is what keeps
 * spacing from drifting component by component.
 */
@Immutable
data class Space(
    /** Icon-to-text nudges. */
    val hairline: Dp = 2.dp,
    /** Within a line. */
    val tight: Dp = 4.dp,
    /** Between related lines. */
    val snug: Dp = 8.dp,
    /** The standard gap, and every screen's horizontal padding. */
    val default: Dp = 16.dp,
    /** Between logical groups. */
    val section: Dp = 24.dp,
    /** Above a headline, around empty states. */
    val major: Dp = 40.dp,
)

/**
 * Four radii. The primary call-to-action is the only element that uses [xl], which is what
 * lets it read as *the* button without colour or size gimmicks.
 */
@Immutable
data class Radius(
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 20.dp,
    val xl: Dp = 28.dp,
)

val LocalSpace = staticCompositionLocalOf { Space() }
val LocalRadius = staticCompositionLocalOf { Radius() }

internal fun materialShapes(radius: Radius) = Shapes(
    extraSmall = RoundedCornerShape(radius.sm),
    small = RoundedCornerShape(radius.sm),
    medium = RoundedCornerShape(radius.md),
    large = RoundedCornerShape(radius.lg),
    extraLarge = RoundedCornerShape(radius.xl),
)

/** Minimum touch target. Applies to the inline actions on a dense review row too. */
val MinTouchTarget: Dp = 48.dp

/**
 * The widest a column of content is allowed to get.
 *
 * Typography's oldest rule: past roughly seventy characters the eye loses the start of the
 * next line. On a landscape phone or a tablet an unconstrained column also strands a row's
 * checkbox and its confidence badge at opposite ends of the screen, which is a worse crime
 * than the wasted space — the two things the user has to compare stop being comparable.
 */
val ReadableWidth: Dp = 640.dp
