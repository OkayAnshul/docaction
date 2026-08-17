package com.okayanshul.docaction.domain

/**
 * Where a value came from. Every [Confident] value that has a value also has one of
 * these — it is a constructor parameter, so an untraceable value cannot be built.
 *
 * This powers "View source" in the review screen, which does more for user trust than
 * any amount of confidence tuning: it turns "the app claims" into "I can see it".
 *
 * **Every [BoundingBox] here is a fraction of its page, in `0..1`** — not points, not
 * pixels. A reference exists to point a person at a place in a document, and the only unit
 * that survives re-rendering that page at some other size is a proportion of it. Extraction
 * works in whatever units its reader produced; [BoundingBox.fractionOf] is the one-way door
 * between the two.
 */
sealed interface SourceReference {

    data class PdfSpan(val page: Int, val bounds: BoundingBox) : SourceReference

    data class SheetCell(val sheet: String, val row: Int, val column: Int) : SourceReference

    data class SheetRange(val sheet: String, val from: SheetCell, val to: SheetCell) : SourceReference

    /**
     * A place on something that was read as an image rather than as text.
     *
     * [page] is null for a photo and set for a scanned PDF page that went through
     * recognition — without it, "where did this come from?" cannot answer "page 4" for
     * exactly the documents where the user is most likely to ask.
     */
    data class ImageRegion(val bounds: BoundingBox, val page: Int? = null) : SourceReference

    data class CsvCell(val line: Int, val column: Int) : SourceReference

    /**
     * Assembled from several places. [rule] names how, so "where did this end time come
     * from?" answers "the next class in this column starts at 10:00" rather than
     * "we assumed an hour". If the honest answer would be the latter, the field is
     * [Confident.Missing] instead.
     */
    data class Derived(val from: List<SourceReference>, val rule: String) : SourceReference

    /**
     * The user supplied or corrected it. Deliberately a source rather than a flag: a
     * corrected value is as traceable as an extracted one, the two can never be
     * confused, and re-derivation must never overwrite it.
     */
    data class UserProvided(val field: String, val atEpochMillis: Long) : SourceReference

    /**
     * **We** supplied it. The document said nothing.
     *
     * Distinct from [Derived], whose whole point is that it names a rule the document
     * supports ("the next class in this column starts at 10:00"). This one names a rule the
     * document does *not* support — an assumed duration, an all-day item made from a bare
     * date. Source View must never point at a page for one of these; it says so instead.
     *
     * See [Assumption], which is the only thing that creates these.
     */
    data class Assumed(val field: String, val rule: String) : SourceReference
}

/** Page-space rectangle, y-down, origin top-left. */
data class BoundingBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun union(other: BoundingBox) = BoundingBox(
        left = minOf(left, other.left),
        top = minOf(top, other.top),
        right = maxOf(right, other.right),
        bottom = maxOf(bottom, other.bottom),
    )

    /** Horizontal overlap in page units; negative when the boxes are disjoint. */
    fun horizontalOverlap(other: BoundingBox): Float =
        minOf(right, other.right) - maxOf(left, other.left)

    /**
     * This box as a proportion of a page that is [width] by [height] in the same units.
     *
     * Clamped to `0..1`: a glyph whose declared box overhangs the page edge is common
     * enough, and a highlight drawn outside the page is a rendering bug rather than useful
     * information. A page with no area yields the whole-page box, which reads as "somewhere
     * on this page" — the honest answer when there is nothing to divide by.
     */
    fun fractionOf(width: Float, height: Float): BoundingBox {
        if (width <= 0f || height <= 0f) return BoundingBox(0f, 0f, 1f, 1f)
        return BoundingBox(
            left = (left / width).coerceIn(0f, 1f),
            top = (top / height).coerceIn(0f, 1f),
            right = (right / width).coerceIn(0f, 1f),
            bottom = (bottom / height).coerceIn(0f, 1f),
        )
    }

    companion object {
        fun of(boxes: List<BoundingBox>): BoundingBox? =
            boxes.reduceOrNull { acc, box -> acc.union(box) }
    }
}
