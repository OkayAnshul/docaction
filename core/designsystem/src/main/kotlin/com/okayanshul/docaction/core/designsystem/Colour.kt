package com.okayanshul.docaction.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * "Ink" — the app's colour system.
 *
 * Documents are ink on paper, and so is this interface. The chrome is achromatic: text is
 * ink, surfaces are paper, and the primary button is an ink-filled block the way a stamp is.
 * **All chroma is reserved for meaning.** Nothing here is coloured to look nice, which is
 * what makes colour trustworthy when it does appear — if something is green, it is because
 * it is ready, not because green was available.
 *
 * That is also what makes "the interface gets quieter as confidence rises" structural rather
 * than aspirational. On a screen where the only colours are the four confidence states, forty
 * ready rows recede on their own and the two that need a decision are the only colour present.
 *
 * Every value in this file was verified against its surfaces with a contrast calculator, not
 * chosen by eye; `ContrastTest` keeps them honest.
 */
@Immutable
data class InkColours(
    /** Primary text, and the fill of the primary button. */
    val ink: Color,
    /** Secondary text: supporting lines, metadata. */
    val inkMuted: Color,
    /** Tertiary text: timestamps, captions. Still AA — "faint" is a role, not an excuse. */
    val inkFaint: Color,
    /**
     * Interaction, and only interaction: focus ring, selection, links, the active nav item.
     * Never a status. This is the one hue that may be retinted by dynamic colour.
     */
    val accent: Color,
    /** Control outlines — text fields, checkboxes. Held at 3:1 for WCAG 1.4.11. */
    val border: Color,
    /** Decorative separators between rows. Deliberately below 3:1: it must never be load-bearing. */
    val hairline: Color,
    val surface: Color,
    /** Inset containers — a search field, a quoted document excerpt. */
    val surfaceSunken: Color,
    /** Lifted containers — sheets, the bottom bar over scrolled content. */
    val surfaceRaised: Color,
) {
    companion object {
        val Light = InkColours(
            ink = Color(0xFF16181B),
            inkMuted = Color(0xFF5B6167),
            inkFaint = Color(0xFF676C72),
            accent = Color(0xFF2A46C0),
            border = Color(0xFF8D887F),
            hairline = Color(0xFFE4E1DB),
            surface = Color(0xFFFBFAF8),
            surfaceSunken = Color(0xFFF2F0EC),
            surfaceRaised = Color(0xFFFFFFFF),
        )

        /** A true dark on a warm axis, so paper stays paper rather than becoming blue-grey. */
        val Dark = InkColours(
            ink = Color(0xFFF2F1EE),
            inkMuted = Color(0xFFA2A8AF),
            inkFaint = Color(0xFF868C93),
            accent = Color(0xFF8FA5FF),
            border = Color(0xFF686E77),
            hairline = Color(0xFF272B31),
            surface = Color(0xFF0E1013),
            surfaceSunken = Color(0xFF08090B),
            surfaceRaised = Color(0xFF171A1F),
        )
    }
}

/**
 * Colours for the four confidence states.
 *
 * A **separate token set** from Material's error and warning roles, because these are not
 * errors. A low-confidence field is a question, not a failure, and painting it error-red
 * would tell the user something went wrong — which is both false and alarming.
 *
 * These are excluded from dynamic theming. A user's wallpaper must never be able to make
 * "needs attention" look like "ready"; the semantics of these four states are worth more than
 * the visual cohesion that would be gained.
 *
 * **Ready, Check and Invalid sit at near-identical relative luminance by design**
 * (0.1130 / 0.1159 / 0.1106). No state is allowed to shout louder than another, which is the
 * point — but it means the three are genuinely indistinguishable in greyscale. The glyph and
 * the row's own words therefore carry the whole meaning, and colour adds nothing a
 * colour-blind user loses. That is the strongest possible reading of NFR-7, and it is a
 * property to preserve rather than a flaw to correct.
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
        /** Foreground ≥ 4.5:1 on both the page and its own container. */
        val Light = ConfidenceColours(
            readyFg = Color(0xFF1F6B4A), readyBg = Color(0xFFE9F2EC),
            checkFg = Color(0xFF8A5300), checkBg = Color(0xFFFBF1DF),
            missingFg = Color(0xFF676C72), missingBg = Color(0xFFF0EFEC),
            invalidFg = Color(0xFFB3261E), invalidBg = Color(0xFFFAEBE9),
        )

        /** Re-tuned rather than reused: amber that reads as "attention" on white reads as "highlighted" on black. */
        val Dark = ConfidenceColours(
            readyFg = Color(0xFF6FD3A3), readyBg = Color(0xFF14241C),
            checkFg = Color(0xFFF0C069), checkBg = Color(0xFF261E10),
            missingFg = Color(0xFF868C93), missingBg = Color(0xFF1A1D21),
            invalidFg = Color(0xFFF2B8B5), invalidBg = Color(0xFF261513),
        )
    }
}

val LocalConfidenceColours = staticCompositionLocalOf { ConfidenceColours.Light }
val LocalInkColours = staticCompositionLocalOf { InkColours.Light }
