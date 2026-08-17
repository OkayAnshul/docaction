package com.okayanshul.docaction.extraction.time

import com.google.common.truth.Truth.assertThat
import java.time.LocalTime
import org.junit.Test

class MeridiemResolverTest {

    private val resolver = MeridiemResolver()

    private fun bare(hour: Int, minute: Int = 0) =
        TimeToken("$hour:$minute", 0..0, hour, minute, Meridiem.Unstated)

    private fun stated(hour: Int, meridiem: Meridiem) =
        TimeToken("$hour", 0..0, hour, 0, meridiem)

    @Test
    fun `a value above twelve proves the column is twenty four hour`() {
        val resolved = resolver.resolve(listOf(bare(9), bare(14), bare(16)))

        assertThat(resolved.map { it.time }).containsExactly(
            LocalTime.of(9, 0), LocalTime.of(14, 0), LocalTime.of(16, 0),
        ).inOrder()
        assertThat(resolved.first().inferred).isTrue()
        assertThat(resolved.first().reason).contains("24-hour")
    }

    @Test
    fun `an academic column wrapping once at noon resolves`() {
        // 8, 9, 10, 11, 12, 1, 2, 3 — the classic timetable column.
        val column = listOf(bare(8), bare(9), bare(10), bare(11), bare(12), bare(1), bare(2), bare(3))

        val resolved = resolver.resolve(column)

        assertThat(resolved.map { it.time }).containsExactly(
            LocalTime.of(8, 0), LocalTime.of(9, 0), LocalTime.of(10, 0), LocalTime.of(11, 0),
            LocalTime.of(12, 0), LocalTime.of(13, 0), LocalTime.of(14, 0), LocalTime.of(15, 0),
        ).inOrder()
        assertThat(resolved.last().inferred).isTrue()
    }

    @Test
    fun `a column that reads equally well as morning or evening is left unresolved`() {
        // 8, 9, 10, 11 is monotonic whether it's all AM or all PM. Inferring either
        // would be a guess dressed up as a reading.
        val resolved = resolver.resolve(listOf(bare(8), bare(9), bare(10), bare(11)))

        assertThat(resolved.map { it.time }).containsExactly(null, null, null, null)
        assertThat(resolved.first().reason).contains("morning or evening")
    }

    @Test
    fun `a stated marker anchors the column`() {
        // One PM marker rules out the all-morning reading.
        val resolved = resolver.resolve(listOf(bare(9), bare(10), stated(2, Meridiem.Pm), bare(3)))

        assertThat(resolved.map { it.time }).containsExactly(
            LocalTime.of(9, 0), LocalTime.of(10, 0), LocalTime.of(14, 0), LocalTime.of(15, 0),
        ).inOrder()
    }

    @Test
    fun `a fully stated column needs no inference`() {
        val resolved = resolver.resolve(
            listOf(stated(9, Meridiem.Am), stated(11, Meridiem.Am), stated(2, Meridiem.Pm))
        )

        assertThat(resolved.none { it.inferred }).isTrue()
        assertThat(resolved.map { it.time }).containsExactly(
            LocalTime.of(9, 0), LocalTime.of(11, 0), LocalTime.of(14, 0),
        ).inOrder()
    }

    @Test
    fun `a zero padded hour proves the column is twenty four hour`() {
        // Nobody writes a 12-hour clock as "09:00". Two rows is far too short for the
        // monotonicity rule, so this is the only thing that can settle it — and it is
        // evidence from the document rather than an assumption about habits.
        val padded = TimeToken("09:00", 0..0, 9, 0, Meridiem.Unstated, paddedHour = true)
        val resolved = resolver.resolve(listOf(padded, bare(10)))

        assertThat(resolved.map { it.time }).containsExactly(LocalTime.of(9, 0), LocalTime.of(10, 0)).inOrder()
        assertThat(resolved.first().reason).contains("24-hour")
    }

    @Test
    fun `context from the same column settles times the sequence cannot`() {
        // Starts "09:00, 10:00" and ends "10:00, 11:00" — only the starts are padded,
        // but both series are the same clock.
        val starts = listOf(
            TimeToken("09:00", 0..0, 9, 0, Meridiem.Unstated, paddedHour = true),
            TimeToken("10:00", 0..0, 10, 0, Meridiem.Unstated),
        )
        val ends = listOf(bare(10), bare(11))

        val resolved = resolver.resolve(ends, context = starts)

        assertThat(resolved.map { it.time }).containsExactly(LocalTime.of(10, 0), LocalTime.of(11, 0)).inOrder()
    }

    @Test
    fun `a column too short to be evidence is left unresolved`() {
        val resolved = resolver.resolve(listOf(bare(9), bare(10)))

        assertThat(resolved.map { it.time }).containsExactly(null, null)
    }

    @Test
    fun `an unordered column is left unresolved`() {
        // Not a time column at all — probably misdetected. Better to say nothing.
        val resolved = resolver.resolve(listOf(bare(10), bare(3), bare(7), bare(1)))

        assertThat(resolved.all { it.time == null }).isTrue()
    }

    @Test
    fun `an invalid value blocks inference for the column`() {
        val invalid = TimeToken("25:90", 0..0, 25, 90, Meridiem.Unstated)
        val resolved = resolver.resolve(listOf(bare(8), bare(9), invalid, bare(11)))

        assertThat(resolved.map { it.time }).containsExactly(null, null, null, null)
    }

    @Test
    fun `an empty column resolves to nothing`() {
        assertThat(resolver.resolve(emptyList())).isEmpty()
    }

    @Test
    fun `noon in a twenty four hour column is noon, not midnight`() {
        // The one value where the two clocks disagree. In 12-hour notation a bare 12 before
        // noon is 00:00; in 24-hour notation it is 12:00. Applying the 12-hour conversion
        // inside the 24-hour branch turned every "11:00-12:00" class into one running from
        // eleven in the morning until midnight — thirteen hours in someone's calendar.
        val padded = TimeToken("09:00", 0..0, 9, 0, Meridiem.Unstated, paddedHour = true)
        val resolved = resolver.resolve(listOf(padded, bare(12), bare(13)))

        assertThat(resolved.map { it.time }).containsExactly(
            LocalTime.of(9, 0), LocalTime.of(12, 0), LocalTime.of(13, 0),
        ).inOrder()
    }

    @Test
    fun `an end time of twelve is read against its own start`() {
        // "11:00-12:00" in one cell: the padded start proves the clock, and the end must
        // follow it rather than being resolved on its own.
        val start = TimeToken("11:00", 0..0, 11, 0, Meridiem.Unstated, paddedHour = true)
        val end = TimeToken("12:00", 0..0, 12, 0, Meridiem.Unstated, paddedHour = true)

        val resolved = resolver.resolve(listOf(end), context = listOf(start))
        assertThat(resolved.single().time).isEqualTo(LocalTime.of(12, 0))
    }
}
