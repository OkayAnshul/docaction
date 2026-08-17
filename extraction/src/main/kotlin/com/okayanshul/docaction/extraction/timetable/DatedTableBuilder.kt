package com.okayanshul.docaction.extraction.timetable

import com.okayanshul.docaction.domain.Confident
import com.okayanshul.docaction.domain.DateOrder
import com.okayanshul.docaction.domain.EntryId
import com.okayanshul.docaction.domain.GroupId
import com.okayanshul.docaction.domain.ScheduleEntry
import com.okayanshul.docaction.domain.ScheduleGroup
import com.okayanshul.docaction.domain.ScheduleKind
import com.okayanshul.docaction.domain.SourceReference
import com.okayanshul.docaction.domain.capAtMedium
import com.okayanshul.docaction.domain.valueOrNull
import com.okayanshul.docaction.extraction.date.DateEngine
import com.okayanshul.docaction.extraction.table.Cell
import com.okayanshul.docaction.extraction.table.Grid
import com.okayanshul.docaction.extraction.time.MeridiemResolver
import com.okayanshul.docaction.extraction.time.TimeEngine
import java.time.LocalDate
import java.time.LocalTime

/**
 * Reads a table whose rows are **dated occasions** rather than weekly slots.
 *
 * The gap this fills was invisible until the corpus reported per-document counts. An
 * interview call letter, a lab appointment, a visa letter and an exam schedule are all
 * tables — `Round | Date | Time | Venue`, `Test | Date | Reporting time | Location` — and
 * every one of them was falling through to the prose path, which reads a line at a time and
 * therefore produced titles like `HR :30 Conference` from a row split across three physical
 * lines.
 *
 * [TimetableBuilder] correctly refuses them: it looks for weekdays, and these have none. It
 * is right to refuse, because reading a dated table as a weekly one is exactly how a
 * conference programme became a recurring class. So this is a sibling, not a change to it —
 * same grid, different question: *does a column hold dates?*
 *
 * Everything is read from the header row, and a column that isn't headed isn't used. That is
 * what keeps a stray figure out of a title.
 */
class DatedTableBuilder(
    private val dates: DateEngine = DateEngine(),
    private val times: TimeEngine = TimeEngine(),
    private val meridiem: MeridiemResolver = MeridiemResolver(),
) {

    data class Result(
        val group: ScheduleGroup?,
        val reason: String? = null,
        /** An ambiguous date we refused to read, so the pipeline can ask about it. */
        val ambiguous: Ambiguity? = null,
    )

    /** `05/10/2026` is 5 October or 10 May, and this document does not say which. */
    data class Ambiguity(val example: String, val dayFirst: LocalDate, val monthFirst: LocalDate)

    fun build(
        grid: Grid,
        label: String,
        assumedYear: Int? = null,
        /** Once the user has said which way round dates are written, we simply apply it. */
        order: DateOrder? = null,
        sourceOf: (Cell) -> SourceReference,
    ): Result {
        if (grid.rowCount < 2 || grid.columnCount < 2) {
            return Result(null, "this isn't a table")
        }

        val header = headerRow(grid) ?: return Result(null, "no header row naming a date column")
        val roles = columnRoles(grid, header)
        val dateColumn = roles.entries.firstOrNull { it.value == Role.Date }?.key
            ?: return Result(null, "no column holds dates")

        val perRow = ((header + 1) until grid.rowCount).mapNotNull { row ->
            entryFor(grid, row, dateColumn, roles, assumedYear, order, sourceOf)
        }

        // A table with one record and several dated *columns* is a different animal: a bill,
        // a ticket, a booking. Its header does not label rows, it labels fields — so the
        // event's name is the column heading ("Due date", "Departure", "Check-in") and there
        // is one event per dated column, not per row. Reading these row-wise produced
        // nothing at all, because the "subject" column holds a billing period or an amount.
        val entries = perRow.ifEmpty {
            fieldEntries(grid, header, roles, assumedYear, order, sourceOf)
        }

        if (entries.isEmpty()) {
            // Before reporting "no readable dates", check whether the real answer is that we
            // could read them and refused. A train ticket whose only date is `05/10/2026`
            // deserves a question, not silence.
            ambiguityIn(grid, header, dateColumn, assumedYear)?.let {
                return Result(null, "this document's date order is ambiguous", it)
            }
        }

        // One row is enough here, unlike in the weekly reader. The difference is evidence:
        // a header row that names a date column and a subject column is a *stated* table
        // structure, so a single row under it is a genuine single appointment — which is
        // exactly what a lab test or a visa slot is. The weekly reader needs several rows
        // because it is inferring a pattern; this one is reading a declaration.
        if (entries.isEmpty()) {
            return Result(null, "the date column held no readable dates")
        }

        return Result(
            ScheduleGroup(
                id = GroupId(label.ifBlank { "schedule" }),
                label = label,
                entries = entries,
                source = sourceOf(grid.row(header).first()),
                // Dated occasions, not a weekly pattern — which is what tells the pipeline
                // these may become all-day items and which reminder ladder they get.
                kind = ScheduleKind.Event,
            ),
        )
    }

    /**
     * One event per dated column, named by that column's heading.
     *
     * Only attempted when the row-wise reading found nothing, so a genuine row-per-event
     * table is never reinterpreted this way. Bounded to a couple of data rows: more than
     * that and the table is a list, and a list whose subject column we could not read is a
     * table we do not understand rather than one to guess at.
     */
    private fun fieldEntries(
        grid: Grid,
        header: Int,
        roles: Map<Int, Role>,
        assumedYear: Int?,
        order: DateOrder?,
        sourceOf: (Cell) -> SourceReference,
    ): List<ScheduleEntry> {
        // Records only. A footnote under the table — "Free cancellation until 16/09/2026" —
        // sits in a single cell and contains a date, and reading it as a field produced a
        // check-out two days before the check-in. A record fills most of its columns; a
        // note fills one.
        val headerWidth = grid.row(header).count { !it.isEmpty }
        val dataRows = ((header + 1) until grid.rowCount)
            .filter { row -> grid.row(row).count { !it.isEmpty } * 2 >= headerWidth }
        if (dataRows.isEmpty() || dataRows.size > MAX_FIELD_TABLE_ROWS) return emptyList()

        val subject = documentHeading(grid, header)

        return dataRows.flatMap { row ->
            grid.row(row).filter { roles[it.column] == Role.Date && !it.isEmpty }
                .mapNotNull { cell ->
                    val date = readDate(cell.text, assumedYear, order) ?: return@mapNotNull null
                    val field = readable(grid.cell(header, cell.column)?.text?.trim().orEmpty())
                    if (field.count { it.isLetter() } < MIN_TITLE_LETTERS) return@mapNotNull null

                    // A cell can hold both, joined without a space when the original wrapped:
                    // "18/09/202614:35" is a departure date *and* time.
                    val range = times.parse(separated(cell.text)).firstOrNull()
                    val start = range?.let {
                        meridiem.resolve(listOf(it.start), context = listOfNotNull(it.end)).firstOrNull()
                    }

                    ScheduleEntry(
                        id = EntryId("field-$row-${cell.column}"),
                        title = Confident.High(
                            subject?.let { "$it · ${field.replaceFirstChar(Char::titlecase)}" }
                                ?: field.replaceFirstChar(Char::titlecase),
                            sourceOf(grid.cell(header, cell.column) ?: cell),
                        ),
                        date = Confident.High(date, sourceOf(cell)),
                        startTime = start?.time
                            ?.let { Confident.High(it, sourceOf(cell)) }
                            ?: Confident.Missing("this field gave no time"),
                        endTime = Confident.Missing("this field gave no end time"),
                    )
                }
        }
            // A field table often repeats its date in a footnote row — "Last date for
            // payment: 18/09/2026" under a "Due date" column — and one bill should not
            // produce the same event twice.
            .distinctBy { entry ->
                listOf(
                    entry.title.valueOrNull,
                    entry.date.valueOrNull,
                    entry.startTime.valueOrNull,
                )
            }
    }

    /**
     * A header cell as a person would write it.
     *
     * Two runs stacked in one narrow column — "Due" above "date" — are joined by the grid
     * without a space, because for a wrapped *word* ("Subje"/"ct") that is exactly right.
     * For a wrapped *phrase* it produces "Duedate". Splitting is limited to the field words
     * this builder already recognises, so it can only ever correct a heading it understands.
     */
    private fun readable(header: String): String {
        var text = header
        SPLITTABLE.forEach { word ->
            text = Regex("(?<=[a-z])($word)\\b", RegexOption.IGNORE_CASE)
                .replace(text) { " " + it.groupValues[1] }
        }
        return text.replace(Regex("\\s+"), " ").trim()
    }

    /**
     * What the document calls itself, so a field is not filed as a bare "Due date".
     *
     * Taken from above the table and cut at the first dash — an invoice heading is normally
     * "Electricity Bill — Consumer 4471-90233", and the consumer number is noise in a
     * calendar entry.
     */
    private fun documentHeading(grid: Grid, header: Int): String? = (0 until header)
        .flatMap { grid.row(it) }
        .map { it.text.trim() }
        .firstOrNull { it.count { ch -> ch.isLetter() } >= MIN_HEADING_LETTERS }
        ?.substringBefore('—')
        ?.substringBefore(" - ")
        ?.trim()
        ?.takeIf { it.count { ch -> ch.isLetter() } >= MIN_HEADING_LETTERS }

    /**
     * The header is the topmost row that names a date column and at least one other.
     *
     * Found by content rather than by position: these documents put a title and a greeting
     * above the table, so row 0 is almost never the header.
     */
    private fun headerRow(grid: Grid): Int? = (0 until grid.rowCount - 1).firstOrNull { row ->
        val cells = grid.row(row).filterNot { it.isEmpty }
        cells.size >= 2 && cells.any { roleOf(it.text) == Role.Date }
    }

    private fun columnRoles(grid: Grid, header: Int): Map<Int, Role> =
        grid.row(header).associate { it.column to roleOf(it.text) }

    private fun entryFor(
        grid: Grid,
        row: Int,
        dateColumn: Int,
        roles: Map<Int, Role>,
        assumedYear: Int?,
        order: DateOrder?,
        sourceOf: (Cell) -> SourceReference,
    ): ScheduleEntry? {
        val dateCell = grid.cell(row, dateColumn) ?: return null
        val date = readDate(dateCell.text, assumedYear, order) ?: return null

        // The subject comes from a column the header did not claim for a date, time or
        // place — normally the first, and normally the one that names the thing.
        val subjectCell = grid.row(row)
            .filterNot { it.isEmpty }
            .firstOrNull { roles[it.column] == Role.Subject }
            ?: return null

        // A currency amount, a revision number or a bare figure is not the name of an
        // event. Without this an insurance premium produced "INR 24,780" and a scanned
        // form's footer produced "Rev.: 00 Date" — both flagged, both meaningless, and both
        // the kind of row that teaches a user to stop reading the flag.
        val title = subjectCell.text.trim()
        val letters = title.count { it.isLetter() }
        if (title.length < MIN_TITLE_LENGTH || letters < MIN_TITLE_LETTERS) return null
        if (letters.toFloat() / title.count { !it.isWhitespace() } < MIN_LETTER_SHARE) return null
        // A header word repeated in the body is the table's own scaffolding leaking in.
        if (roleOf(title) != Role.Subject) return null

        val timeCell = grid.row(row).firstOrNull { roles[it.column] == Role.Time && !it.isEmpty }
        val range = timeCell?.let { times.parse(separated(it.text)).firstOrNull() }
        val start = range?.let {
            meridiem.resolve(listOf(it.start), context = listOfNotNull(it.end)).firstOrNull()
        }

        val placeCell = grid.row(row).firstOrNull { roles[it.column] == Role.Place && !it.isEmpty }

        return ScheduleEntry(
            id = EntryId("dated-$row"),
            title = Confident.High(title, sourceOf(subjectCell)),
            date = Confident.High(date, sourceOf(dateCell)),
            startTime = start?.time
                ?.let { time ->
                    val scored: Confident<LocalTime> = Confident.High(time, sourceOf(timeCell!!))
                    if (start.inferred) scored.capAtMedium(start.reason ?: "meridiem inferred") else scored
                }
                ?: Confident.Missing("this row gave no time"),
            // Never synthesised. A row that states one time states a start, not a range, and
            // the choke point decides what to do about that.
            endTime = range?.end
                ?.let { end ->
                    meridiem.resolve(listOf(end), context = listOf(range.start)).firstOrNull()?.time
                }
                ?.let { Confident.High(it, sourceOf(timeCell!!)) }
                ?: Confident.Missing("this row gave no end time"),
            location = placeCell
                ?.let { Confident.High(it.text.trim(), sourceOf(it)) }
                ?: Confident.Missing("this row gave no location"),
        )
    }

    /**
     * Separates a date from a time that has been glued to it.
     *
     * A ticket's departure cell wraps as "18/09/2026" above "14:35", and the grid joins two
     * stacked runs without a space — correctly, because that same rule is what reassembles
     * "08/10/" + "2026". Joined, "18/09/202614:35" parses as neither a date nor a time, so
     * an itinerary produced nothing at all.
     *
     * Narrow on purpose: only a four-digit year immediately followed by something shaped
     * like a clock time. Anything looser would start splitting real numbers.
     */
    private fun separated(text: String): String =
        text.replace(Regex("""(\d{4})(\d{1,2}:\d{2})"""), "$1 $2")

    private fun readDate(rawText: String, assumedYear: Int?, order: DateOrder?): LocalDate? {
        val readings = readingsIn(rawText) ?: return null
        // One reading is no question at all. Two is a question — answered here only if the
        // user has already answered it, never settled by us.
        readings.singleOrNull()?.let { return it.toLocalDate(assumedYear) }
        // Positional, because that is the contract DateEngine states: for an ambiguous
        // numeric date it emits the day-first reading first, and DateResolver already relies
        // on it. Choosing by month value instead — which is what this did — is not a function
        // of the order at all: `05/10` puts day-first at month 10 and `10/05` puts it at
        // month 5, so one of the two was always resolved backwards. Someone answering
        // "day first" got the month-first date, months away, presented as settled.
        return when (order) {
            DateOrder.DayFirst -> readings.first()
            DateOrder.MonthFirst -> readings.last()
            null -> null
        }?.toLocalDate(assumedYear)
    }

    private fun readingsIn(rawText: String): List<com.okayanshul.docaction.extraction.date.DateReading>? {
        val match = dates.parse(separated(rawText)).firstOrNull { it.readings.isNotEmpty() }
            ?: return null
        return match.readings.filter { it.isCalendarValid }.distinct().ifEmpty { null }
    }

    /** The first cell in the date column that has two readings and no way to choose. */
    private fun ambiguityIn(
        grid: Grid,
        header: Int,
        dateColumn: Int,
        assumedYear: Int?,
    ): Ambiguity? = ((header + 1) until grid.rowCount)
        .mapNotNull { row -> grid.cell(row, dateColumn) }
        .filterNot { it.isEmpty }
        .firstNotNullOfOrNull { cell ->
            val readings = readingsIn(cell.text) ?: return@firstNotNullOfOrNull null
            if (readings.size < 2) return@firstNotNullOfOrNull null
            // Day-first reads the smaller month; month-first the larger. Both are real dates
            // the document could mean, and the user is shown exactly those two.
            val dayFirst = readings.minByOrNull { it.month }?.toLocalDate(assumedYear)
            val monthFirst = readings.maxByOrNull { it.month }?.toLocalDate(assumedYear)
            if (dayFirst == null || monthFirst == null || dayFirst == monthFirst) {
                null
            } else {
                Ambiguity(cell.text.trim(), dayFirst, monthFirst)
            }
        }

    private enum class Role { Date, Time, Place, Subject }

    private fun roleOf(header: String): Role {
        val text = header.lowercase().replace(Regex("[^a-z]"), "")
        return when {
            // Time before date: "reporting time" and "departure time" contain both cues.
            TIME_WORDS.any { it in text } -> Role.Time
            DATE_WORDS.any { it in text } -> Role.Date
            PLACE_WORDS.any { it in text } -> Role.Place
            else -> Role.Subject
        }
    }

    private companion object {
        const val MIN_TITLE_LENGTH = 3
        const val MIN_TITLE_LETTERS = 3

        /** Below this a cell is a figure with stray letters, not a name. */
        const val MIN_LETTER_SHARE = 0.5f

        /** More rows than this and a field-wise reading is a guess, not a shape. */
        const val MAX_FIELD_TABLE_ROWS = 3

        const val MIN_HEADING_LETTERS = 4

        /** Trailing words that a wrapped header cell glues onto the one before it. */
        val SPLITTABLE = listOf("date", "time", "day", "period", "ends", "payable")

        // No bare "on" or "day": both appear inside ordinary words, and a substring match
        // would make "Monday" or "Location" a date column.
        //
        // The travel and booking words are here because a ticket's date column is headed
        // "Departure", never "Date" — which is why an itinerary produced nothing at all
        // until this list grew.
        val DATE_WORDS = setOf(
            "date", "dated", "schedule", "departure", "arrival", "checkin", "checkout",
            "validtill", "validuntil", "expiry", "expires", "commences", "deadline",
            "duedate", "due", "journey", "issue",
        )
        // "Reportingtime" arrives as one word: a wrapped header cell whose two runs the
        // grid joined without a space. Matching on substrings rather than whole words is
        // what makes wrapped headers legible.
        val TIME_WORDS = setOf("time", "timing", "timings", "hours", "slot", "reporting")
        val PLACE_WORDS = setOf(
            "venue", "location", "place", "room", "hall", "centre", "center", "address",
            "block", "where", "platform", "gate",
        )
    }
}
