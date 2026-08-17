package com.okayanshul.docaction.extraction.timetable

import com.google.common.truth.Truth.assertThat
import com.okayanshul.docaction.domain.BoundingBox
import com.okayanshul.docaction.domain.Confident
import com.okayanshul.docaction.domain.SourceReference
import com.okayanshul.docaction.domain.TextOrigin
import com.okayanshul.docaction.domain.TextRun
import com.okayanshul.docaction.domain.valueOrNull
import com.okayanshul.docaction.extraction.table.Cell
import com.okayanshul.docaction.extraction.table.TableBuilder
import java.time.DayOfWeek
import java.time.LocalTime
import org.junit.Test

class TimetableBuilderTest {

    private val tables = TableBuilder()
    private val builder = TimetableBuilder()

    private fun sourceOf(cell: Cell): SourceReference =
        SourceReference.PdfSpan(1, cell.bounds ?: BoundingBox(0f, 0f, 0f, 0f))

    private fun run(text: String, col: Int, row: Int): TextRun {
        val left = col * 120f
        val top = row * 20f
        return TextRun(
            text = text,
            bounds = BoundingBox(left, top, left + 90f, top + 12f),
            confidence = null,
            origin = TextOrigin.PdfTextLayer,
        )
    }

    /** The classic academic grid: weekdays across the top, times down the side. */
    private fun columnOrientedRuns() = listOf(
        run("Time", 0, 0), run("Monday", 1, 0), run("Tuesday", 2, 0), run("Wednesday", 3, 0),
        run("09:00-10:00", 0, 1), run("DSA / K10", 1, 1), run("OS / K11", 2, 1), run("DBMS / K10", 3, 1),
        run("10:00-11:00", 0, 2), run("OS / K11", 1, 2), run("DSA / K10", 2, 2), run("Java / K12", 3, 2),
    )

    private fun build(runs: List<TextRun>, label: String = "Semester 5 Section B"): TimetableResult {
        val grid = tables.build(runs) ?: error("no grid built")
        return builder.build(grid, label, ::sourceOf)
    }

    // --- orientation ---

    @Test
    fun `weekdays across the top means the grid is column oriented`() {
        assertThat(build(columnOrientedRuns()).orientation).isEqualTo(Orientation.ColumnOriented)
    }

    @Test
    fun `weekdays down the side means the grid is row oriented`() {
        val runs = listOf(
            run("Day", 0, 0), run("Time", 1, 0), run("Subject", 2, 0), run("Room", 3, 0),
            run("Monday", 0, 1), run("09:00-10:00", 1, 1), run("Data Structures", 2, 1), run("K10", 3, 1),
            run("Tuesday", 0, 2), run("10:00-11:00", 1, 2), run("Operating Systems", 2, 2), run("K11", 3, 2),
            run("Wednesday", 0, 3), run("11:00-12:00", 1, 3), run("Databases", 2, 3), run("K12", 3, 3),
        )
        assertThat(build(runs).orientation).isEqualTo(Orientation.RowOriented)
    }

    @Test
    fun `text with no weekdays is not a timetable`() {
        val runs = listOf(
            run("Notice", 0, 0), run("Board", 1, 0),
            run("Fees due", 0, 1), run("soon", 1, 1),
        )
        val result = build(runs)
        assertThat(result.orientation).isEqualTo(Orientation.None)
        assertThat(result.group).isNull()
    }

    // --- column-oriented extraction ---

    @Test
    fun `a column oriented grid produces one entry per populated cell`() {
        val group = build(columnOrientedRuns()).group!!

        assertThat(group.entries).hasSize(6)
        assertThat(group.label).isEqualTo("Semester 5 Section B")
    }

    @Test
    fun `the weekday comes from the column header and the time from the row header`() {
        val group = build(columnOrientedRuns()).group!!

        val monday9 = group.entries.first {
            it.weekday.valueOrNull == DayOfWeek.MONDAY && it.startTime.valueOrNull == LocalTime.of(9, 0)
        }

        assertThat(monday9.title.valueOrNull).isEqualTo("DSA")
        assertThat(monday9.location.valueOrNull).isEqualTo("K10")
        assertThat(monday9.endTime.valueOrNull).isEqualTo(LocalTime.of(10, 0))
    }

    @Test
    fun `structurally derived fields never claim high confidence`() {
        val group = build(columnOrientedRuns()).group!!
        val entry = group.entries.first()

        // The weekday was read from a column heading, not from the cell itself.
        assertThat(entry.weekday).isInstanceOf(Confident.Medium::class.java)
        assertThat(entry.weekday.sourceOrNullIsDerived()).isTrue()
        // The subject was read directly.
        assertThat(entry.title).isInstanceOf(Confident.High::class.java)
    }

    private fun Confident<*>.sourceOrNullIsDerived(): Boolean =
        (this as? Confident.Medium)?.source is SourceReference.Derived

    @Test
    fun `an empty cell is a free period and produces no entry`() {
        val runs = columnOrientedRuns().filterNot { it.text == "OS / K11" && it.bounds.left == 240f }
        val group = build(runs).group!!

        // Tuesday 09:00 was removed; five entries remain, and none of them is a
        // Tuesday-nine-o'clock entry with missing data.
        assertThat(group.entries).hasSize(5)
        assertThat(
            group.entries.none {
                it.weekday.valueOrNull == DayOfWeek.TUESDAY && it.startTime.valueOrNull == LocalTime.of(9, 0)
            }
        ).isTrue()
    }

    @Test
    fun `room and subject are separated regardless of which comes first`() {
        val runs = columnOrientedRuns().map {
            if (it.text == "DSA / K10" && it.bounds.top == 20f && it.bounds.left == 120f) {
                it.copy(text = "K10 / DSA")
            } else {
                it
            }
        }
        val group = build(runs).group!!

        val monday9 = group.entries.first {
            it.weekday.valueOrNull == DayOfWeek.MONDAY && it.startTime.valueOrNull == LocalTime.of(9, 0)
        }
        assertThat(monday9.title.valueOrNull).isEqualTo("DSA")
        assertThat(monday9.location.valueOrNull).isEqualTo("K10")
    }

    // --- row-oriented extraction ---

    @Test
    fun `a row oriented list produces one entry per row`() {
        val runs = listOf(
            run("Day", 0, 0), run("Time", 1, 0), run("Subject", 2, 0), run("Room", 3, 0),
            run("Monday", 0, 1), run("09:00-10:00", 1, 1), run("Data Structures", 2, 1), run("K10", 3, 1),
            run("Tuesday", 0, 2), run("10:00-11:00", 1, 2), run("Operating Systems", 2, 2), run("K11", 3, 2),
            run("Wednesday", 0, 3), run("11:00-12:00", 1, 3), run("Databases", 2, 3), run("K12", 3, 3),
        )

        val group = build(runs).group!!

        assertThat(group.entries).hasSize(3)
        val first = group.entries.first()
        assertThat(first.title.valueOrNull).isEqualTo("Data Structures")
        assertThat(first.weekday.valueOrNull).isEqualTo(DayOfWeek.MONDAY)
        assertThat(first.startTime.valueOrNull).isEqualTo(LocalTime.of(9, 0))
        assertThat(first.location.valueOrNull).isEqualTo("K10")
    }

    // --- the AM/PM rule, end to end through a grid ---

    @Test
    fun `an academic column wrapping past noon resolves across the whole timetable`() {
        val runs = listOf(
            run("Time", 0, 0), run("Monday", 1, 0), run("Tuesday", 2, 0),
            run("11:00", 0, 1), run("DSA", 1, 1), run("OS", 2, 1),
            run("12:00", 0, 2), run("OS", 1, 2), run("DSA", 2, 2),
            run("1:00", 0, 3), run("DBMS", 1, 3), run("Java", 2, 3),
            run("2:00", 0, 4), run("Java", 1, 4), run("DBMS", 2, 4),
        )

        val group = build(runs).group!!
        val starts = group.entries.mapNotNull { it.startTime.valueOrNull }.distinct().sorted()

        assertThat(starts).containsExactly(
            LocalTime.of(11, 0), LocalTime.of(12, 0), LocalTime.of(13, 0), LocalTime.of(14, 0),
        ).inOrder()
    }

    @Test
    fun `an unresolvable time column leaves start times missing rather than guessed`() {
        val runs = listOf(
            run("Time", 0, 0), run("Monday", 1, 0), run("Tuesday", 2, 0),
            run("8:00", 0, 1), run("DSA", 1, 1), run("OS", 2, 1),
            run("9:00", 0, 2), run("OS", 1, 2), run("DSA", 2, 2),
        )

        val result = build(runs)

        // 8, 9 reads equally well as morning or evening, and two rows is not a sequence.
        // Nothing may be invented, so the timetable reports that it could not be read.
        assertThat(result.group?.entries?.all { it.startTime is Confident.Missing } ?: true).isTrue()
    }

    @Test
    fun `a single day is not a week, however it was found`() {
        // One weekday and nothing else is what a stray table looks like, and reading it as
        // a weekly schedule is how a transit timetable became a recurring "HOLIDAY SERVICE".
        // This holds even when the user cropped to it — see isPlausibleWeek.
        val oneDay = listOf(
            run("Time", 0, 0), run("Monday", 1, 0),
            run("09:00-10:00", 0, 1), run("DSA / K10", 1, 1),
            run("10:00-11:00", 0, 2), run("OS / K11", 1, 2),
            run("11:00-12:00", 0, 3), run("DBMS / K10", 1, 3),
        )
        assertThat(build(oneDay).group).isNull()
    }

    // --- rosters: people down the side, hours in the cells ---

    private fun rosterRuns() = listOf(
        run("Staff", 0, 0), run("Monday", 1, 0), run("Tuesday", 2, 0), run("Wednesday", 3, 0),
        run("A. Nair", 0, 1), run("07:00-15:00", 1, 1), run("07:00-15:00", 2, 1), run("OFF", 3, 1),
        run("R. Iyer", 0, 2), run("15:00-23:00", 1, 2), run("OFF", 2, 2), run("07:00-15:00", 3, 2),
        run("M. Das", 0, 3), run("23:00-07:00", 1, 3), run("23:00-07:00", 2, 3), run("15:00-23:00", 3, 3),
    )

    @Test
    fun `a roster becomes one recurring shift per person per working day`() {
        // Every other reading wants a column of times down the side. A roster has names
        // there and the hours inside the grid, so a real nursing roster produced nothing at
        // all until this shape was recognised.
        val group = build(rosterRuns(), label = "Duty roster").group

        assertThat(group).isNotNull()
        // Nine cells hold hours; three say OFF.
        assertThat(group!!.entries).hasSize(7)
        assertThat(group.entries.mapNotNull { it.title.valueOrNull }.distinct())
            .containsExactly("A. Nair", "R. Iyer", "M. Das")
    }

    @Test
    fun `a day someone is not working is never an event`() {
        val group = build(rosterRuns(), label = "Duty roster").group!!

        // Writing a shift on someone's day off is worse than missing one: they would plan
        // around a shift that does not exist.
        val nairDays = group.entries
            .filter { it.title.valueOrNull == "A. Nair" }
            .mapNotNull { it.weekday.valueOrNull }
        assertThat(nairDays).containsExactly(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.TUESDAY)
    }

    @Test
    fun `an overnight shift keeps both ends as stated`() {
        val overnight = build(rosterRuns(), label = "Duty roster").group!!
            .entries.first { it.title.valueOrNull == "M. Das" }

        // 23:00 to 07:00 crosses midnight, which is legal and is what a night shift is.
        // Reordering it, or refusing it, would both be wrong.
        assertThat(overnight.startTime.valueOrNull).isEqualTo(java.time.LocalTime.of(23, 0))
        assertThat(overnight.endTime.valueOrNull).isEqualTo(java.time.LocalTime.of(7, 0))
    }
}
