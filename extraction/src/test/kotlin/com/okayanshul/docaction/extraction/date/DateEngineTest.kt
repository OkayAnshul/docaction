package com.okayanshul.docaction.extraction.date

import com.google.common.truth.Truth.assertThat
import com.okayanshul.docaction.domain.DateOrder
import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Test

class DateEngineTest {

    private val engine = DateEngine()

    private fun single(text: String): DateMatch =
        engine.parse(text).singleOrNull() ?: error("expected exactly one match in \"$text\", got ${engine.parse(text)}")

    // --- unambiguous forms ---

    @Test
    fun `iso date is unambiguous`() {
        val match = single("Exam on 2026-09-18 in K10")
        assertThat(match.readings).containsExactly(DateReading(18, 9, 2026))
    }

    @Test
    fun `day month year with month name`() {
        assertThat(single("18 September 2026").readings).containsExactly(DateReading(18, 9, 2026))
        assertThat(single("18th Sept 2026").readings).containsExactly(DateReading(18, 9, 2026))
        assertThat(single("September 18, 2026").readings).containsExactly(DateReading(18, 9, 2026))
        assertThat(single("Sep 18 2026").readings).containsExactly(DateReading(18, 9, 2026))
    }

    @Test
    fun `numeric date with a component above twelve resolves itself`() {
        val match = single("13/04/2026")
        assertThat(match.readings).containsExactly(DateReading(13, 4, 2026))
        assertThat(match.provenOrder).isEqualTo(DateOrder.DayFirst)
    }

    @Test
    fun `month-first numeric date is detected when the second component exceeds twelve`() {
        val match = single("04/13/2026")
        assertThat(match.readings).containsExactly(DateReading(13, 4, 2026))
        assertThat(match.provenOrder).isEqualTo(DateOrder.MonthFirst)
    }

    // --- the case the whole product hinges on ---

    @Test
    fun `ambiguous numeric date keeps both readings`() {
        val match = single("03/04/2026")
        assertThat(match.isAmbiguous).isTrue()
        assertThat(match.readings).containsExactly(
            DateReading(3, 4, 2026),
            DateReading(4, 3, 2026),
        ).inOrder()
        assertThat(match.provenOrder).isNull()
    }

    @Test
    fun `identical readings collapse to one`() {
        // 05/05 reads the same either way — not ambiguous in any way that matters.
        assertThat(single("05/05/2026").isAmbiguous).isFalse()
    }

    // --- rejection, never coercion ---

    @Test
    fun `impossible day is invalid and is not coerced`() {
        val match = single("32/09/2026")
        assertThat(match.isInvalid).isTrue()
        assertThat(match.readings).isEmpty()
        assertThat(match.raw).isEqualTo("32/09/2026")
    }

    @Test
    fun `february thirtieth is invalid`() {
        assertThat(single("2026-02-30").isInvalid).isTrue()
    }

    @Test
    fun `february twenty ninth is invalid in a common year but valid in a leap year`() {
        assertThat(single("29/02/2026").isInvalid).isTrue()
        assertThat(single("29/02/2028").readings).containsExactly(DateReading(29, 2, 2028))
    }

    @Test
    fun `zero day is invalid`() {
        assertThat(single("00/01/2026").isInvalid).isTrue()
    }

    // --- year handling ---

    @Test
    fun `two digit year is windowed around the pivot`() {
        assertThat(single("18/09/26").readings.first().year).isEqualTo(2026)
        assertThat(single("18/09/99").readings.first().year).isEqualTo(1999)
    }

    @Test
    fun `missing year is recorded as missing rather than assumed`() {
        val match = single("18/09")
        assertThat(match.readings.map { it.year }).containsExactly(null)
        assertThat(match.readings.first().toLocalDate()).isNull()
        assertThat(match.readings.first().toLocalDate(assumedYear = 2026))
            .isEqualTo(LocalDate.of(2026, 9, 18))
    }

    // --- weekdays ---

    @Test
    fun `bare weekday is recognised`() {
        val match = single("Monday")
        assertThat(match.weekday).isEqualTo(DayOfWeek.MONDAY)
        assertThat(match.readings).isEmpty()
    }

    @Test
    fun `weekday abbreviations are recognised`() {
        assertThat(single("Tue").weekday).isEqualTo(DayOfWeek.TUESDAY)
        assertThat(single("Thurs").weekday).isEqualTo(DayOfWeek.THURSDAY)
        assertThat(single("Sat").weekday).isEqualTo(DayOfWeek.SATURDAY)
    }

    // --- separators and noise ---

    @Test
    fun `dot and dash separators parse like slashes`() {
        assertThat(single("18-09-2026").readings.first()).isEqualTo(DateReading(18, 9, 2026))
        assertThat(single("18.09.2026").readings.first()).isEqualTo(DateReading(18, 9, 2026))
    }

    @Test
    fun `a date inside a sentence is found with its span`() {
        val text = "The exam is on 18/09/2026 at 10 AM."
        val match = single(text)
        assertThat(text.substring(match.range)).isEqualTo("18/09/2026")
    }

    @Test
    fun `text with no date produces no matches`() {
        assertThat(engine.parse("Data Structures, Room K10")).isEmpty()
    }

    @Test
    fun `room codes are not mistaken for dates`() {
        assertThat(engine.parse("Room K10, Block C").filter { it.readings.isNotEmpty() }).isEmpty()
    }
}
