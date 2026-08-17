package com.okayanshul.docaction.extraction.table

import com.okayanshul.docaction.domain.BoundingBox
import com.okayanshul.docaction.domain.TextRun

/** A horizontal band of runs that belong to the same visual line. */
data class TextLine(val index: Int, val runs: List<TextRun>) {
    init {
        // A line with no runs has no extent and no meaning. Fail with a readable message
        // rather than a null-pointer somewhere downstream.
        require(runs.isNotEmpty()) { "a text line must contain at least one run" }
    }

    val bounds: BoundingBox = BoundingBox.of(runs.map { it.bounds })!!
    val text: String get() = runs.sortedBy { it.bounds.left }.joinToString(" ") { it.text }
}

/** A detected column, as a horizontal span in page units. */
data class ColumnSpan(val index: Int, val left: Float, val right: Float) {
    val center: Float get() = (left + right) / 2f
    fun contains(x: Float) = x >= left && x <= right
}

data class Cell(
    val row: Int,
    val column: Int,
    val runs: List<TextRun>,
    /** True when one run covers more than this column, i.e. a merged or spanning cell. */
    val spanning: Boolean = false,
) {
    /**
     * Reading order within the cell: top to bottom, then left to right. A cell whose
     * content wrapped across several lines — `09:00-` above `10:00` — must read back as
     * the single value the document shows.
     *
     * Runs that sit flush against each other are joined with no separator. Justified text
     * and OCR both fragment words, and inserting a space between the pieces turns
     * `Tuesday` into `Tuesda y`, which no weekday matcher will ever recognise.
     */
    val text: String
        get() {
            val ordered = runs.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
            val builder = StringBuilder()
            var previous: TextRun? = null

            for (run in ordered) {
                previous?.let { before ->
                    val sameLine = kotlin.math.abs(run.bounds.top - before.bounds.top) <
                        before.bounds.height * 0.5f
                    val needsSpace = if (sameLine) {
                        run.bounds.left - before.bounds.right > before.bounds.height * TIGHT_JOIN
                    } else {
                        !continuesWord(before.text, run.text)
                    }
                    if (needsSpace) builder.append(' ')
                }
                builder.append(run.text)
                previous = run
            }
            return builder.toString().trim()
        }
    val isEmpty: Boolean get() = text.isEmpty()
    val bounds: BoundingBox? get() = BoundingBox.of(runs.map { it.bounds })

    private companion object {
        /** Runs closer than this fraction of text height are pieces of one word. */
        const val TIGHT_JOIN = 0.2f

        /**
         * Whether a wrapped line continues the word above it rather than starting a new one.
         *
         * Narrow table columns wrap *mid-word* when a single word doesn't fit — real
         * documents produced `Subje`/`ct`, `Tuesda`/`y`, `08/10/`/`2026`. Rejoining those
         * with a space is what stopped weekday and subject matching from working at all.
         *
         * A normal word wrap, by contrast, breaks at a space, so the next line starts a new
         * word — typically capitalised (`Seminar` / `Hall`), which stays separated.
         */
        fun continuesWord(before: String, after: String): Boolean {
            val last = before.lastOrNull() ?: return false
            val first = after.firstOrNull() ?: return false
            return when {
                // "Subje" + "ct", "Tuesda" + "y"
                last.isLetter() && first.isLowerCase() -> true
                // "R-" + "101", "16:00-" + "18:00"
                last in "-:" && first.isLetterOrDigit() -> true
                // A stop or slash continues a *number* ("08/10/" + "2026") but not a word:
                // "A." + "Nair" and "Data Structures /" + "K10" keep their spaces.
                last in "./" && first.isDigit() -> true
                // "R-10" + "1"
                last.isDigit() && first.isDigit() -> true
                else -> false
            }
        }
    }
}

data class Grid(
    val rowCount: Int,
    val columns: List<ColumnSpan>,
    val cells: List<Cell>,
) {
    private val byPosition = cells.associateBy { it.row to it.column }

    fun cell(row: Int, column: Int): Cell? = byPosition[row to column]

    fun row(index: Int): List<Cell> = columns.indices.map { cell(index, it) ?: Cell(index, it, emptyList()) }

    fun column(index: Int): List<Cell> = (0 until rowCount).map { cell(it, index) ?: Cell(it, index, emptyList()) }

    val columnCount: Int get() = columns.size

    val isEmpty: Boolean get() = cells.none { !it.isEmpty }
}
