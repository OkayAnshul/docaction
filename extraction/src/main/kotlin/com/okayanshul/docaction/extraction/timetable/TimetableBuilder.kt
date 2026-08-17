package com.okayanshul.docaction.extraction.timetable

import com.okayanshul.docaction.domain.Confident
import com.okayanshul.docaction.domain.EntryId
import com.okayanshul.docaction.domain.GroupId
import com.okayanshul.docaction.domain.ScheduleEntry
import com.okayanshul.docaction.domain.ScheduleGroup
import com.okayanshul.docaction.domain.ScheduleKind
import com.okayanshul.docaction.domain.SourceReference
import com.okayanshul.docaction.domain.capAtMedium
import com.okayanshul.docaction.domain.valueOrNull
import com.okayanshul.docaction.extraction.confidence.ConfidenceScorer
import com.okayanshul.docaction.extraction.date.DateEngine
import com.okayanshul.docaction.extraction.table.Cell
import com.okayanshul.docaction.extraction.table.Grid
import com.okayanshul.docaction.extraction.time.MeridiemResolver
import com.okayanshul.docaction.extraction.time.TimeEngine
import com.okayanshul.docaction.extraction.time.TimeRange
import java.time.DayOfWeek
import java.time.LocalTime

/** How a timetable is laid out. */
enum class Orientation {
    /** Weekdays across the top, times down the side. The classic academic grid. */
    ColumnOriented,

    /**
     * Weekdays down the side, periods across the top, with the times living in the
     * column headings: `P1 (8:00 AM-9:00 AM)`.
     *
     * The transpose of [ColumnOriented], and the shape institutional exports actually use
     * when one sheet holds many sections — each section becomes a compact five-row block.
     */
    PeriodColumns,

    /** One entry per row: `Monday | 09:00 | DSA | K10`. */
    RowOriented,

    /** No timetable structure here — route to free-text extraction or rescue mode. */
    None,
}

data class TimetableResult(
    val orientation: Orientation,
    val group: ScheduleGroup?,
    /** Set when the layout was recognisable but could not be read confidently. */
    val reason: String? = null,
)

/**
 * Turns a reconstructed [Grid] into a [ScheduleGroup].
 *
 * Roles are decided by sampling cell content, not by assuming row 1 is the header — real
 * institutional documents put a college name, a logo caption, or a blank spacer there
 * often enough that position-based header detection is simply wrong.
 *
 * See docs/08-extraction.md § Timetable reconstruction.
 */
class TimetableBuilder(
    private val content: CellContent = CellContent(),
    private val timeEngine: TimeEngine = TimeEngine(),
    private val meridiem: MeridiemResolver = MeridiemResolver(),
    private val dateEngine: DateEngine = DateEngine(),
    private val scorer: ConfidenceScorer = ConfidenceScorer(),
) {

    fun build(
        grid: Grid,
        label: String,
        sourceOf: (Cell) -> SourceReference,
    ): TimetableResult {
        // A weekly timetable states weekdays and repeats. A document that states calendar
        // *dates* is describing specific occasions instead — a month calendar, a conference
        // programme, a transit timetable with an effective-from date. Reading those as a
        // weekly schedule produced confidently wrong recurring events on real documents,
        // so a dated region is declined here rather than forced into a weekly shape.
        // Counted by *row*, not by cell. A genuine timetable often carries an effective-from
        // date or an academic-year line in its header, and counting cells declined a real
        // college timetable over two header dates. A dated schedule instead has dates
        // running down its rows.
        val datedRows = grid.cells
            .filter { !it.isEmpty && content.looksLikeDate(it.text) && !content.looksLikeTime(it.text) }
            .map { it.row }
            .distinct()
            .size
        if (datedRows >= MAX_DATED_ROWS_FOR_WEEKLY) {
            return TimetableResult(
                orientation = Orientation.None,
                group = null,
                reason = "this looks like a dated schedule rather than a weekly one",
            )
        }

        val result = when (detectOrientation(grid)) {
            Orientation.ColumnOriented -> buildColumnOriented(grid, label, sourceOf)
            Orientation.PeriodColumns -> buildPeriodColumns(grid, label, sourceOf)
            Orientation.RowOriented -> buildRowOriented(grid, label, sourceOf)
            Orientation.None -> TimetableResult(Orientation.None, null, "no timetable structure in this region")
        }
        result.group?.let { return if (isPlausibleWeek(it)) result else implausible(result) }

        // Nothing found in the ordinary readings. One shape remains, and it is common enough
        // to be worth a second look: a roster, where the rows are *people* and each cell
        // holds that person's hours for that day. Every reading above wants a column of
        // times down the side; a roster has names there and the times inside the grid.
        //
        // Tried last rather than folded into orientation detection, so that a document the
        // proven readers understand can never be reinterpreted this way.
        val roster = buildRoster(grid, label, sourceOf)
        return roster.group?.let { if (isPlausibleWeek(it)) roster else implausible(roster) }
            ?: result
    }

    /**
     * A real weekly timetable covers several weekdays at several times. One stray row, or
     * a handful of entries scraped out of prose that happened to contain a weekday word,
     * is not a schedule — and emitting it would create recurring events from noise.
     *
     * Deliberately not relaxed when the user has cropped a region themselves. That was
     * tried: a single-day crop also needs [detectOrientation]'s weekday threshold lowered,
     * and putting a second, looser path through the two checks that produce the engine's
     * zero-wrong-outputs record is a poor trade for an unusual gesture. A crop that selects
     * one row is told so instead.
     */
    /**
     * Does this cell name a subject, or is it grid furniture?
     *
     * A month calendar has a day-number grid — `Su M Tu W Th F Sa` over rows of numbers —
     * and every one of those headings is a weekday, so the weekly reader recognises the
     * shape perfectly and reads the day numbers as subjects. A real academic calendar
     * produced four events titled "Su 7142128" that way: not a poor name for a real class,
     * but an event that does not exist.
     *
     * A subject is mostly letters. Day numbers are not.
     */
    private fun namesASubject(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.length < MIN_SUBJECT_LENGTH) return false
        val letters = trimmed.count { it.isLetter() }
        if (letters < MIN_SUBJECT_LETTERS) return false
        return letters.toFloat() / trimmed.count { !it.isWhitespace() } >= MIN_SUBJECT_LETTER_SHARE
    }

    private fun isPlausibleWeek(group: ScheduleGroup): Boolean {
        // Whole-group, not per-entry. One odd cell in a real timetable is normal and the
        // rest should survive it; a grid where *nothing* names a subject is not a timetable
        // at all, and that is the month-calendar case.
        if (group.entries.none { namesASubject(it.title.valueOrNull.orEmpty()) }) return false
        if (group.entries.size < MIN_ENTRIES) return false

        val withWeekday = group.entries.count { it.weekday.valueOrNull != null }
        // In a real weekly timetable nearly every entry sits on a named day. When most
        // don't, a few weekday words were picked out of prose — which is how a transit
        // timetable produced a weekly "HOLIDAY SERVICE" class.
        if (withWeekday.toFloat() / group.entries.size < MIN_WEEKDAY_COVERAGE) return false

        val weekdays = group.entries.mapNotNull { it.weekday.valueOrNull }.distinct()
        val starts = group.entries.mapNotNull { it.startTime.valueOrNull }.distinct()
        return weekdays.size >= MIN_DISTINCT_WEEKDAYS && starts.size >= MIN_DISTINCT_TIMES
    }

    private fun implausible(result: TimetableResult) = TimetableResult(
        orientation = result.orientation,
        group = null,
        reason = "found only a few scattered entries — this doesn't look like a weekly timetable",
    )

    /**
     * Weekday names along a row mean the weekdays are columns; along a column, they are
     * rows. Checked by content across the whole grid rather than by looking only at index 0.
     */
    internal fun detectOrientation(grid: Grid): Orientation {
        if (grid.isEmpty || grid.columnCount < 2 || grid.rowCount < 2) return Orientation.None

        val weekdayRow = (0 until grid.rowCount).maxByOrNull { row ->
            grid.row(row).count { content.looksLikeWeekday(it.text) }
        } ?: return Orientation.None

        val weekdayColumn = (0 until grid.columnCount).maxByOrNull { column ->
            grid.column(column).count { content.looksLikeWeekday(it.text) }
        } ?: return Orientation.None

        val inRow = grid.row(weekdayRow).count { content.looksLikeWeekday(it.text) }
        val inColumn = grid.column(weekdayColumn).count { content.looksLikeWeekday(it.text) }

        return when {
            inRow < MIN_WEEKDAYS && inColumn < MIN_WEEKDAYS -> Orientation.None
            inRow >= inColumn -> Orientation.ColumnOriented
            // Weekdays run down a column. If the times live in a header row rather than in
            // a column of their own, this is a period grid, not a list of entries.
            periodHeaderRow(grid, weekdayColumn) != null -> Orientation.PeriodColumns
            else -> Orientation.RowOriented
        }
    }

    /** The row whose cells carry times, e.g. `P1 (8:00 AM-9:00 AM)`, if there is one. */
    private fun periodHeaderRow(grid: Grid, weekdayColumn: Int): Int? =
        (0 until grid.rowCount).firstOrNull { row ->
            grid.row(row)
                .filter { it.column != weekdayColumn && !it.isEmpty }
                .count { content.looksLikeTime(it.text) } >= MIN_PERIODS
        }

    /**
     * A roster: one row per person, one column per weekday, hours inside the cells.
     *
     * `Staff | Monday | Tuesday | …` over `A. Nair | 07:00-15:00 | OFF | …` is a weekly
     * schedule in every sense — it recurs, it has weekdays, it has times — but it inverts
     * the usual arrangement, so the readers above look for a time column, find names, and
     * give up. A nursing roster in the corpus produced nothing at all for that reason.
     *
     * The person's name becomes the title, which is right for the calendar of the person who
     * imported it: they want to see their own shifts, not the word "shift".
     */
    private fun buildRoster(
        grid: Grid,
        label: String,
        sourceOf: (Cell) -> SourceReference,
    ): TimetableResult {
        if (grid.rowCount < 2 || grid.columnCount < 3) {
            return TimetableResult(Orientation.None, null, "not a roster")
        }

        val headerRow = (0 until grid.rowCount).maxBy { row ->
            grid.row(row).count { content.looksLikeWeekday(it.text) }
        }
        val weekdayColumns = grid.row(headerRow)
            .mapNotNull { cell -> weekdayOf(cell.text)?.let { cell.column to it } }
            .toMap()
        if (weekdayColumns.size < MIN_WEEKDAYS) {
            return TimetableResult(Orientation.None, null, "no weekday columns")
        }

        // The name column is whatever the weekdays are not — normally the first, and the one
        // the header labels "Staff", "Employee" or "Name".
        val nameColumn = (0 until grid.columnCount).firstOrNull { it !in weekdayColumns }
            ?: return TimetableResult(Orientation.None, null, "no column names the people")

        val entries = mutableListOf<ScheduleEntry>()

        (0 until grid.rowCount).filter { it != headerRow }.forEach { row ->
            val nameCell = grid.cell(row, nameColumn) ?: return@forEach
            val name = nameCell.text.trim()
            if (name.count { it.isLetter() } < MIN_NAME_LETTERS) return@forEach

            weekdayColumns.forEach { (column, weekday) ->
                val cell = grid.cell(row, column) ?: return@forEach
                // "OFF", "—", "Leave": a day someone is not working is not an event, and
                // writing one would put a shift in their calendar on their day off. Only a
                // full range counts — a lone start is not a shift, and inventing its length
                // is exactly what an assumption is for and this is not the place for one.
                val range = timeEngine.parse(cell.text).firstOrNull() ?: return@forEach
                val resolved = meridiem.resolve(
                    listOf(range.start),
                    context = listOfNotNull(range.end),
                ).firstOrNull() ?: return@forEach
                val end = range.end
                    ?.let { meridiem.resolve(listOf(it), context = listOf(range.start)).firstOrNull() }
                    ?: return@forEach
                val start = resolved.time ?: return@forEach
                val finish = end.time ?: return@forEach

                entries += ScheduleEntry(
                    id = EntryId("roster-$row-$column"),
                    title = Confident.High(name, sourceOf(nameCell)),
                    weekday = Confident.High(weekday, sourceOf(grid.row(headerRow)[column])),
                    startTime = Confident.High(start, sourceOf(cell)),
                    endTime = Confident.High(finish, sourceOf(cell)),
                )
            }
        }

        if (entries.isEmpty()) {
            return TimetableResult(Orientation.None, null, "no shifts in this roster")
        }

        return TimetableResult(
            orientation = Orientation.ColumnOriented,
            group = ScheduleGroup(
                id = GroupId(label.ifBlank { "roster" }),
                label = label,
                entries = dedupe(entries),
                source = sourceOf(grid.row(headerRow).first()),
                kind = ScheduleKind.Weekly,
            ),
        )
    }

    // ---- column-oriented: weekdays across the top, times down the side ----

    private fun buildColumnOriented(
        grid: Grid,
        label: String,
        sourceOf: (Cell) -> SourceReference,
    ): TimetableResult {
        val headerRow = (0 until grid.rowCount).maxBy { row ->
            grid.row(row).count { content.looksLikeWeekday(it.text) }
        }

        val weekdayColumns = grid.row(headerRow)
            .mapNotNull { cell -> weekdayOf(cell.text)?.let { cell.column to it } }
            .toMap()

        if (weekdayColumns.size < MIN_WEEKDAYS) {
            return TimetableResult(Orientation.ColumnOriented, null, "couldn't identify the weekday columns")
        }

        val timeColumn = (0 until grid.columnCount)
            .filterNot { it in weekdayColumns }
            .maxByOrNull { column ->
                grid.column(column).count { it.row != headerRow && content.looksLikeTime(it.text) }
            }
            ?: return TimetableResult(Orientation.ColumnOriented, null, "couldn't find the time column")

        // Only rows whose time cell actually holds a time are class rows. Titles,
        // sub-headings and spacer rows sit in the same columns and would otherwise become
        // entries named after the institution.
        //
        // A row whose time cell looks like a time but could not be resolved is kept, so it
        // surfaces as a question instead of being silently dropped.
        val bodyRows = (0 until grid.rowCount).filter { row ->
            row != headerRow && content.looksLikeTime(grid.cell(row, timeColumn)?.text.orEmpty())
        }
        val times = resolveTimeColumn(grid, timeColumn, bodyRows)

        if (times.values.none { it.start != null }) {
            return TimetableResult(Orientation.ColumnOriented, null, "couldn't read the times in this timetable")
        }

        val entries = mutableListOf<ScheduleEntry>()

        bodyRows.forEach { row ->
            val slot = times[row] ?: return@forEach
            weekdayColumns.forEach { (column, weekday) ->
                val cell = grid.cell(row, column) ?: return@forEach
                // An empty cell is a free period. It is simply absent — never an entry
                // with missing data.
                if (cell.isEmpty) return@forEach
                entries += entryFrom(cell, weekday, slot, sourceOf, grid, timeColumn, row, headerRow, column)
            }
        }

        if (entries.isEmpty()) {
            return TimetableResult(Orientation.ColumnOriented, null, "no classes found in this timetable")
        }

        return TimetableResult(
            orientation = Orientation.ColumnOriented,
            group = ScheduleGroup(
                id = GroupId(label.ifBlank { "schedule" }),
                label = label,
                entries = dedupe(entries),
                source = sourceOf(grid.row(headerRow).first()),
                // Weekly by construction: orientation detection has already established
                // weekdays across a row or down a column.
                kind = ScheduleKind.Weekly,
            ),
        )
    }

    private fun entryFrom(
        cell: Cell,
        weekday: DayOfWeek,
        slot: ResolvedSlot,
        sourceOf: (Cell) -> SourceReference,
        grid: Grid,
        timeColumn: Int,
        row: Int,
        headerRow: Int,
        weekdayColumn: Int,
    ): ScheduleEntry {
        val source = sourceOf(cell)
        val parsed = content.parseSubject(cell.text)
        val subject = parsed?.subject ?: cell.text

        val timeSource = grid.cell(row, timeColumn)?.let(sourceOf) ?: source
        val weekdaySource = grid.cell(headerRow, weekdayColumn)?.let(sourceOf) ?: source

        val startTime = slot.start
            ?.let { time ->
                scorer.derived(time, listOf(timeSource), "row-header", "read from the time column")
                    .let { if (slot.inferred) it.capAtMedium(slot.reason ?: "meridiem inferred") else it }
            }
            ?: Confident.Missing(slot.reason ?: "no start time for this row")

        return ScheduleEntry(
            id = EntryId("${weekday.name.lowercase()}-${cell.row}-${cell.column}"),
            title = scorer.score(subject, cell.runs, source),
            weekday = scorer.derived(weekday, listOf(weekdaySource), "column-header", "read from the column heading"),
            startTime = startTime,
            endTime = slot.end
                ?.let { scorer.derived(it, listOf(timeSource), "row-header", "read from the time column") }
                ?: Confident.Missing("this row's time didn't give an end"),
            location = parsed?.location
                ?.let { scorer.score(it, cell.runs, source) }
                ?: Confident.Missing("no room in this cell"),
            instructor = parsed?.instructor
                ?.let { scorer.score(it, cell.runs, source) }
                ?: Confident.Missing("no instructor in this cell"),
        )
    }

    // ---- period columns: weekdays down the side, times in the column headings ----

    private fun buildPeriodColumns(
        grid: Grid,
        label: String,
        sourceOf: (Cell) -> SourceReference,
    ): TimetableResult {
        val weekdayColumn = (0 until grid.columnCount).maxBy { column ->
            grid.column(column).count { content.looksLikeWeekday(it.text) }
        }
        val headerRow = periodHeaderRow(grid, weekdayColumn)
            ?: return TimetableResult(Orientation.PeriodColumns, null, "couldn't find the period headings")

        // Each period column contributes its own time, read from its heading. A heading
        // holding more than one period means two columns were merged during layout
        // detection and we cannot say which one a class belongs to — so those columns are
        // skipped rather than guessed at.
        val periods = mutableMapOf<Int, TimeRange>()
        val ambiguousColumns = mutableListOf<Int>()

        grid.row(headerRow).forEach { cell ->
            if (cell.column == weekdayColumn || cell.isEmpty) return@forEach
            val found = timeEngine.parse(cell.text).filter { it.end != null }
            when (found.size) {
                0 -> Unit
                1 -> periods[cell.column] = found.single()
                else -> ambiguousColumns += cell.column
            }
        }

        if (periods.isEmpty()) {
            return TimetableResult(Orientation.PeriodColumns, null, "couldn't read the period times")
        }

        // Every heading is one clock, so they resolve together.
        val starts = periods.values.map { it.start }
        val ends = periods.values.mapNotNull { it.end }
        val startByToken = starts.zip(meridiem.resolve(starts, context = ends)).toMap()
        val endByToken = ends.zip(meridiem.resolve(ends, context = starts)).toMap()

        val entries = mutableListOf<ScheduleEntry>()

        (0 until grid.rowCount).forEach { row ->
            if (row == headerRow) return@forEach
            val weekdayCell = grid.cell(row, weekdayColumn) ?: return@forEach
            val weekday = weekdayOf(weekdayCell.text) ?: return@forEach

            periods.forEach { (column, period) ->
                val cell = grid.cell(row, column) ?: return@forEach
                if (cell.isEmpty) return@forEach // a free period, simply absent

                val start = startByToken[period.start]
                val end = period.end?.let { endByToken[it] }
                val headerSource = grid.cell(headerRow, column)?.let(sourceOf) ?: sourceOf(cell)
                val source = sourceOf(cell)
                val parsed = content.parseSubject(cell.text)

                entries += ScheduleEntry(
                    id = EntryId("${weekday.name.lowercase()}-$row-$column"),
                    title = scorer.score(parsed?.subject ?: cell.text, cell.runs, source),
                    weekday = scorer.score(weekday, weekdayCell.runs, sourceOf(weekdayCell)),
                    startTime = start?.time
                        ?.let { time ->
                            scorer.derived(time, listOf(headerSource), "period-header", "read from the period heading")
                                .let { if (start.inferred) it.capAtMedium(start.reason ?: "meridiem inferred") else it }
                        }
                        ?: Confident.Missing(start?.reason ?: "this period has no start time"),
                    endTime = end?.time
                        ?.let { scorer.derived(it, listOf(headerSource), "period-header", "read from the period heading") }
                        ?: Confident.Missing("this period has no end time"),
                    location = parsed?.location
                        ?.let { scorer.score(it, cell.runs, source) }
                        ?: Confident.Missing("no room in this cell"),
                    instructor = parsed?.instructor
                        ?.let { scorer.score(it, cell.runs, source) }
                        ?: Confident.Missing("no instructor in this cell"),
                )
            }
        }

        if (entries.isEmpty()) {
            return TimetableResult(Orientation.PeriodColumns, null, "no classes found in this timetable")
        }

        val note = if (ambiguousColumns.isEmpty()) {
            null
        } else {
            "${ambiguousColumns.size} period column(s) couldn't be told apart and were skipped"
        }

        return TimetableResult(
            orientation = Orientation.PeriodColumns,
            group = ScheduleGroup(
                id = GroupId(label.ifBlank { "schedule" }),
                label = label,
                entries = dedupe(entries),
                source = sourceOf(grid.row(headerRow).first { !it.isEmpty }),
            ),
            reason = note,
        )
    }

    // ---- row-oriented: one entry per row ----

    private fun buildRowOriented(
        grid: Grid,
        label: String,
        sourceOf: (Cell) -> SourceReference,
    ): TimetableResult {
        val roles = (0 until grid.columnCount).associateWith { column ->
            content.classify(grid.column(column).map { it.text })
        }

        val weekdayColumn = roles.entries.firstOrNull { it.value == CellRole.Weekday }?.key
        val dateColumn = roles.entries.firstOrNull { it.value == CellRole.Date }?.key
        val timeColumn = roles.entries.firstOrNull { it.value == CellRole.Time }?.key
        val subjectColumn = roles.entries.firstOrNull { it.value == CellRole.Subject }?.key
        val locationColumn = roles.entries.firstOrNull { it.value == CellRole.Location }?.key

        if (subjectColumn == null || timeColumn == null || (weekdayColumn == null && dateColumn == null)) {
            return TimetableResult(Orientation.RowOriented, null, "couldn't work out what the columns mean")
        }

        val bodyRows = (0 until grid.rowCount).filter { row ->
            val cell = grid.cell(row, timeColumn)
            cell != null && content.looksLikeTime(cell.text)
        }

        val times = resolveTimeColumn(grid, timeColumn, bodyRows)
        val entries = mutableListOf<ScheduleEntry>()

        bodyRows.forEach { row ->
            val subjectCell = grid.cell(row, subjectColumn) ?: return@forEach
            if (subjectCell.isEmpty) return@forEach
            val slot = times[row] ?: return@forEach
            val source = sourceOf(subjectCell)

            val weekday = weekdayColumn
                ?.let { grid.cell(row, it) }
                ?.let { cell -> weekdayOf(cell.text)?.let { it to sourceOf(cell) } }

            val parsed = content.parseSubject(subjectCell.text)
            val locationText = locationColumn?.let { grid.cell(row, it)?.text }?.takeIf { it.isNotBlank() }
                ?: parsed?.location

            entries += ScheduleEntry(
                id = EntryId("row-$row"),
                title = scorer.score(parsed?.subject ?: subjectCell.text, subjectCell.runs, source),
                weekday = weekday
                    ?.let { (day, at) -> scorer.score(day, listOf(subjectCell.runs.first()), at) }
                    ?: Confident.Missing("no weekday on this row"),
                startTime = slot.start
                    ?.let { time ->
                        val timeSource = grid.cell(row, timeColumn)?.let(sourceOf) ?: source
                        scorer.score(time, grid.cell(row, timeColumn)?.runs.orEmpty(), timeSource)
                            .let { if (slot.inferred) it.capAtMedium(slot.reason ?: "meridiem inferred") else it }
                    }
                    ?: Confident.Missing(slot.reason ?: "no start time on this row"),
                endTime = slot.end
                    ?.let {
                        val timeSource = grid.cell(row, timeColumn)?.let(sourceOf) ?: source
                        scorer.score(it, grid.cell(row, timeColumn)?.runs.orEmpty(), timeSource)
                    }
                    ?: Confident.Missing("no end time on this row"),
                location = locationText
                    ?.let { scorer.score(it, subjectCell.runs, source) }
                    ?: Confident.Missing("no room on this row"),
            )
        }

        if (entries.isEmpty()) {
            return TimetableResult(Orientation.RowOriented, null, "no entries found in this list")
        }

        return TimetableResult(
            orientation = Orientation.RowOriented,
            group = ScheduleGroup(
                id = GroupId(label.ifBlank { "schedule" }),
                label = label,
                entries = dedupe(entries),
                // Point at content we actually extracted. The first body row's subject cell
                // can legitimately be empty, and asserting otherwise crashed on a real
                // academic calendar.
                source = entries.first().sources.firstOrNull()
                    ?: sourceOf(grid.row(bodyRows.first()).first()),
            ),
        )
    }

    // ---- shared ----

    private data class ResolvedSlot(
        val start: LocalTime?,
        val end: LocalTime?,
        val inferred: Boolean,
        val reason: String?,
    )

    /**
     * Reads a whole time column at once, because AM/PM can only be settled by looking at
     * the column as a sequence — one value in isolation never proves anything.
     */
    private fun resolveTimeColumn(grid: Grid, column: Int, rows: List<Int>): Map<Int, ResolvedSlot> {
        val parsed: Map<Int, TimeRange?> = rows.associateWith { row ->
            grid.cell(row, column)?.text?.let { timeEngine.parse(it).firstOrNull() }
        }

        val starts = parsed.values.filterNotNull().map { it.start }
        val ends = parsed.values.mapNotNull { it?.end }

        // Starts and ends share a clock notation, so each constrains the other.
        val startByToken = starts.zip(meridiem.resolve(starts, context = ends)).toMap()
        val endByToken = ends.zip(meridiem.resolve(ends, context = starts)).toMap()

        return rows.associateWith { row ->
            val range = parsed[row]
            if (range == null) {
                ResolvedSlot(null, null, false, "no time on this row")
            } else {
                val start = startByToken[range.start]
                val end = range.end?.let { endByToken[it] }
                ResolvedSlot(
                    start = start?.time,
                    end = end?.time,
                    inferred = start?.inferred == true || end?.inferred == true,
                    reason = start?.reason ?: end?.reason,
                )
            }
        }
    }

    /**
     * Collapses entries that are identical in every meaningful field. A grid cell repeated
     * because of a merge should not become two events.
     */
    private fun dedupe(entries: List<ScheduleEntry>): List<ScheduleEntry> =
        entries.distinctBy { entry ->
            listOf(
                entry.title.let { (it as? Confident.High)?.value ?: (it as? Confident.Medium)?.value },
                entry.weekday.let { (it as? Confident.High)?.value ?: (it as? Confident.Medium)?.value },
                entry.startTime.let { (it as? Confident.High)?.value ?: (it as? Confident.Medium)?.value },
                entry.endTime.let { (it as? Confident.High)?.value ?: (it as? Confident.Medium)?.value },
            )
        }

    private fun weekdayOf(text: String): DayOfWeek? =
        dateEngine.parse(text.trim()).firstOrNull { it.weekday != null && it.readings.isEmpty() }?.weekday

    private companion object {
        /** Below this, a row of weekday-ish words is more likely to be prose. */
        const val MIN_WEEKDAYS = 2

        /** Below this, a row of time-ish cells is more likely to be a sentence. */
        const val MIN_PERIODS = 3

        /** Dates spread over this many rows mean a dated schedule, not a weekly one. */
        const val MAX_DATED_ROWS_FOR_WEEKLY = 3

        /** Fraction of entries that must name a weekday for a weekly reading to be credible. */
        const val MIN_WEEKDAY_COVERAGE = 0.6f

        /** A believable week: this many entries, across this many days and time slots. */
        const val MIN_ENTRIES = 3
        const val MIN_DISTINCT_WEEKDAYS = 2
        const val MIN_NAME_LETTERS = 2

        /** "AFL" and "Viva" are subjects; "7142128" and "Su 18152229" are day numbers. */
        const val MIN_SUBJECT_LENGTH = 2
        const val MIN_SUBJECT_LETTERS = 3
        const val MIN_SUBJECT_LETTER_SHARE = 0.5f

        const val MIN_DISTINCT_TIMES = 2
    }
}
