package com.okayanshul.docaction.document.spreadsheet

import com.google.common.truth.Truth.assertThat
import com.okayanshul.docaction.domain.PipelineAnswers
import com.okayanshul.docaction.domain.PipelineQuestion
import com.okayanshul.docaction.domain.valueOrNull
import java.time.LocalDate
import org.junit.Test

/**
 * The shapes a sheet can take, and which reader each one needs.
 *
 * Three of these four shapes produced **nothing at all** before: the corpus was 42 documents
 * silent on spreadsheets and CSVs, and the cause was that detection could only recognise a
 * period grid or a weekday-per-row list. A sheet keyed on calendar dates, and the ordinary
 * school timetable with weekdays across the top, were both invisible.
 *
 * These are written as shapes rather than as files on purpose. A real workbook proves the
 * whole path works; this proves *which* structural rule fires, so a regression names itself.
 */
class SheetShapesTest {

    private val schedules = SpreadsheetSchedules()

    private fun sheetOf(name: String, rows: List<List<String>>): Workbook {
        val cells = rows.flatMapIndexed { row, values ->
            values.mapIndexed { column, text -> SheetCell(row, column, text) }
        }
        return Workbook(listOf(SheetGrid.of(name, hidden = false, cells = cells)))
    }

    private fun build(workbook: Workbook, answers: PipelineAnswers = PipelineAnswers()) =
        schedules.detect(workbook).let { detected ->
            detected.singleOrNull()?.let { schedules.build(workbook, it, answers) }
        }

    @Test
    fun `a sheet keyed on dates yields one event per row`() {
        val workbook = sheetOf(
            "events",
            listOf(
                listOf("Event", "Date", "Time"),
                listOf("Sprint demo", "25/03/2026", "10:00"),
                listOf("Retro", "26/03/2026", "15:30"),
            ),
        )

        val detected = schedules.detect(workbook).single()
        assertThat(detected.shape).isEqualTo(ScheduleShape.Dated)
        // Non-zero, or the pipeline drops the group before it is ever built.
        assertThat(detected.entryCount).isGreaterThan(0)

        val entries = build(workbook)!!.groups.single().entries
        assertThat(entries).hasSize(2)
        assertThat(entries.map { it.title.valueOrNull }).containsExactly("Sprint demo", "Retro")
        assertThat(entries.first().date.valueOrNull).isEqualTo(LocalDate.of(2026, 3, 25))
    }

    @Test
    fun `the ordinary timetable has weekdays across the top and times down the side`() {
        val workbook = sheetOf(
            "week",
            listOf(
                listOf("Time", "Monday", "Tuesday", "Wednesday"),
                listOf("09:00-10:00", "Maths", "Physics", "Maths"),
                listOf("10:00-11:00", "Physics", "Maths", "Chemistry"),
            ),
        )

        val detected = schedules.detect(workbook).single()
        // A weekly grid, not a dated one — this shape must never reach the dated reader.
        assertThat(detected.shape).isEqualTo(ScheduleShape.Weekly)

        val entries = build(workbook)!!.groups.single().entries
        assertThat(entries).hasSize(6)
        assertThat(entries.mapNotNull { it.title.valueOrNull }).contains("Maths")
    }

    @Test
    fun `a weekday column still reads as a weekly list`() {
        val workbook = sheetOf(
            "flat",
            listOf(
                listOf("Day", "Time", "Subject", "Room"),
                listOf("Monday", "09:00-10:00", "Data Structures", "K10"),
                listOf("Tuesday", "10:00-11:00", "Operating Systems", "K11"),
                listOf("Wednesday", "11:00-12:00", "DBMS", "K12"),
            ),
        )

        assertThat(schedules.detect(workbook).single().shape).isEqualTo(ScheduleShape.Weekly)
        assertThat(build(workbook)!!.groups.single().entries).hasSize(3)
    }

    @Test
    fun `a month calendar of day numbers names nothing and is refused`() {
        // Day numbers are not subjects. This grid once produced four fabricated events, and
        // the guard that stopped it must survive every change to detection.
        val workbook = sheetOf(
            "month",
            listOf(
                listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su"),
                listOf("", "", "1", "2", "3", "4", "5"),
                listOf("6", "7", "8", "9", "10", "11", "12"),
                listOf("13", "14", "15", "16", "17", "18", "19"),
            ),
        )

        val found = build(workbook)
        assertThat(found?.groups?.flatMap { it.entries }.orEmpty()).isEmpty()
    }

    @Test
    fun `a blank template of the ordinary timetable shape yields nothing`() {
        val workbook = sheetOf(
            "blank",
            listOf(
                listOf("Time / period", "Monday", "Tuesday", "Wednesday"),
                listOf("", "", "", ""),
                listOf("", "", "", ""),
            ),
        )

        val found = build(workbook)
        assertThat(found?.groups?.flatMap { it.entries }.orEmpty()).isEmpty()
    }

    @Test
    fun `an undecidable date is asked about rather than guessed`() {
        // 05/10/2026 is 5 October or 10 May and this sheet says nothing either way. Before,
        // the spreadsheet path silently produced nothing while the PDF path asked.
        val workbook = sheetOf(
            "ambiguous",
            listOf(
                listOf("Event", "Date", "Time"),
                listOf("Project kickoff", "05/10/2026", "10:00"),
                listOf("Design review", "06/07/2026", "14:00"),
            ),
        )

        val found = build(workbook)!!
        assertThat(found.groups).isEmpty()
        assertThat(found.questions.filterIsInstance<PipelineQuestion.DateOrder>()).isNotEmpty()
    }

    @Test
    fun `answering the date order produces the events it was blocking`() {
        val workbook = sheetOf(
            "ambiguous",
            listOf(
                listOf("Event", "Date", "Time"),
                listOf("Project kickoff", "05/10/2026", "10:00"),
                listOf("Design review", "06/07/2026", "14:00"),
            ),
        )

        val dayFirst = build(workbook, PipelineAnswers(dateOrder = com.okayanshul.docaction.domain.DateOrder.DayFirst))!!
        val monthFirst = build(workbook, PipelineAnswers(dateOrder = com.okayanshul.docaction.domain.DateOrder.MonthFirst))!!

        val dayDates = dayFirst.groups.single().entries.mapNotNull { it.date.valueOrNull }
        val monthDates = monthFirst.groups.single().entries.mapNotNull { it.date.valueOrNull }

        assertThat(dayDates).contains(LocalDate.of(2026, 10, 5))
        assertThat(monthDates).contains(LocalDate.of(2026, 5, 10))
        // A question whose answers produce the same result was not worth asking.
        assertThat(dayDates).isNotEqualTo(monthDates)
    }
}
