package com.okayanshul.docaction.document.spreadsheet

import com.google.common.truth.Truth.assertThat
import com.okayanshul.docaction.domain.CalendarEventCandidate
import com.okayanshul.docaction.domain.TermBounds
import com.okayanshul.docaction.domain.valueOrNull
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs the engine against a real institutional timetable export.
 *
 * The workbook is a student's own document and is deliberately **not** checked in — see
 * docs/12-privacy-security.md. The test skips when it isn't present, so CI stays green
 * while the file remains available for local verification.
 */
class RealWorkbookTest {

    private val workbookFile = File(
        System.getProperty("docaction.realWorkbook")
            ?: "/home/anshul/0-Structure/1-Work/Projects-Big-Three/New/" +
            "timetable_reports_2_20260714_2234_student.xlsx"
    )

    private val schedules = SpreadsheetSchedules()

    private fun requireWorkbook(): Workbook {
        assumeTrue("real workbook not available", workbookFile.exists())
        return schedules.open(workbookFile)
    }

    @Test
    fun `reads both sheets without loading the whole workbook into a parser`() {
        val workbook = requireWorkbook()

        assertThat(workbook.sheets.map { it.name })
            .containsExactly("Section Grid No Faculty", "Section Allocation Grid")
        assertThat(workbook.sheets.first().rowCount).isAtLeast(2000)
    }

    @Test
    fun `detects every section block as its own selectable schedule`() {
        val detected = schedules.detect(requireWorkbook())

        // The grid sheet holds one block per section, three semesters' worth.
        assertThat(detected.size).isAtLeast(300)
        // The label is whatever the document's own section row says, verbatim.
        assertThat(detected.map { it.label }).contains("Sem 3 | CS-S3 | CS1 · 8 course group(s)")
        assertThat(detected.all { it.entryCount >= 0 }).isTrue()
    }

    @Test
    fun `extracts one section into a weekly schedule`() {
        val workbook = requireWorkbook()
        val cs1 = schedules.detect(workbook).first { it.label.contains("CS1") }

        val group = schedules.extract(workbook, cs1).group ?: error("no schedule extracted for ${cs1.label}")

        // The sheet holds exactly 23 non-blank class cells for CS1:
        // Mon 4, Tue 5, Wed 4, Thu 5, Fri 5. Every one is extracted, and no entry is
        // invented for the empty periods that surround them.
        assertThat(group.entries).hasSize(23)
        assertThat(
            group.entries.groupingBy { it.weekday.valueOrNull }.eachCount()
        ).containsExactlyEntriesIn(
            mapOf(
                DayOfWeek.MONDAY to 4,
                DayOfWeek.TUESDAY to 5,
                DayOfWeek.WEDNESDAY to 4,
                DayOfWeek.THURSDAY to 5,
                DayOfWeek.FRIDAY to 5,
            )
        )

        val mondayNine = group.entries.first {
            it.weekday.valueOrNull == DayOfWeek.MONDAY && it.startTime.valueOrNull == LocalTime.of(9, 0)
        }
        assertThat(mondayNine.title.valueOrNull).isEqualTo("AFL")
        assertThat(mondayNine.location.valueOrNull).isEqualTo("C25-A107")
        assertThat(mondayNine.endTime.valueOrNull).isEqualTo(LocalTime.of(10, 0))
    }

    @Test
    fun `a course code shaped like a room does not steal the room slot`() {
        val workbook = requireWorkbook()
        val cs1 = schedules.detect(workbook).first { it.label.contains("CS1") }
        val group = schedules.extract(workbook, cs1).group!!

        // "IND4 / C25-B001" — the course code has the same shape as a room code, so the
        // more specific compound code has to win or the two end up swapped.
        val ind4 = group.entries.first { it.title.valueOrNull == "IND4" }
        assertThat(ind4.location.valueOrNull).isEqualTo("C25-B001")
    }

    @Test
    fun `periods populated only in the header row are still their own columns`() {
        val workbook = requireWorkbook()
        val cs1 = schedules.detect(workbook).first { it.label.contains("CS1") }
        val group = schedules.extract(workbook, cs1).group!!

        // P6 (1-2 PM) and P9 (4-5 PM) are empty for most sections. Inferring columns from
        // where text happens to sit lost them entirely; reading the sheet's own coordinates
        // keeps them.
        val starts = group.entries.mapNotNull { it.startTime.valueOrNull }.toSet()
        assertThat(starts).containsAtLeast(LocalTime.of(13, 0), LocalTime.of(16, 0))
    }

    @Test
    fun `afternoon periods keep their PM times`() {
        val workbook = requireWorkbook()
        // CS1 Thursday runs into the afternoon periods.
        val cs1 = schedules.detect(workbook).first { it.label.contains("CS1") }
        val group = schedules.extract(workbook, cs1).group!!

        val starts = group.entries.mapNotNull { it.startTime.valueOrNull }.distinct().sorted()
        assertThat(starts.first()).isAtLeast(LocalTime.of(8, 0))
        assertThat(starts.last()).isAtLeast(LocalTime.of(13, 0))
    }

    @Test
    fun `a chosen section becomes recurring calendar candidates`() {
        val workbook = requireWorkbook()
        val cs1 = schedules.detect(workbook).first { it.label.contains("CS1") }
        val group = schedules.extract(workbook, cs1).group!!

        val term = TermBounds(LocalDate.of(2026, 8, 17), LocalDate.of(2026, 12, 5))
        val accepted = group.entries.mapNotNull {
            (CalendarEventCandidate.from(it, ZoneId.of("Asia/Kolkata"), term)
                as? CalendarEventCandidate.Result.Accepted)?.candidate
        }

        assertThat(accepted).isNotEmpty()
        assertThat(accepted.all { it.recurrence != null }).isTrue()
        assertThat(accepted.all { it.recurrence!!.until == term.end }).isTrue()
        assertThat(accepted.all { it.sources.isNotEmpty() }).isTrue()
        assertThat(accepted.all { it.end.isAfter(it.start) }).isTrue()
    }

    @Test
    fun `the faculty allocation sheet is not mistaken for a schedule`() {
        val detected = schedules.detect(requireWorkbook())

        // Sheet 2 lists lecturers per section. It has no period times, so it must not
        // produce schedules — importing lecturer names as classes would be nonsense.
        assertThat(detected.none { it.sheetName == "Section Allocation Grid" }).isTrue()
    }
}
