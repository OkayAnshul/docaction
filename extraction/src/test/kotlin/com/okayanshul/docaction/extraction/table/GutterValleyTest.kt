package com.okayanshul.docaction.extraction.table

import com.google.common.truth.Truth.assertThat
import com.okayanshul.docaction.domain.BoundingBox
import com.okayanshul.docaction.domain.TextOrigin
import com.okayanshul.docaction.domain.TextRun
import org.junit.Test

/**
 * Columns survive content that overruns them.
 *
 * A gutter used to mean a band no ordinary cell text touched at all. That is true of a clean
 * table and false of a real one: columns are narrow, their contents are not, and one subject
 * spilling a few pixels past its column erased that boundary for the whole document. A
 * screenshot of a college timetable came back with six columns where it had nine, so two
 * period headings shared a cell and the period row could no longer be read at all.
 *
 * A gutter is a valley in the occupancy profile, not a silence.
 */
class GutterValleyTest {

    private val builder = TableBuilder()

    private fun run(text: String, left: Float, row: Int, width: Float) = TextRun(
        text = text,
        bounds = BoundingBox(left, row * 20f, left + width, row * 20f + 10f),
        confidence = null,
        origin = TextOrigin.PdfTextLayer,
    )

    @Test
    fun `a column boundary survives a cell that overruns it`() {
        // Four columns on a 120pt pitch, cells 100 wide, so each gutter is the 20pt band
        // between them. The overrunning cells are 118 wide: enough to cross the gutter, but
        // still under the spanning threshold (median 100 × 1.2 = 120), so they are counted as
        // ordinary cell text rather than waved through as a merged header. That combination
        // is what used to erase the boundary — and it is exactly what a slightly-too-long
        // subject name does on a real timetable.
        val runs = mutableListOf<TextRun>()
        val columns = listOf(0f, 120f, 240f, 360f)

        columns.forEachIndexed { index, x -> runs += run("Head$index", x, 0, 100f) }

        (1..10).forEach { row ->
            columns.forEachIndexed { index, x ->
                val overruns = row == 4
                runs += run(
                    text = if (overruns) "Overlong cell $index" else "Cell $index",
                    left = x,
                    row = row,
                    width = if (overruns) 118f else 100f,
                )
            }
        }

        val grid = builder.build(runs)

        assertThat(grid).isNotNull()
        // Four columns, not two fused pairs.
        assertThat(grid!!.columnCount).isEqualTo(columns.size)
    }

    @Test
    fun `a sparse table still requires a silent gutter`() {
        // Two columns, few lines. Here the quiet threshold rounds below one, so the rule is
        // the strict one it always was — a column populated only in the header must survive.
        val runs = listOf(
            run("Test", 0f, 0, 40f),
            run("Date", 200f, 0, 40f),
            run("MRI Brain", 0f, 1, 60f),
            run("22/09/2026", 200f, 1, 60f),
            run("Bloods", 0f, 2, 50f),
            run("23/09/2026", 200f, 2, 60f),
        )

        val grid = builder.build(runs)

        assertThat(grid).isNotNull()
        assertThat(grid!!.columnCount).isEqualTo(2)
        assertThat(grid.cells.filter { it.row == 0 }.map { it.text })
            .containsExactly("Test", "Date")
    }
}
