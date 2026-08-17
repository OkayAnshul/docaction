package com.okayanshul.docaction.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription

/** The four states a user ever sees. Never a percentage. */
enum class Confidence { Ready, Check, Missing, Invalid }

/**
 * How certain we are about a value, said in a way anyone can read.
 *
 * Three things carry the meaning — **glyph, word, and colour** — so the state survives
 * greyscale, colour blindness, and a screen reader. Colour alone would fail all three, and
 * this is the component that appears beside every extracted event.
 *
 * Deliberately never renders a number: a percentage invites the user to calibrate against a
 * scale they have no basis for, and implies a precision the score does not have.
 */
@Composable
fun ConfidenceBadge(
    state: Confidence,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
) {
    val colours = DocAction.confidence
    val (glyph, word, foreground, background) = when (state) {
        Confidence.Ready -> Quad("✓", "Ready", colours.readyFg, colours.readyBg)
        Confidence.Check -> Quad("⚠", "Check", colours.checkFg, colours.checkBg)
        Confidence.Missing -> Quad("?", "Missing", colours.missingFg, colours.missingBg)
        Confidence.Invalid -> Quad("✕", "Invalid", colours.invalidFg, colours.invalidBg)
    }

    Row(
        modifier = modifier
            .background(background, RoundedCornerShape(DocAction.radius.sm))
            .padding(horizontal = DocAction.space.snug, vertical = DocAction.space.tight)
            // One announcement, in words. Without this a screen reader reads a glyph it
            // cannot name and the state is simply lost.
            .clearAndSetSemantics { contentDescription = word },
    ) {
        Text(text = glyph, style = DocAction.type.label, color = foreground)
        if (showLabel) {
            Text(
                text = word,
                style = DocAction.type.label,
                color = foreground,
                modifier = Modifier.padding(start = DocAction.space.tight),
            )
        }
    }
}

private data class Quad(
    val glyph: String,
    val word: String,
    val foreground: Color,
    val background: Color,
)
