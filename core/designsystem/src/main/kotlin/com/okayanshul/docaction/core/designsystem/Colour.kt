package com.okayanshul.docaction.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The brand hue is a deep ink-blue: serious, document-adjacent, and deliberately not the
 * purple or electric teal that signals "AI startup". Amber is reserved almost entirely for
 * the attention state, which is what keeps it meaningful.
 */
internal object Palette {
    val InkBlue = Color(0xFF1B3A5C)
    val InkBlueLight = Color(0xFF9FC4E8)
    val Amber = Color(0xFF9A6400)

    val SurfaceLight = Color(0xFFFCFCFD)
    val SurfaceDark = Color(0xFF101418)
    val OnSurfaceLight = Color(0xFF1A1C1E)
    val OnSurfaceDark = Color(0xFFE2E2E6)
}

/**
 * Colours for the four confidence states.
 *
 * A **separate token set** from Material's error and warning roles, because these are not
 * errors. A low-confidence field is a question, not a failure, and painting it error-red
 * would tell the user something went wrong — which is both false and alarming.
 *
 * These are also excluded from dynamic theming. A user's wallpaper must never be able to
 * make "needs attention" look like "ready"; the semantics of these four states are worth
 * more than the visual cohesion that would be gained.
 */
@Immutable
data class ConfidenceColours(
    val readyFg: Color,
    val readyBg: Color,
    val checkFg: Color,
    val checkBg: Color,
    val missingFg: Color,
    val missingBg: Color,
    val invalidFg: Color,
    val invalidBg: Color,
) {
    companion object {
        /** All pairs verified at 4.5:1 or better against their surface. */
        val Light = ConfidenceColours(
            readyFg = Color(0xFF2E6B4F), readyBg = Color(0xFFE8F3ED),
            checkFg = Color(0xFF8A5A00), checkBg = Color(0xFFFDF3E0),
            missingFg = Color(0xFF5A6572), missingBg = Color(0xFFEFF1F4),
            invalidFg = Color(0xFFB3261E), invalidBg = Color(0xFFFCEDEC),
        )

        /** Re-tuned rather than reused: amber that reads as "attention" on white reads as "highlighted" on black. */
        val Dark = ConfidenceColours(
            readyFg = Color(0xFF7FD4A8), readyBg = Color(0xFF16281F),
            checkFg = Color(0xFFF2C066), checkBg = Color(0xFF2B2114),
            missingFg = Color(0xFFA8B2BE), missingBg = Color(0xFF1C2026),
            invalidFg = Color(0xFFF2B8B5), invalidBg = Color(0xFF2B1614),
        )
    }
}

val LocalConfidenceColours = staticCompositionLocalOf { ConfidenceColours.Light }
