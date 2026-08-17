package com.okayanshul.docaction.domain

import com.google.common.truth.Truth.assertThat
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Test

/**
 * The scheduling brain, tested with no device and no alarms.
 *
 * The daylight-saving cases are the ones that matter. A weekly class is a *wall-clock*
 * commitment — 09:00 stays 09:00 when the clocks change — and doing the arithmetic in
 * instants instead of local dates would shift every occurrence by an hour for half a term
 * without anything failing loudly.
 */
class ReminderSchedulingTest {

    private val london = ZoneId.of("Europe/London")
    private val kolkata = ZoneId.of("Asia/Kolkata")

    private fun weekly(vararg days: DayOfWeek, until: LocalDate) =
        Recurrence(Frequency.Weekly, days.toSet(), until)

    // --- recurrence expansion ---

    @Test
    fun `expands only the occurrences inside the window`() {
        val recurrence = weekly(DayOfWeek.MONDAY, until = LocalDate.of(2026, 12, 5))
        val from = ZonedDateTime.of(2026, 8, 17, 0, 0, 0, 0, kolkata).toInstant()
        val to = from.plus(Duration.ofDays(2))

        val occurrences = recurrence.occurrencesBetween(from, to, LocalTime.of(9, 0), kolkata)

        // 17 Aug 2026 is a Monday; the next is outside a two-day window.
        assertThat(occurrences).hasSize(1)
        assertThat(occurrences.single().toLocalDate()).isEqualTo(LocalDate.of(2026, 8, 17))
        assertThat(occurrences.single().toLocalTime()).isEqualTo(LocalTime.of(9, 0))
    }

    @Test
    fun `never returns an occurrence after the recurrence ends`() {
        val recurrence = weekly(DayOfWeek.MONDAY, until = LocalDate.of(2026, 8, 17))
        val from = ZonedDateTime.of(2026, 8, 18, 0, 0, 0, 0, kolkata).toInstant()

        val occurrences = recurrence.occurrencesBetween(
            from, from.plus(Duration.ofDays(30)), LocalTime.of(9, 0), kolkata,
        )

        assertThat(occurrences).isEmpty()
    }

    @Test
    fun `a window containing no matching weekday yields nothing`() {
        val recurrence = weekly(DayOfWeek.SUNDAY, until = LocalDate.of(2026, 12, 5))
        val from = ZonedDateTime.of(2026, 8, 17, 0, 0, 0, 0, kolkata).toInstant() // Monday

        val occurrences = recurrence.occurrencesBetween(
            from, from.plus(Duration.ofDays(2)), LocalTime.of(9, 0), kolkata,
        )

        assertThat(occurrences).isEmpty()
    }

    @Test
    fun `excluded dates are skipped`() {
        val recurrence = Recurrence(
            frequency = Frequency.Weekly,
            byWeekday = setOf(DayOfWeek.MONDAY),
            until = LocalDate.of(2026, 12, 5),
            exceptions = listOf(LocalDate.of(2026, 8, 17)),
        )
        val from = ZonedDateTime.of(2026, 8, 17, 0, 0, 0, 0, kolkata).toInstant()

        val occurrences = recurrence.occurrencesBetween(
            from, from.plus(Duration.ofDays(2)), LocalTime.of(9, 0), kolkata,
        )

        assertThat(occurrences).isEmpty()
    }

    // --- the case that would fail silently if the maths were done in instants ---

    @Test
    fun `a weekly class keeps its wall-clock time across a daylight saving change`() {
        // British clocks go back on 25 October 2026. A Monday 09:00 class must stay at
        // 09:00 local on both sides of that.
        val recurrence = weekly(DayOfWeek.MONDAY, until = LocalDate.of(2026, 12, 7))
        val from = ZonedDateTime.of(2026, 10, 19, 0, 0, 0, 0, london).toInstant()
        val to = ZonedDateTime.of(2026, 11, 3, 0, 0, 0, 0, london).toInstant()

        val occurrences = recurrence.occurrencesBetween(from, to, LocalTime.of(9, 0), london)

        assertThat(occurrences.map { it.toLocalDate() }).containsExactly(
            LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 2),
        ).inOrder()
        assertThat(occurrences.map { it.toLocalTime() }.distinct()).containsExactly(LocalTime.of(9, 0))
        // ...and the offsets genuinely differ, proving the transition was crossed.
        assertThat(occurrences.map { it.offset }.distinct()).hasSize(2)
    }

    // --- ladder ---

    @Test
    fun `the ladder fires once per offset, furthest first`() {
        val start = ZonedDateTime.of(2026, 9, 18, 10, 0, 0, 0, kolkata).toInstant()
        val ladder = ReminderLadder(
            offsets = listOf(Duration.ofHours(1), Duration.ofMinutes(15), Duration.ofMinutes(5)),
        )

        val instants = ladder.instantsFor(start)

        assertThat(instants).containsExactly(
            start.minus(Duration.ofHours(1)),
            start.minus(Duration.ofMinutes(15)),
            start.minus(Duration.ofMinutes(5)),
        ).inOrder()
    }

    @Test
    fun `repeat until start keeps nudging and stops at the event`() {
        val start = ZonedDateTime.of(2026, 9, 18, 10, 0, 0, 0, kolkata).toInstant()
        val ladder = ReminderLadder(
            offsets = listOf(Duration.ofMinutes(15)),
            repeatEvery = Duration.ofMinutes(5),
        )

        val instants = ladder.instantsFor(start)

        assertThat(instants).containsExactly(
            start.minus(Duration.ofMinutes(15)),
            start.minus(Duration.ofMinutes(10)),
            start.minus(Duration.ofMinutes(5)),
        ).inOrder()
        // Nothing is scheduled at or after the moment the thing actually begins.
        assertThat(instants.none { !it.isBefore(start) }).isTrue()
    }

    @Test
    fun `dueWithin returns nothing outside the window`() {
        val start = ZonedDateTime.of(2026, 9, 18, 10, 0, 0, 0, kolkata).toInstant()
        val ladder = ReminderLadder.Default

        val windowStart = start.minus(Duration.ofMinutes(20))
        val windowEnd = start.minus(Duration.ofMinutes(1))
        val due = ladder.dueWithin(start, windowStart, windowEnd)

        assertThat(due).isNotEmpty()
        assertThat(due.all { !it.isBefore(windowStart) && it.isBefore(windowEnd) }).isTrue()
        // The day-ahead and hour-ahead rungs are long past and must not be fired late.
        assertThat(due).doesNotContain(start.minus(Duration.ofDays(1)))
        assertThat(due).doesNotContain(start.minus(Duration.ofHours(1)))
    }

    @Test
    fun `a ladder cannot be built with a negative offset`() {
        runCatching { ReminderLadder(offsets = listOf(Duration.ofMinutes(-5))) }
            .also { assertThat(it.isFailure).isTrue() }
    }

    // --- the choke point still holds ---

    @Test
    fun `a reminder cannot be built from a missing date`() {
        val entry = ScheduleEntry(
            id = EntryId("e1"),
            title = Confident.High("Fee payment", SourceReference.UserProvided("title", 0)),
            date = Confident.Missing("no date in this notice"),
        )

        val result = ReminderCandidate.from(entry, kolkata, ReminderKind.Deadline, ReminderLadder.Deadlines)

        assertThat(result).isInstanceOf(ReminderCandidate.Result.Rejected::class.java)
    }

    @Test
    fun `a dated entry with no time becomes an all-day reminder`() {
        val source = SourceReference.UserProvided("f", 0)
        val entry = ScheduleEntry(
            id = EntryId("e1"),
            title = Confident.High("Fee payment", source),
            date = Confident.High(LocalDate.of(2026, 11, 20), source),
        )

        val accepted = ReminderCandidate.from(
            entry, kolkata, ReminderKind.Deadline, ReminderLadder.Deadlines,
        ) as ReminderCandidate.Result.Accepted

        assertThat(accepted.candidate.allDay).isTrue()
        // Anchored at a sensible hour to notify — not midnight, and not a fabricated start.
        assertThat(accepted.candidate.dueAt.toLocalTime()).isEqualTo(LocalTime.of(9, 0))
        assertThat(accepted.candidate.dueAt.toLocalDate()).isEqualTo(LocalDate.of(2026, 11, 20))
    }

    @Test
    fun `a dated entry with a time is not all-day`() {
        val source = SourceReference.UserProvided("f", 0)
        val entry = ScheduleEntry(
            id = EntryId("e1"),
            title = Confident.High("Interview", source),
            date = Confident.High(LocalDate.of(2026, 9, 21), source),
            startTime = Confident.High(LocalTime.of(10, 30), source),
        )

        val accepted = ReminderCandidate.from(
            entry, kolkata, ReminderKind.Appointment, ReminderLadder.Default,
        ) as ReminderCandidate.Result.Accepted

        assertThat(accepted.candidate.allDay).isFalse()
        assertThat(accepted.candidate.dueAt.toLocalTime()).isEqualTo(LocalTime.of(10, 30))
    }
}
