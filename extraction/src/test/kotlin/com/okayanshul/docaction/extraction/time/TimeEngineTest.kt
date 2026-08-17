package com.okayanshul.docaction.extraction.time

import com.google.common.truth.Truth.assertThat
import java.time.LocalTime
import org.junit.Test

class TimeEngineTest {

    private val engine = TimeEngine()

    private fun one(text: String): TimeRange =
        engine.parse(text).singleOrNull() ?: error("expected one range in \"$text\", got ${engine.parse(text)}")

    // --- unambiguous forms ---

    @Test
    fun `twenty four hour time needs no inference`() {
        assertThat(one("14:30").start.asWritten()).isEqualTo(LocalTime.of(14, 30))
    }

    @Test
    fun `explicit markers resolve twelve hour times`() {
        assertThat(one("10 AM").start.asWritten()).isEqualTo(LocalTime.of(10, 0))
        assertThat(one("2:30 pm").start.asWritten()).isEqualTo(LocalTime.of(14, 30))
        assertThat(one("10a.m.").start.asWritten()).isEqualTo(LocalTime.of(10, 0))
    }

    @Test
    fun `noon and midnight are not confused`() {
        assertThat(one("12 PM").start.asWritten()).isEqualTo(LocalTime.NOON)
        assertThat(one("12 AM").start.asWritten()).isEqualTo(LocalTime.MIDNIGHT)
        assertThat(one("00:00").start.asWritten()).isEqualTo(LocalTime.MIDNIGHT)
    }

    // --- the AM/PM rule ---

    @Test
    fun `a bare twelve hour time is left unresolved`() {
        val token = one("10:00").start
        assertThat(token.meridiem).isEqualTo(Meridiem.Unstated)
        assertThat(token.asWritten()).isNull()
    }

    @Test
    fun `a bare integer is not a time`() {
        assertThat(engine.parse("Room 10")).isEmpty()
        assertThat(engine.parse("Semester 5")).isEmpty()
    }

    // --- ranges ---

    @Test
    fun `a range yields both ends`() {
        val range = one("9:00-10:00")
        assertThat(range.start.hour).isEqualTo(9)
        assertThat(range.end?.hour).isEqualTo(10)
    }

    @Test
    fun `en dash and the word to are both range separators`() {
        assertThat(one("9:00–10:00").end?.hour).isEqualTo(10)
        assertThat(one("9:00 to 10:00").end?.hour).isEqualTo(10)
    }

    @Test
    fun `a trailing marker governs both ends of a range`() {
        val range = one("10–11 AM")
        assertThat(range.start.asWritten()).isEqualTo(LocalTime.of(10, 0))
        assertThat(range.end?.asWritten()).isEqualTo(LocalTime.of(11, 0))
    }

    @Test
    fun `a bare range is parsed but left unresolved`() {
        val range = one("9-10")
        assertThat(range.start.hour).isEqualTo(9)
        assertThat(range.end?.hour).isEqualTo(10)
        assertThat(range.start.asWritten()).isNull()
    }

    // --- invalid values are flagged, not dropped ---

    @Test
    fun `impossible clock values are returned as invalid rather than discarded`() {
        val token = one("25:90").start
        assertThat(token.isValid).isFalse()
        assertThat(token.asWritten()).isNull()
        assertThat(token.raw).isEqualTo("25:90")
    }

    @Test
    fun `an impossible minute is invalid`() {
        assertThat(one("13:75").start.isValid).isFalse()
    }

    // --- dates must not be read as times ---

    @Test
    fun `a numeric date is not parsed as a time range`() {
        assertThat(engine.parse("18-09-2026")).isEmpty()
        assertThat(engine.parse("2026-09-18")).isEmpty()
    }

    @Test
    fun `a dotted date is not parsed as a time`() {
        assertThat(engine.parse("18.09.2026")).isEmpty()
    }

    // --- context ---

    @Test
    fun `a time inside a sentence is found with its span`() {
        val text = "Data Structures at 10:00 in K10"
        val range = one(text)
        assertThat(text.substring(range.range)).contains("10:00")
    }

    @Test
    fun `singles inside a range are not duplicated`() {
        assertThat(engine.parse("09:00-10:00")).hasSize(1)
    }
}
