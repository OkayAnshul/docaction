package com.okayanshul.docaction.imports.source

import android.graphics.Bitmap
import com.okayanshul.docaction.domain.BoundingBox
import com.okayanshul.docaction.domain.SourceReference

/**
 * What "where did this come from?" can actually show.
 *
 * Deliberately closed. Every branch either shows the user the place or says plainly that it
 * cannot — there is no branch that shows something approximate and lets it pass for the real
 * thing, because a highlight over the wrong row is worse than no highlight.
 */
sealed interface SourceEvidence {

    /** A rendered page with the extracted span outlined. [highlight] is in page fractions. */
    data class Page(
        val label: String,
        val image: Bitmap,
        val highlight: BoundingBox?,
    ) : SourceEvidence

    /**
     * A spreadsheet has no picture to show, so it shows its own grid — the cell in context,
     * which is what the user would look at if they opened the file themselves.
     */
    data class Cells(
        val label: String,
        val columnLabels: List<String>,
        val rows: List<Row>,
        val focusColumn: Int,
    ) : SourceEvidence {
        data class Row(val label: String, val values: List<String>, val isFocus: Boolean)
    }

    /** No place to point at, but something true to say. */
    data class Told(val label: String, val detail: String) : SourceEvidence

    data class Unavailable(val reason: String) : SourceEvidence
}

/**
 * Picks the reference worth showing out of everything a candidate carries.
 *
 * A candidate's fields each carry their own reference, and they are usually neighbours on
 * one page — so the most useful answer is the whole span they cover, not whichever one
 * happens to be first. [Derived][SourceReference.Derived] references are flattened, since
 * "where did this come from" means the places, not the reasoning about them.
 */
object References {

    fun placeable(sources: List<SourceReference>): List<SourceReference> =
        sources.flatMap(::flatten).filter {
            // Neither of these is a place. Pointing at a page for an assumed end time would
            // be the app claiming the document said something it did not — the precise lie
            // Source View exists to make impossible.
            it !is SourceReference.UserProvided && it !is SourceReference.Assumed
        }

    private fun flatten(reference: SourceReference): List<SourceReference> =
        when (reference) {
            is SourceReference.Derived -> reference.from.flatMap(::flatten)
            else -> listOf(reference)
        }

    /** What we filled in ourselves, if anything. */
    fun assumed(sources: List<SourceReference>): List<SourceReference.Assumed> =
        sources.filterIsInstance<SourceReference.Assumed>()

    /** The user's own corrections, most recent first. */
    fun corrections(sources: List<SourceReference>): List<SourceReference.UserProvided> =
        sources.filterIsInstance<SourceReference.UserProvided>().sortedByDescending { it.atEpochMillis }

    /**
     * The page most of this candidate's evidence sits on, and the span it covers there.
     *
     * Choosing the busiest page rather than the first reference matters on a document where
     * one field was read from a heading several pages earlier: highlighting the heading and
     * calling it the source of a class would be technically true and completely unhelpful.
     */
    fun busiestPage(sources: List<SourceReference>): Pair<Int?, BoundingBox?>? {
        val located = placeable(sources).mapNotNull { reference ->
            when (reference) {
                is SourceReference.PdfSpan -> reference.page to reference.bounds
                is SourceReference.ImageRegion -> reference.page to reference.bounds
                else -> null
            }
        }
        if (located.isEmpty()) return null

        val (page, boxes) = located.groupBy({ it.first }, { it.second })
            .maxByOrNull { it.value.size }!!
        return page to BoundingBox.of(boxes.filter { it.width > 0f || it.height > 0f })
    }

    /**
     * A spreadsheet column's name: 0 -> A, 25 -> Z, 26 -> AA.
     *
     * Bijective base-26, which is not the same as base-26 — there is no zero digit, so the
     * carry subtracts one. Getting this wrong produces "cell [1" or "cell Z1" for column 26,
     * and a user checking our work against their own spreadsheet then looks in the wrong
     * place and concludes we read the wrong cell.
     */
    fun columnName(index: Int): String {
        if (index < 0) return ""
        var remaining = index
        val name = StringBuilder()
        while (remaining >= 0) {
            name.insert(0, 'A' + remaining % 26)
            remaining = remaining / 26 - 1
        }
        return name.toString()
    }

    fun sheetCell(sources: List<SourceReference>): SourceReference.SheetCell? =
        placeable(sources).firstNotNullOfOrNull {
            when (it) {
                is SourceReference.SheetCell -> it
                is SourceReference.SheetRange -> it.from
                else -> null
            }
        }
}
