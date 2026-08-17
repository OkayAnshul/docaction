package com.okayanshul.docaction.document.spreadsheet

import com.okayanshul.docaction.domain.DateOrder
import com.okayanshul.docaction.domain.FoundSchedules
import com.okayanshul.docaction.domain.PipelineAnswers
import com.okayanshul.docaction.domain.PipelineQuestion
import com.okayanshul.docaction.domain.ScheduleGroup
import com.okayanshul.docaction.extraction.timetable.DatedTableBuilder
import java.io.File

/**
 * Which reader a detected block needs.
 *
 * Decided during detection and carried forward, rather than re-derived at extraction time:
 * the two readers disagree about the same grid on purpose, and working the answer out twice
 * is how they would drift apart.
 */
enum class ScheduleShape { Weekly, Dated }

/** One selectable schedule found in a workbook, before the user picks. */
data class DetectedSchedule(
    val sheetName: String,
    val label: String,
    val block: SectionBlock,
    val entryCount: Int,
    val shape: ScheduleShape = ScheduleShape.Weekly,
)

/**
 * Finds the schedules in a workbook and, on request, extracts one.
 *
 * Detection is deliberately separated from extraction. A real institutional export can
 * hold hundreds of sections; building every one of them up front would be slow and
 * pointless when the user wants exactly one. So detection does the cheap structural pass,
 * and the chosen block alone goes through the full engine.
 */
class SpreadsheetSchedules(
    private val reader: XlsxReader = XlsxReader(),
    private val engine: ScheduleExtractor = ScheduleExtractor(),
) {

    fun open(file: File): Workbook = reader.read(file)

    /** Cheap pass: what schedules are in here, and roughly how big is each. */
    fun detect(workbook: Workbook): List<DetectedSchedule> = workbook.sheets.flatMap { sheet ->
        val headerRow = engine.findHeaderRow(sheet)
            ?: return@flatMap flatList(sheet)
                .ifEmpty { weekdayHeaderList(sheet) }
                .ifEmpty { datedList(sheet) }
        val splitter = SectionSplitter(isDayRow = engine::isDayRow)
        splitter.split(sheet, headerRow).map { block ->
            DetectedSchedule(
                sheetName = sheet.name,
                label = block.label,
                block = block,
                // Populated cells in the block, excluding the weekday column — a good
                // enough size hint for the picker without running the full engine.
                entryCount = block.dataRows.sumOf { row ->
                    sheet.row(row).count { it.isNotBlank() } - 1
                }.coerceAtLeast(0),
            )
        }
    }

    /**
     * Full extraction of one chosen schedule.
     *
     * [order] and [assumedYear] are the user's answers about how this document writes dates;
     * they matter only to the dated reader, and a weekly grid ignores them.
     */
    fun extract(
        workbook: Workbook,
        schedule: DetectedSchedule,
        order: DateOrder? = null,
        assumedYear: Int? = null,
    ): Extracted {
        val sheet = workbook.sheets.firstOrNull { it.name == schedule.sheetName }
            ?: return Extracted(null)

        if (schedule.shape == ScheduleShape.Dated) {
            val result = engine.extractDated(sheet, schedule.block, order, assumedYear)
            return Extracted(result.group, result.ambiguous)
        }

        val headerRow = engine.findHeaderRow(sheet)
            ?: return Extracted(engine.extractFlat(sheet, schedule.block))
        return Extracted(engine.extract(sheet, headerRow, schedule.block))
    }

    /**
     * What one extraction produced, and the one thing it could not decide for itself.
     *
     * An ambiguous date has to travel back up rather than be swallowed: `05/10/2026` is two
     * real dates and the document does not say which, so the only honest options are to ask
     * or to produce nothing. Returning it here is what lets the pipeline ask.
     */
    data class Extracted(
        val group: ScheduleGroup?,
        val ambiguous: DatedTableBuilder.Ambiguity? = null,
    )

    /**
     * Builds one block and turns anything it could not decide into a question.
     *
     * Shared by the workbook and CSV sources so the two cannot drift: they are the same
     * engine reached through different parsers, and a rule about when to ask belongs in one
     * place. The question is raised only when the block produced nothing, because that is the
     * only case where the ambiguity is genuinely why the user has no events.
     */
    fun build(
        workbook: Workbook,
        schedule: DetectedSchedule,
        answers: PipelineAnswers,
    ): FoundSchedules {
        val result = extract(workbook, schedule, answers.dateOrder, answers.assumedYear)
        val ambiguity = result.ambiguous

        return FoundSchedules(
            groups = listOfNotNull(result.group),
            questions = if (result.group == null && ambiguity != null) {
                listOf(
                    PipelineQuestion.DateOrder(
                        example = ambiguity.example,
                        dayFirst = ambiguity.dayFirst,
                        monthFirst = ambiguity.monthFirst,
                    )
                )
            } else {
                emptyList()
            },
        )
    }

    /**
     * A sheet with no period-header row, but a weekday in every row.
     *
     * `Day | Time | Subject | Room`, one class per line. It is the natural shape for a
     * hand-written timetable and the only shape a CSV export can really take, and the
     * period-grid reader cannot see it at all: it looks for a row carrying several times and
     * gives up when the header says "Day, Time, Subject, Room".
     *
     * Treated as one block covering the whole sheet, because a flat list has no sections to
     * split — the reader downstream finds the weekday column by content.
     */
    private fun flatList(sheet: SheetGrid): List<DetectedSchedule> {
        val dataRows = (0 until sheet.rowCount).filter { engine.isDayRow(sheet.row(it)) }
        if (dataRows.size < MIN_FLAT_ROWS) return emptyList()

        return listOf(
            DetectedSchedule(
                sheetName = sheet.name,
                label = sheet.name,
                block = SectionBlock(
                    label = sheet.name,
                    // Row 0 carries the column names in this shape; the reader needs it to
                    // tell a subject column from a room column.
                    headerRow = 0,
                    firstDataRow = dataRows.min(),
                    lastDataRow = dataRows.max(),
                ),
                entryCount = dataRows.size,
            )
        )
    }

    /**
     * The ordinary school timetable: weekdays across the top, times down the side.
     *
     * `Time | Monday | Tuesday | …` with `09:00-10:00 | Maths | Physics | …` beneath. This is
     * probably the most common timetable layout there is, and neither existing reader could
     * see it: [ScheduleExtractor.findHeaderRow] wants three time-shaped cells in **one row**
     * and this shape puts one time per row, while [flatList] wants three **rows** naming
     * weekdays and this shape names them only in the header.
     *
     * The whole sheet becomes one block and `TimetableBuilder` works out the orientation
     * itself — weekdays along the top is a shape it already understands. A blank template of
     * this exact layout still yields nothing, because the builder insists on cells that
     * actually name a subject.
     */
    private fun weekdayHeaderList(sheet: SheetGrid): List<DetectedSchedule> {
        val header = (0 until minOf(sheet.rowCount, HEADER_SEARCH_ROWS)).firstOrNull { row ->
            sheet.row(row).count { it.isNotBlank() && engine.namesWeekday(it) } >= MIN_HEADER_WEEKDAYS
        } ?: return emptyList()

        val lastRow = (sheet.rowCount - 1).takeIf { it > header } ?: return emptyList()

        return listOf(
            DetectedSchedule(
                sheetName = sheet.name,
                label = sheet.name,
                block = SectionBlock(
                    label = sheet.name,
                    headerRow = header,
                    firstDataRow = header + 1,
                    lastDataRow = lastRow,
                ),
                entryCount = (header + 1..lastRow).sumOf { row ->
                    sheet.row(row).count { it.isNotBlank() }
                }.coerceAtLeast(1),
            )
        )
    }

    /**
     * A sheet with no period header and no weekday column, but dates down a column.
     *
     * `Event | Date | Time`, one event per row — the shape of every system export there is,
     * and the one this module could not see at all. Tried only after the weekly readers have
     * declined, so a timetable that happens to carry a date column stays a timetable.
     *
     * Detection is deliberately looser than extraction. Two dated rows is enough to look, and
     * [DatedTableBuilder] is the real gate: it insists on a header row that names a date
     * column and refuses anything else. Being generous here and strict there costs one cheap
     * pass and avoids a whole class of "we nearly read it" failures.
     */
    private fun datedList(sheet: SheetGrid): List<DetectedSchedule> {
        val dataRows = (0 until sheet.rowCount).filter { engine.isDateRow(sheet.row(it)) }
        if (dataRows.size < MIN_DATED_ROWS) return emptyList()

        // Row 0 carries the column names, exactly as in the weekday flat list: the reader
        // needs them to tell a date column from a time column.
        val first = dataRows.min()
        if (first == 0) return emptyList()

        return listOf(
            DetectedSchedule(
                sheetName = sheet.name,
                label = sheet.name,
                block = SectionBlock(
                    label = sheet.name,
                    headerRow = 0,
                    firstDataRow = first,
                    lastDataRow = dataRows.max(),
                ),
                // Must be non-zero, or DocumentPipeline drops the group before it is ever
                // built — the same way the 335-section workbook once vanished.
                entryCount = dataRows.size,
                shape = ScheduleShape.Dated,
            )
        )
    }

    private companion object {
        /** Two weekday rows is a coincidence; three is a timetable. */
        const val MIN_FLAT_ROWS = 3

        /** Lower than [MIN_FLAT_ROWS] because the dated reader itself is far stricter. */
        const val MIN_DATED_ROWS = 2

        /** Two weekday names across a row is a header; one is a passing mention. */
        const val MIN_HEADER_WEEKDAYS = 2
        const val HEADER_SEARCH_ROWS = 50
    }
}
