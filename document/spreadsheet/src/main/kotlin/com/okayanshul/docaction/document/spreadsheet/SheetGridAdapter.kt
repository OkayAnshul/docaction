package com.okayanshul.docaction.document.spreadsheet

import com.okayanshul.docaction.domain.BoundingBox
import com.okayanshul.docaction.domain.SourceReference
import com.okayanshul.docaction.domain.TextOrigin
import com.okayanshul.docaction.domain.TextRun
import com.okayanshul.docaction.extraction.table.Cell
import com.okayanshul.docaction.extraction.table.ColumnSpan
import com.okayanshul.docaction.extraction.table.Grid

/**
 * Presents a worksheet as the positioned text the extraction engine consumes.
 *
 * Synthesising geometry from row and column indices looks odd until you notice what it
 * buys: the timetable engines, the confidence scorer, and the candidate builder are shared
 * verbatim with the PDF path. A spreadsheet is simply a document whose layout we happen to
 * know exactly instead of having to infer.
 *
 * Emits one [TextRun] per populated cell, positioned on a regular lattice, so the caller
 * builds an extraction `Grid` without any of the clustering a PDF needs.
 */
object SheetGridAdapter {

    /** Arbitrary but consistent: any regular lattice reconstructs to the same grid. */
    private const val COLUMN_PITCH = 100f
    private const val ROW_PITCH = 20f
    private const val CELL_WIDTH = 80f
    private const val CELL_HEIGHT = 10f

    /**
     * Builds the extraction grid **directly** from sheet coordinates.
     *
     * The geometric path ([runsFor] plus `TableBuilder`) exists for PDFs, where column
     * boundaries have to be inferred from where text happens to sit. Running a spreadsheet
     * through it throws away exact information and then tries to recover it — and on a real
     * timetable it lost three period columns that were populated only in the header row.
     *
     * Column C is column C here, empty or not. No inference, no tolerances.
     */
    fun toGrid(sheet: SheetGrid, rows: List<Int>): Grid {
        val columns = (0 until sheet.columnCount).map { column ->
            val left = column * COLUMN_PITCH
            ColumnSpan(column, left, left + CELL_WIDTH)
        }

        val cells = rows.flatMapIndexed { placedRow, sheetRow ->
            (0 until sheet.columnCount).mapNotNull { column ->
                val text = sheet.text(sheetRow, column)
                if (text.isBlank()) return@mapNotNull null
                val left = column * COLUMN_PITCH
                val top = placedRow * ROW_PITCH
                Cell(
                    row = placedRow,
                    column = column,
                    runs = listOf(
                        TextRun(
                            text = text,
                            bounds = BoundingBox(left, top, left + CELL_WIDTH, top + CELL_HEIGHT),
                            confidence = null,
                            origin = TextOrigin.SpreadsheetCell,
                        )
                    ),
                )
            }
        }

        return Grid(rowCount = rows.size, columns = columns, cells = cells)
    }

    fun runsFor(sheet: SheetGrid, rows: IntRange, includeRow: (Int) -> Boolean = { true }): List<TextRun> {
        val runs = mutableListOf<TextRun>()
        var placedRow = 0

        for (row in rows) {
            if (row !in 0 until sheet.rowCount) continue
            if (!includeRow(row)) continue

            for (column in 0 until sheet.columnCount) {
                val text = sheet.text(row, column)
                if (text.isBlank()) continue
                val left = column * COLUMN_PITCH
                val top = placedRow * ROW_PITCH
                runs += TextRun(
                    text = text,
                    bounds = BoundingBox(left, top, left + CELL_WIDTH, top + CELL_HEIGHT),
                    // A spreadsheet cell is a read, not a recognition.
                    confidence = null,
                    origin = TextOrigin.SpreadsheetCell,
                )
            }
            placedRow++
        }
        return runs
    }

    /**
     * Maps a synthesised position back to the cell it came from, so "View source" can say
     * *Sheet "Section Grid", row 9, column D* rather than pointing at made-up coordinates.
     */
    fun sourceOf(sheetName: String, bounds: BoundingBox, rows: List<Int>): SourceReference {
        val placedRow = (bounds.top / ROW_PITCH).toInt()
        val column = (bounds.left / COLUMN_PITCH).toInt()
        val row = rows.getOrNull(placedRow) ?: placedRow
        return SourceReference.SheetCell(sheetName, row, column)
    }
}
