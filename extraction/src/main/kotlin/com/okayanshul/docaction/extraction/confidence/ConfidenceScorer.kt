package com.okayanshul.docaction.extraction.confidence

import com.okayanshul.docaction.domain.Confident
import com.okayanshul.docaction.domain.SourceReference
import com.okayanshul.docaction.domain.TextOrigin
import com.okayanshul.docaction.domain.TextRun

/**
 * Turns a value plus the runs it came from into a [Confident].
 *
 * The baselines and the caps are the rules from docs/09-confidence.md. The caps matter
 * most: they are absolute, so no amount of other positive signal lifts an OCR-repaired or
 * structurally-derived value to [Confident.High]. The transformation itself is the
 * uncertainty, and nothing else in the document speaks to it.
 */
class ConfidenceScorer {

    fun <T : Any> score(value: T, runs: List<TextRun>, source: SourceReference): Confident<T> {
        if (runs.isEmpty()) return Confident.Missing("nothing was read here")

        val ocr = runs.filter { it.origin == TextOrigin.Ocr }
        if (ocr.isEmpty()) {
            // A PDF text layer or a spreadsheet cell is a read, not a recognition.
            return Confident.High(value, source)
        }

        // The weakest element decides. A block averaging 0.9 can contain the one element
        // at 0.4, and that element is usually the number that matters.
        val weakest = ocr.mapNotNull { it.confidence }.minOrNull()
            ?: return Confident.Medium(value, source, "read from an image")

        return when {
            weakest >= HIGH -> Confident.High(value, source)
            weakest >= MEDIUM -> Confident.Medium(value, source, "read from an image")
            else -> Confident.Low(value, source, "this was hard to read")
        }
    }

    /**
     * A value assembled from the document's structure rather than read from it — the
     * weekday that comes from a column header, the time that comes from a row header.
     * Legitimate, traceable, and never [Confident.High].
     */
    fun <T : Any> derived(value: T, from: List<SourceReference>, rule: String, reason: String): Confident<T> =
        Confident.Medium(value, SourceReference.Derived(from, rule), reason)

    private companion object {
        const val HIGH = 0.85f
        const val MEDIUM = 0.60f
    }
}
