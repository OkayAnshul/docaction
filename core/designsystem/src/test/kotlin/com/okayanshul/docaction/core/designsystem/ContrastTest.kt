package com.okayanshul.docaction.core.designsystem

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow

/**
 * The accessibility promises of the colour system, checked rather than asserted in prose.
 *
 * NFR-6 requires WCAG 2.2 AA. A palette is the easiest thing in a codebase to adjust by eye
 * and the hardest to notice you have broken — a designer nudges one hex two shades lighter
 * and a caption silently drops to 4.1:1. These tests make that a build failure.
 *
 * Ratios are computed with the WCAG 2.x relative-luminance formula, so the numbers here are
 * the numbers an auditing tool will produce.
 */
class ContrastTest {

    // --- thresholds, named so failures read as requirements ---

    private val bodyText = 4.5     // WCAG 1.4.3, normal-size text
    private val uiComponent = 3.0  // WCAG 1.4.11, non-text contrast

    private val lightSurfaces = listOf(
        "surface" to InkColours.Light.surface,
        "surfaceSunken" to InkColours.Light.surfaceSunken,
        "surfaceRaised" to InkColours.Light.surfaceRaised,
    )

    private val darkSurfaces = listOf(
        "surface" to InkColours.Dark.surface,
        "surfaceSunken" to InkColours.Dark.surfaceSunken,
        "surfaceRaised" to InkColours.Dark.surfaceRaised,
    )

    // --- text ---

    @Test
    fun everyTextRoleClearsAaOnEverySurface() {
        // "Faint" is a role in the hierarchy, not permission to be unreadable. A timestamp
        // still has to be legible to someone reading it on a phone in daylight.
        val roles = listOf(
            "ink" to { c: InkColours -> c.ink },
            "inkMuted" to { c: InkColours -> c.inkMuted },
            "inkFaint" to { c: InkColours -> c.inkFaint },
            "accent" to { c: InkColours -> c.accent },
        )

        for ((themeName, theme, surfaces) in listOf(
            Triple("light", InkColours.Light, lightSurfaces),
            Triple("dark", InkColours.Dark, darkSurfaces),
        )) {
            for ((roleName, role) in roles) {
                for ((surfaceName, surface) in surfaces) {
                    val ratio = contrast(role(theme), surface)
                    assertWithContext(
                        "$themeName $roleName on $surfaceName", ratio, bodyText,
                    )
                }
            }
        }
    }

    @Test
    fun theFilledPrimaryButtonIsLegible() {
        // primary is ink, onPrimary is paper — the highest-contrast pair in the system, and
        // the one the main call to action depends on.
        assertWithContext(
            "light primary", contrast(InkColours.Light.surface, InkColours.Light.ink), bodyText,
        )
        assertWithContext(
            "dark primary", contrast(InkColours.Dark.surface, InkColours.Dark.ink), bodyText,
        )
    }

    // --- confidence ---

    @Test
    fun everyConfidenceStateClearsAaOnThePageAndOnItsOwnContainer() {
        for ((themeName, confidence, surface) in listOf(
            Triple("light", ConfidenceColours.Light, InkColours.Light.surface),
            Triple("dark", ConfidenceColours.Dark, InkColours.Dark.surface),
        )) {
            val states = listOf(
                Triple("ready", confidence.readyFg, confidence.readyBg),
                Triple("check", confidence.checkFg, confidence.checkBg),
                Triple("missing", confidence.missingFg, confidence.missingBg),
                Triple("invalid", confidence.invalidFg, confidence.invalidBg),
            )
            for ((state, fg, bg) in states) {
                assertWithContext("$themeName $state on page", contrast(fg, surface), bodyText)
                assertWithContext("$themeName $state on container", contrast(fg, bg), bodyText)
            }
        }
    }

    @Test
    fun readyCheckAndInvalidCarryEqualVisualWeight() {
        // A deliberate property, not a coincidence: no state is allowed to shout louder than
        // another, so their luminances are matched. The consequence is that colour alone
        // cannot separate them — which is exactly why ConfidenceBadge ships a distinct glyph
        // and every row states its reason in words. If someone "fixes" the palette so these
        // diverge, the quiet-by-default design is what breaks.
        val ready = luminance(ConfidenceColours.Light.readyFg)
        val check = luminance(ConfidenceColours.Light.checkFg)
        val invalid = luminance(ConfidenceColours.Light.invalidFg)

        assertThat(abs(ready - check)).isLessThan(0.02)
        assertThat(abs(ready - invalid)).isLessThan(0.02)
    }

    // --- non-text ---

    @Test
    fun controlBordersClearNonTextContrast() {
        // WCAG 1.4.11. A text field the user cannot find the edge of is a text field they
        // cannot tell is editable.
        for ((themeName, theme, surfaces) in listOf(
            Triple("light", InkColours.Light, lightSurfaces),
            Triple("dark", InkColours.Dark, darkSurfaces),
        )) {
            for ((surfaceName, surface) in surfaces) {
                assertWithContext(
                    "$themeName border on $surfaceName", contrast(theme.border, surface), uiComponent,
                )
            }
        }
    }

    @Test
    fun theFocusRingIsFindableOnEverySurface() {
        for ((themeName, theme, surfaces) in listOf(
            Triple("light", InkColours.Light, lightSurfaces),
            Triple("dark", InkColours.Dark, darkSurfaces),
        )) {
            for ((surfaceName, surface) in surfaces) {
                assertWithContext(
                    "$themeName accent on $surfaceName", contrast(theme.accent, surface), uiComponent,
                )
            }
        }
    }

    @Test
    fun hairlinesStayBelowComponentContrastSoTheyAreNeverLoadBearing() {
        // The inverse assertion, and a deliberate one. A separator that clears 3:1 reads as a
        // border and starts carrying meaning it was never given. Keeping it quiet is what
        // stops a list of forty rows turning into forty boxes.
        assertThat(contrast(InkColours.Light.hairline, InkColours.Light.surface))
            .isLessThan(uiComponent)
        assertThat(contrast(InkColours.Dark.hairline, InkColours.Dark.surface))
            .isLessThan(uiComponent)
    }

    // --- the calculator ---

    /** Names the pair in the failure, so a red build says which token to fix and by how much. */
    private fun assertWithContext(what: String, ratio: Double, minimum: Double) {
        // Truth's placeholders are %s only, so the numbers are formatted before they go in.
        assertWithMessage(
            "$what contrast is ${"%.2f".format(ratio)}:1, needs ${"%.1f".format(minimum)}:1",
        ).that(ratio).isAtLeast(minimum)
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** WCAG 2.x relative luminance. */
    private fun luminance(colour: Color): Double {
        fun channel(value: Float): Double {
            val c = value.toDouble()
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(colour.red) +
            0.7152 * channel(colour.green) +
            0.0722 * channel(colour.blue)
    }
}
