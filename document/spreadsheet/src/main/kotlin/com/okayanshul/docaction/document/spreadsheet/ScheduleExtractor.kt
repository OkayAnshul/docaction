package com.okayanshul.docaction.document.spreadsheet

import com.okayanshul.docaction.domain.DateOrder
import com.okayanshul.docaction.domain.ScheduleGroup
import com.okayanshul.docaction.domain.SourceReference
import com.okayanshul.docaction.extraction.timetable.CellContent
import com.okayanshul.docaction.extraction.timetable.DatedTableBuilder
import com.okayanshul.docaction.extraction.timetable.TimetableBuilder

/**
 * Runs one section block through the shared extraction engine.
 *
 * The block's header row is prepended to its data rows so the engine sees a self-contained
 * little timetable — period headings on top, one row per weekday beneath — which is
 * exactly the shape [com.okayanshul.docaction.extraction.timetable.Orientation.PeriodColumns]
 * was built for.
 */
class ScheduleExtractor(
    private val content: CellContent = CellContent(),
    private val timetables: TimetableBuilder = TimetableBuilder(),
    private val dated: DatedTableBuilder = DatedTableBuilder(),
) {

    /** Whether one cell names a weekday, for callers counting them across a header row. */
    fun namesWeekday(text: String): Boolean = content.looksLikeWeekday(text)

    /** A row counts as a day row when any cell in it names a weekday. */
    fun isDayRow(values: List<String>): Boolean =
        values.any { it.isNotBlank() && content.looksLikeWeekday(it) }

    /**
     * A row counts as dated when any cell in it holds a readable calendar date.
     *
     * This is the shape a weekday reader cannot see at all: `Sprint demo | 25/03/2026 |
     * 10:00`, one event per row, keyed on a date rather than on "Monday". It is what almost
     * every system export looks like — bookings, bills, assignment lists, fixture lists — and
     * the whole spreadsheet path was blind to it.
     *
     * [CellContent.looksLikeDate] deliberately excludes impossible parses, which is what keeps
     * an ordinary timetable's `09:00-10:00` from reading as the date `00-10` and turning every
     * weekly grid into a dated one.
     */
    fun isDateRow(values: List<String>): Boolean =
        values.any { it.isNotBlank() && content.looksLikeDate(it) }

    /**
     * The header row is the one carrying the period times. Found by content, never by
     * assuming row 1 — institutional exports put titles and blank spacers there.
     */
    fun findHeaderRow(sheet: SheetGrid): Int? =
        (0 until minOf(sheet.rowCount, HEADER_SEARCH_ROWS)).firstOrNull { row ->
            sheet.row(row).count { it.isNotBlank() && content.looksLikeTime(it) } >= MIN_PERIODS
        }

    /**
     * A flat list — one class per row, with a weekday column.
     *
     * The grid is handed over whole and the reader works out the orientation itself: rows of
     * weekdays is a shape it already understands. Nothing here decides which column is which,
     * because the sheet's own header row says so and guessing would be worse.
     */
    fun extractFlat(sheet: SheetGrid, block: SectionBlock): ScheduleGroup? {
        val rows = (block.headerRow..block.lastDataRow).toList()
        val grid = SheetGridAdapter.toGrid(sheet, rows)
        return timetables.build(grid, block.label) { cell ->
            SourceReference.SheetCell(sheet.name, cell.row, cell.column)
        }.group
    }

    fun extract(sheet: SheetGrid, headerRow: Int, block: SectionBlock): ScheduleGroup? {
        val rows = listOf(headerRow) + block.dataRows.toList()
        // Built from sheet coordinates, not inferred from geometry — see [SheetGridAdapter.toGrid].
        val grid = SheetGridAdapter.toGrid(sheet, rows)

        val result = timetables.build(grid, block.label) { cell ->
            val bounds = cell.bounds
            if (bounds == null) {
                // An empty cell has no extent. Point at the block itself rather than
                // inventing a position — a source reference must never be made up.
                SourceReference.SheetCell(sheet.name, block.headerRow, 0)
            } else {
                SheetGridAdapter.sourceOf(sheet.name, bounds, rows)
            }
        }
        return result.group
    }

    /**
     * A dated table — one event per row, with a date column.
     *
     * Routed to [DatedTableBuilder] rather than [TimetableBuilder], because the timetable
     * reader explicitly declines dated grids: it is looking for a week, and a list of dates
     * is not one. The two readers consume the same [com.okayanshul.docaction.extraction.table.Grid],
     * so this is a routing decision, not a second parser.
     *
     * [order] and [assumedYear] carry the user's answers back down. `18/09/2026` is
     * unambiguous, `05/10/2026` is not, and once someone has said which way round their
     * documents are written we simply apply it rather than asking again.
     */
    fun extractDated(
        sheet: SheetGrid,
        block: SectionBlock,
        order: DateOrder? = null,
        assumedYear: Int? = null,
    ): DatedTableBuilder.Result {
        val rows = listOf(block.headerRow) + block.dataRows.toList()
        val grid = SheetGridAdapter.toGrid(sheet, rows)

        return dated.build(grid, block.label, assumedYear, order) { cell ->
            val bounds = cell.bounds
            if (bounds == null) {
                SourceReference.SheetCell(sheet.name, block.headerRow, 0)
            } else {
                SheetGridAdapter.sourceOf(sheet.name, bounds, rows)
            }
        }
    }

    private companion object {
        const val HEADER_SEARCH_ROWS = 50
        const val MIN_PERIODS = 3
    }
}
