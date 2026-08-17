package com.okayanshul.docaction.extraction.prose

import com.google.common.truth.Truth.assertThat
import com.okayanshul.docaction.domain.BoundingBox
import com.okayanshul.docaction.domain.Confident
import com.okayanshul.docaction.domain.SourceReference
import com.okayanshul.docaction.domain.TextOrigin
import com.okayanshul.docaction.domain.TextRun
import com.okayanshul.docaction.domain.valueOrNull
import com.okayanshul.docaction.extraction.table.TextLine
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Test

class ProseExtractorTest {

    private val extractor = ProseExtractor()

    /** One line of prose per element, stacked like a page of text. */
    private fun page(vararg lines: String): List<TextRun> = lines.mapIndexed { index, text ->
        TextRun(
            text = text,
            bounds = BoundingBox(0f, index * 20f, 400f, index * 20f + 10f),
            confidence = null,
            origin = TextOrigin.PdfTextLayer,
        )
    }

    private fun sourceOf(line: TextLine): SourceReference = SourceReference.PdfSpan(0, line.bounds)

    private fun extract(vararg lines: String, year: Int? = 2026) =
        extractor.extract(page(*lines), "doc", ::sourceOf, assumedYear = year)

    // --- the core case: a notice with a deadline ---

    @Test
    fun `a deadline notice yields a dated all-day entry`() {
        val result = extract(
            "Examination Section",
            "Last date for submission of examination form: 20/11/2026",
        )

        assertThat(result.kind).isEqualTo(DocumentKind.Deadline)
        val entry = result.group!!.entries.single()
        assertThat(entry.date.valueOrNull).isEqualTo(LocalDate.of(2026, 11, 20))
        assertThat(entry.title.valueOrNull).contains("Last date for submission")
        // No time in the document, so none is invented.
        assertThat(entry.startTime).isInstanceOf(Confident.Missing::class.java)
    }

    @Test
    fun `a date with a time keeps the time`() {
        val result = extract(
            "Interview Call Letter",
            "Interview on 18/09/2026 at 10:30 AM at the Head Office",
        )

        val entry = result.group!!.entries.single()
        assertThat(entry.date.valueOrNull).isEqualTo(LocalDate.of(2026, 9, 18))
        assertThat(entry.startTime.valueOrNull).isEqualTo(LocalTime.of(10, 30))
        assertThat(result.kind).isEqualTo(DocumentKind.Appointment)
    }

    // --- classification never supplies values ---

    @Test
    fun `cue words classify but never invent a date`() {
        val result = extract("Fees are due before the end of term.", "Please pay promptly.")

        assertThat(result.group).isNull()
        assertThat(result.reason).contains("no dates")
    }

    @Test
    fun `travel and appointment cues are distinguished`() {
        assertThat(extractor.classify("Departure 14:35 from Terminal 3")).isEqualTo(DocumentKind.Travel)
        assertThat(extractor.classify("Your appointment is confirmed")).isEqualTo(DocumentKind.Appointment)
        assertThat(extractor.classify("Last date for fee payment")).isEqualTo(DocumentKind.Deadline)
        assertThat(extractor.classify("Nothing notable here")).isEqualTo(DocumentKind.Unknown)
    }

    // --- multi-event documents ---

    @Test
    fun `a calendar of dated items yields one entry each`() {
        val result = extract(
            "Academic Calendar 2026",
            "Semester begins 17/08/2026",
            "Mid-term examinations 05/10/2026",
            "Last day of classes 05/12/2026",
        )

        val entries = result.group!!.entries
        assertThat(entries).hasSize(3)
        assertThat(entries.mapNotNull { it.date.valueOrNull }).containsExactly(
            LocalDate.of(2026, 8, 17), LocalDate.of(2026, 10, 5), LocalDate.of(2026, 12, 5),
        ).inOrder()
    }

    @Test
    fun `a bare date takes its title from the heading above it`() {
        val result = extract("Convocation Ceremony", "12/12/2026")

        val entry = result.group!!.entries.single()
        assertThat(entry.title.valueOrNull).isEqualTo("Convocation Ceremony")
        // Borrowed from a heading, so it must not claim to have been read in place.
        assertThat(entry.title).isInstanceOf(Confident.Medium::class.java)
    }

    // --- ambiguity is still preserved, reusing the date engine ---

    @Test
    fun `an ambiguous date with no evidence produces no entry`() {
        val result = extract("Meeting scheduled on 03/04/2026")

        assertThat(result.group).isNull()
        assertThat(result.reason).contains("couldn't tell what they refer to")
    }

    @Test
    fun `a sibling date elsewhere in the document resolves the ambiguous one`() {
        // 13/04 proves day-first for the whole document, so 03/04 is 3 April.
        val result = extract(
            "Fee payment window",
            "Opens on 13/04/2026",
            "Closes on 03/04/2026",
        )

        assertThat(result.group!!.entries.mapNotNull { it.date.valueOrNull })
            .containsExactly(LocalDate.of(2026, 4, 13), LocalDate.of(2026, 4, 3)).inOrder()
    }

    @Test
    fun `an impossible date is not turned into an entry`() {
        val result = extract("Report on 32/09/2026 without fail")

        assertThat(result.group).isNull()
    }

    // --- prose never becomes a weekly schedule ---

    @Test
    fun `a weekday mentioned in prose does not create a recurring entry`() {
        val result = extract("Classes resume on Monday 17/08/2026 for all sections")

        val entry = result.group!!.entries.single()
        assertThat(entry.date.valueOrNull).isEqualTo(LocalDate.of(2026, 8, 17))
        // A dated notice is not a timetable; weekday must stay absent so no RRULE forms.
        assertThat(entry.weekday).isInstanceOf(Confident.Missing::class.java)
    }

    /**
     * A dated *table* — company, date, time, venue in separate columns. The whole row
     * clusters into one line, so the title has to come from what is left after the date and
     * time are removed, and must not be the column header.
     */
    @Test
    fun `a dated table row uses the remaining cell as the title`() {
        val result = extract(
            "Training and Placement Cell",
            "Company Date Reporting time Venue",
            "Infosys 08/10/2026 08:30 Auditorium",
            "TCS Digital 12/10/2026 09:00 Seminar Hall",
            "Wipro 19/10/2026 08:45 Auditorium",
        )

        val entries = result.group?.entries ?: error("nothing extracted: ${result.reason}")
        assertThat(entries).hasSize(3)
        assertThat(entries.map { it.title.valueOrNull }).containsExactly(
            "Infosys", "TCS Digital", "Wipro",
        ).inOrder()
        // 19/10 proves day-first for the document, so 08/10 is the 8th of October.
        assertThat(entries.first().date.valueOrNull).isEqualTo(LocalDate.of(2026, 10, 8))
        assertThat(entries.first().startTime.valueOrNull).isEqualTo(LocalTime.of(8, 30))
    }

    @Test
    fun `empty input yields nothing`() {
        assertThat(extractor.extract(emptyList(), "doc", ::sourceOf).group).isNull()
    }
}
