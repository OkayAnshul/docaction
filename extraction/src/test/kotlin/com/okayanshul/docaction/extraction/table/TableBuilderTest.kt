package com.okayanshul.docaction.extraction.table

import com.google.common.truth.Truth.assertThat
import com.okayanshul.docaction.domain.BoundingBox
import com.okayanshul.docaction.domain.TextOrigin
import com.okayanshul.docaction.domain.TextRun
import org.junit.Test

/**
 * Built from synthetic geometry rather than real files. That is the point: we can
 * construct the exact pathological layout instead of hunting for a document that has it.
 */
class TableBuilderTest {

    private val builder = TableBuilder()

    /** A run placed on a notional grid: 100pt columns, 20pt rows, 12pt text. */
    private fun run(text: String, col: Int, row: Int, widthCols: Int = 1, scale: Float = 1f): TextRun {
        val left = col * 100f * scale
        val top = row * 20f * scale
        return TextRun(
            text = text,
            bounds = BoundingBox(
                left = left,
                top = top,
                right = left + (80f * widthCols) * scale,
                bottom = top + 12f * scale,
            ),
            confidence = null,
            origin = TextOrigin.PdfTextLayer,
        )
    }

    private fun timetableRuns(scale: Float = 1f) = listOf(
        run("Time", 0, 0, scale = scale),
        run("Monday", 1, 0, scale = scale),
        run("Tuesday", 2, 0, scale = scale),
        run("09:00", 0, 1, scale = scale),
        run("DSA", 1, 1, scale = scale),
        run("OS", 2, 1, scale = scale),
        run("10:00", 0, 2, scale = scale),
        run("OS", 1, 2, scale = scale),
        run("DBMS", 2, 2, scale = scale),
    )

    @Test
    fun `a clean grid reconstructs into rows and columns`() {
        val grid = builder.build(timetableRuns())!!

        assertThat(grid.rowCount).isEqualTo(3)
        assertThat(grid.columnCount).isEqualTo(3)
        assertThat(grid.cell(0, 1)?.text).isEqualTo("Monday")
        assertThat(grid.cell(1, 0)?.text).isEqualTo("09:00")
        assertThat(grid.cell(2, 2)?.text).isEqualTo("DBMS")
    }

    @Test
    fun `input order does not affect the result`() {
        val ordered = builder.build(timetableRuns())!!
        val shuffled = builder.build(timetableRuns().reversed())!!

        assertThat(shuffled.rowCount).isEqualTo(ordered.rowCount)
        assertThat(shuffled.columnCount).isEqualTo(ordered.columnCount)
        (0 until ordered.rowCount).forEach { r ->
            (0 until ordered.columnCount).forEach { c ->
                assertThat(shuffled.cell(r, c)?.text).isEqualTo(ordered.cell(r, c)?.text)
            }
        }
    }

    @Test
    fun `resolution does not affect the result`() {
        // The same layout at roughly 300 dpi. Tolerances are relative to text height,
        // so the reconstruction must be identical.
        val low = builder.build(timetableRuns(scale = 1f))!!
        val high = builder.build(timetableRuns(scale = 4.17f))!!

        assertThat(high.rowCount).isEqualTo(low.rowCount)
        assertThat(high.columnCount).isEqualTo(low.columnCount)
        assertThat(high.cell(1, 1)?.text).isEqualTo(low.cell(1, 1)?.text)
    }

    @Test
    fun `runs on the same line are grouped even when slightly misaligned`() {
        // OCR rarely produces perfectly aligned baselines.
        val runs = listOf(
            run("09:00", 0, 1),
            run("DSA", 1, 1).let { it.copy(bounds = it.bounds.copy(top = it.bounds.top + 3f, bottom = it.bounds.bottom + 3f)) },
            run("K10", 2, 1),
        )

        val grid = builder.build(runs)!!
        assertThat(grid.rowCount).isEqualTo(1)
        assertThat(grid.columnCount).isEqualTo(3)
    }

    @Test
    fun `a superscript does not create a new row`() {
        val base = run("Data Structures", 0, 1)
        val marker = TextRun(
            text = "*",
            bounds = BoundingBox(base.bounds.right + 2f, base.bounds.top - 3f, base.bounds.right + 6f, base.bounds.top + 4f),
            confidence = null,
            origin = TextOrigin.PdfTextLayer,
        )

        assertThat(builder.build(listOf(base, marker))!!.rowCount).isEqualTo(1)
    }

    @Test
    fun `a wide merged header does not erase the columns beneath it`() {
        val runs = timetableRuns() + run("CSE Semester 5 Section B", 0, -1, widthCols = 3)

        val grid = builder.build(runs)!!

        // Without excluding spanning runs from the projection, this would collapse to
        // a single column.
        assertThat(grid.columnCount).isEqualTo(3)
        assertThat(grid.cell(1, 1)?.text).isEqualTo("Monday")
    }

    @Test
    fun `a merged cell is marked as spanning`() {
        val runs = timetableRuns() + run("Lunch Break", 1, 3, widthCols = 2)
        val grid = builder.build(runs)!!

        val merged = grid.row(3).firstOrNull { !it.isEmpty }
        assertThat(merged?.spanning).isTrue()
    }

    @Test
    fun `empty cells are absent rather than fabricated`() {
        // A free period. It must not become an entry with missing data.
        val runs = timetableRuns().filterNot { it.text == "OS" && it.bounds.top == 20f }
        val grid = builder.build(runs)!!

        assertThat(grid.cell(1, 1)?.text).isEqualTo("DSA")
        assertThat(grid.cell(1, 2)).isNull()
    }

    /**
     * Regression: the first real PDF this engine was pointed at had narrow columns, so
     * every cell wrapped. `09:00-10:00` rendered as `09:00-` above `10:00`, and treating
     * each visual line as a table row shattered the whole timetable.
     */
    @Test
    fun `cell content wrapping onto a second line stays in one row`() {
        // Two table rows, each wrapping onto a second line. Wrapped lines sit 8pt apart;
        // the next row starts 24pt later, the way cell padding and a border would.
        fun at(text: String, col: Int, top: Float) = TextRun(
            text = text,
            bounds = BoundingBox(col * 100f, top, col * 100f + 80f, top + 6f),
            confidence = null,
            origin = TextOrigin.PdfTextLayer,
        )

        val runs = listOf(
            at("Time", 0, 0f), at("Monday", 1, 0f),
            at("09:00-", 0, 24f), at("Data Structures /", 1, 24f),
            at("10:00", 0, 32f), at("K10", 1, 32f),
            at("11:00-", 0, 56f), at("Operating Systems /", 1, 56f),
            at("12:00", 0, 64f), at("K11", 1, 64f),
        )

        val grid = builder.build(runs)!!

        assertThat(grid.rowCount).isEqualTo(3)
        // A value broken across lines is rejoined without a space — narrow columns wrap
        // mid-token, and "09:00- 10:00" is not what the document says.
        assertThat(grid.cell(1, 0)?.text).isEqualTo("09:00-10:00")
        assertThat(grid.cell(1, 1)?.text).isEqualTo("Data Structures / K10")
        assertThat(grid.cell(2, 0)?.text).isEqualTo("11:00-12:00")
    }

    @Test
    fun `evenly spaced lines are not merged into rows`() {
        // Uniform spacing means nothing wrapped. The widest gap between sorted gaps is
        // meaningless here, and acting on it would merge unrelated rows.
        val runs = (0..4).flatMap { row ->
            listOf(run("09:0$row", 0, row), run("Subject $row", 1, row))
        }

        assertThat(builder.build(runs)!!.rowCount).isEqualTo(5)
    }

    @Test
    fun `blank input yields no grid`() {
        assertThat(builder.build(emptyList())).isNull()
        assertThat(builder.build(listOf(run("   ", 0, 0)))).isNull()
    }

    @Test
    fun `a single column of text is a one column grid, not a table`() {
        val runs = listOf(run("First line", 0, 0), run("Second line", 0, 1), run("Third line", 0, 2))
        val grid = builder.build(runs)!!

        assertThat(grid.columnCount).isEqualTo(1)
        assertThat(grid.rowCount).isEqualTo(3)
    }
}
