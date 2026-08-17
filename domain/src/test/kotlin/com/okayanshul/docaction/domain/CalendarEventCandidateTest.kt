package com.okayanshul.docaction.domain

import com.google.common.truth.Truth.assertThat
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Test

/**
 * These tests guard the invariants in docs/14-testing.md § Strategy. A failure here is a
 * release blocker regardless of what else passes: this is the one function standing
 * between an uncertain reading and a wrong entry in the user's calendar.
 */
class CalendarEventCandidateTest {

    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")
    private val source = SourceReference.PdfSpan(1, BoundingBox(0f, 0f, 10f, 10f))
    private val term = TermBounds(LocalDate.of(2026, 8, 17), LocalDate.of(2026, 12, 5))

    private fun <T : Any> high(value: T) = Confident.High(value, source)

    private fun entry(
        title: Confident<String> = high("Data Structures"),
        date: Confident<LocalDate> = Confident.Missing("no date"),
        weekday: Confident<DayOfWeek> = Confident.Missing("no weekday"),
        start: Confident<LocalTime> = high(LocalTime.of(9, 0)),
        end: Confident<LocalTime> = high(LocalTime.of(10, 0)),
        location: Confident<String> = high("K10"),
    ) = ScheduleEntry(
        id = EntryId("e1"),
        title = title,
        date = date,
        weekday = weekday,
        startTime = start,
        endTime = end,
        location = location,
    )

    private fun accept(result: CalendarEventCandidate.Result) =
        (result as? CalendarEventCandidate.Result.Accepted)?.candidate
            ?: error("expected acceptance, got $result")

    private fun reject(result: CalendarEventCandidate.Result) =
        (result as? CalendarEventCandidate.Result.Rejected)?.unresolved
            ?: error("expected rejection, got $result")

    // --- I-3: a Missing field never becomes a value ---

    @Test
    fun `a missing title is rejected, not defaulted`() {
        val result = CalendarEventCandidate.from(
            entry(title = Confident.Missing("no subject in this cell"), date = high(LocalDate.of(2026, 9, 18))),
            zone, term,
        )
        assertThat(reject(result).field).isEqualTo(Unresolved.Field.Title)
    }

    @Test
    fun `a missing start time is rejected`() {
        val result = CalendarEventCandidate.from(
            entry(date = high(LocalDate.of(2026, 9, 18)), start = Confident.Missing("no time")),
            zone, term,
        )
        assertThat(reject(result).field).isEqualTo(Unresolved.Field.StartTime)
    }

    @Test
    fun `a missing end time is rejected rather than padded when nothing may be assumed`() {
        // Renamed, not deleted. This was the product's absolute rule — invent nothing, ever —
        // and it is still what `from` does by default. Inference is opt-in, and this is the
        // test that proves opting out is real rather than nominal.
        val result = CalendarEventCandidate.from(
            entry(date = high(LocalDate.of(2026, 9, 18)), end = Confident.Missing("no end time")),
            zone, term,
        )
        assertThat(reject(result).field).isEqualTo(Unresolved.Field.EndTime)
    }

    // --- what may be filled in, and what may never be ---

    @Test
    fun `an assumed end time is flagged, sourced, and says which rule filled it`() {
        val candidate = accept(
            CalendarEventCandidate.from(
                entry(date = high(LocalDate.of(2026, 9, 18)), end = Confident.Missing("no end time")),
                zone, term, assumedDuration = Duration.ofHours(1),
            )
        )

        assertThat(candidate.duration).isEqualTo(Duration.ofHours(1))
        assertThat(candidate.status).isEqualTo(CandidateStatus.NeedsAttention)
        assertThat(candidate.assumptions).containsExactly(Assumption.EndTime(Duration.ofHours(1)))
        assertThat(candidate.sources).contains(SourceReference.Assumed("EndTime", "assumed-duration"))
    }

    @Test
    fun `a badly read end time is never replaced by an assumed one`() {
        // The subtle case. `takeIfUsable` maps Low and Missing to the same null, so keying
        // the assumption on "not usable" would quietly overwrite a real, poor reading with
        // an invention — and the user would never learn the document said something else.
        val result = CalendarEventCandidate.from(
            entry(
                date = high(LocalDate.of(2026, 9, 18)),
                end = Confident.Low(LocalTime.of(10, 0), source, "this could be 10:00 or 16:00"),
            ),
            zone, term, assumedDuration = Duration.ofHours(1),
        )
        assertThat(reject(result).field).isEqualTo(Unresolved.Field.EndTime)
    }

    @Test
    fun `a date with no time becomes an all-day item when that is allowed`() {
        val candidate = accept(
            CalendarEventCandidate.from(
                entry(
                    date = high(LocalDate.of(2026, 9, 18)),
                    start = Confident.Missing("no time given"),
                    end = Confident.Missing("no end time given"),
                ),
                zone, term, allowAllDay = true,
            )
        )

        assertThat(candidate.isAllDay).isTrue()
        assertThat(candidate.timing).isEqualTo(EventTiming.AllDay(LocalDate.of(2026, 9, 18), zone))
        assertThat(candidate.status).isEqualTo(CandidateStatus.NeedsAttention)
    }

    @Test
    fun `a weekly entry with no time is never an all-day event, whatever is allowed`() {
        // Fifteen weeks of all-day rows across five weekdays is the worst thing this engine
        // could put in someone's calendar, and one unreadable time column would do it.
        val result = CalendarEventCandidate.from(
            entry(
                weekday = high(DayOfWeek.MONDAY),
                start = Confident.Missing("no time in this cell"),
                end = Confident.Missing("no time in this cell"),
            ),
            zone, term, allowAllDay = true, assumedDuration = Duration.ofHours(1),
        )
        assertThat(reject(result).field).isEqualTo(Unresolved.Field.StartTime)
    }

    @Test
    fun `correcting an assumed value clears the assumption`() {
        val assumed = accept(
            CalendarEventCandidate.from(
                entry(date = high(LocalDate.of(2026, 9, 18)), end = Confident.Missing("no end time")),
                zone, term, assumedDuration = Duration.ofHours(1),
            )
        )
        val corrected = assumed.edited(end = assumed.start.plusMinutes(90))!!

        // A stale "end time assumed" on a row the user has just set by hand would be the app
        // arguing with someone who is right.
        assertThat(corrected.assumptions).isEmpty()
        assertThat(corrected.status).isEqualTo(CandidateStatus.Ready)
    }

    @Test
    fun `an entry with neither date nor weekday is rejected`() {
        assertThat(reject(CalendarEventCandidate.from(entry(), zone, term)).field)
            .isEqualTo(Unresolved.Field.Date)
    }

    // --- I-9: unresolved ambiguity never becomes an event ---

    @Test
    fun `a low confidence field cannot be used without resolution`() {
        val result = CalendarEventCandidate.from(
            entry(
                title = Confident.Low("DBMS", source, "couldn't read this clearly"),
                date = high(LocalDate.of(2026, 9, 18)),
            ),
            zone, term,
        )
        assertThat(reject(result).field).isEqualTo(Unresolved.Field.Title)
    }

    @Test
    fun `a user provided value unblocks an otherwise unusable field`() {
        val corrected = Confident.High(
            LocalTime.of(11, 0),
            SourceReference.UserProvided("endTime", atEpochMillis = 1_000L),
        )
        val candidate = accept(
            CalendarEventCandidate.from(
                entry(date = high(LocalDate.of(2026, 9, 18)), end = corrected),
                zone, term,
            )
        )
        assertThat(candidate.end.toLocalTime()).isEqualTo(LocalTime.of(11, 0))
        assertThat(candidate.sources).contains(SourceReference.UserProvided("endTime", 1_000L))
    }

    // --- I-2: every candidate is traceable ---

    @Test
    fun `every accepted candidate carries at least one source`() {
        val candidate = accept(
            CalendarEventCandidate.from(entry(date = high(LocalDate.of(2026, 9, 18))), zone, term)
        )
        assertThat(candidate.sources).isNotEmpty()
    }

    // --- I-10: recurrence, not repetition ---

    @Test
    fun `a weekday entry becomes one recurring event bounded by the term`() {
        val candidate = accept(
            CalendarEventCandidate.from(entry(weekday = high(DayOfWeek.MONDAY)), zone, term)
        )

        assertThat(candidate.recurrence).isNotNull()
        assertThat(candidate.recurrence!!.byWeekday).containsExactly(DayOfWeek.MONDAY)
        assertThat(candidate.recurrence!!.until).isEqualTo(term.end)
    }

    @Test
    fun `recurrence starts on the first matching weekday on or after the term start`() {
        // 2026-08-17 is a Monday, so a Wednesday class starts on the 19th.
        val candidate = accept(
            CalendarEventCandidate.from(entry(weekday = high(DayOfWeek.WEDNESDAY)), zone, term)
        )
        assertThat(candidate.start.toLocalDate()).isEqualTo(LocalDate.of(2026, 8, 19))
    }

    @Test
    fun `a weekday entry with no term bounds is rejected rather than made unbounded`() {
        val result = CalendarEventCandidate.from(entry(weekday = high(DayOfWeek.MONDAY)), zone, term = null)

        assertThat(reject(result).field).isEqualTo(Unresolved.Field.Recurrence)
    }

    // --- validation of the interval ---

    @Test
    fun `a zero length event is rejected rather than padded`() {
        val result = CalendarEventCandidate.from(
            entry(
                date = high(LocalDate.of(2026, 9, 18)),
                start = high(LocalTime.of(10, 0)),
                end = high(LocalTime.of(10, 0)),
            ),
            zone, term,
        )
        assertThat(reject(result).field).isEqualTo(Unresolved.Field.EndTime)
    }

    @Test
    fun `an event crossing midnight ends the next day with a positive duration`() {
        val candidate = accept(
            CalendarEventCandidate.from(
                entry(
                    date = high(LocalDate.of(2026, 9, 18)),
                    start = high(LocalTime.of(23, 0)),
                    end = high(LocalTime.of(1, 0)),
                ),
                zone, term,
            )
        )

        assertThat(candidate.end.toLocalDate()).isEqualTo(LocalDate.of(2026, 9, 19))
        assertThat(candidate.duration.isNegative).isFalse()
        assertThat(candidate.duration.toHours()).isEqualTo(2)
    }

    // --- timezone is always explicit ---

    @Test
    fun `the candidate carries the supplied zone`() {
        val candidate = accept(
            CalendarEventCandidate.from(entry(date = high(LocalDate.of(2026, 9, 18))), zone, term)
        )
        assertThat(candidate.start.zone).isEqualTo(zone)
        assertThat(candidate.end.zone).isEqualTo(zone)
    }

    // --- status reflects whether review is needed ---

    @Test
    fun `an entry with a low confidence optional field needs attention but is still usable`() {
        val candidate = accept(
            CalendarEventCandidate.from(
                entry(
                    date = high(LocalDate.of(2026, 9, 18)),
                    location = Confident.Low("K1O", source, "this could be K10 or K1O"),
                ),
                zone, term,
            )
        )
        assertThat(candidate.status).isEqualTo(CandidateStatus.NeedsAttention)
        // A Low optional field is not used — its value is not silently accepted.
        assertThat(candidate.location).isNull()
    }

    @Test
    fun `a fully confident entry is ready`() {
        val candidate = accept(
            CalendarEventCandidate.from(entry(date = high(LocalDate.of(2026, 9, 18))), zone, term)
        )
        assertThat(candidate.status).isEqualTo(CandidateStatus.Ready)
        assertThat(candidate.location).isEqualTo("K10")
    }

    // --- user corrections: the other way a value becomes certain ---

    private fun ready() = accept(
        CalendarEventCandidate.from(entry(date = high(LocalDate.of(2026, 9, 18))), zone, term)
    )

    @Test
    fun `a correction records itself as user-provided`() {
        val edited = ready().edited(title = "Data Structures Lab")!!

        assertThat(edited.title).isEqualTo("Data Structures Lab")
        assertThat(edited.sources).contains(
            SourceReference.UserProvided("title", edited.sources.filterIsInstance<SourceReference.UserProvided>().first().atEpochMillis)
        )
        // The extracted provenance survives alongside it, never replaced.
        assertThat(edited.sources).contains(source)
    }

    @Test
    fun `a corrected row stops asking for attention`() {
        val flagged = accept(
            CalendarEventCandidate.from(
                entry(
                    date = high(LocalDate.of(2026, 9, 18)),
                    location = Confident.Low("K1O", source, "this could be K10 or K1O"),
                ),
                zone, term,
            )
        )
        assertThat(flagged.status).isEqualTo(CandidateStatus.NeedsAttention)
        assertThat(flagged.edited(location = "K10")!!.status).isEqualTo(CandidateStatus.Ready)
    }

    @Test
    fun `a zero-length correction is refused rather than padded`() {
        val candidate = ready()
        assertThat(candidate.edited(end = candidate.start)).isNull()
    }

    @Test
    fun `a correction cannot empty the title`() {
        assertThat(ready().edited(title = "   ")).isNull()
    }

    @Test
    fun `moving a weekly class to another day moves the whole series`() {
        val weekly = accept(
            CalendarEventCandidate.from(entry(weekday = high(DayOfWeek.MONDAY)), zone, term)
        )
        assertThat(weekly.recurrence!!.byWeekday).containsExactly(DayOfWeek.MONDAY)

        // Left alone, the rule would still repeat every Monday while the event sat on Tuesday.
        val moved = weekly.edited(
            start = weekly.start.plusDays(1),
            end = weekly.end.plusDays(1),
        )!!
        assertThat(moved.recurrence!!.byWeekday).containsExactly(DayOfWeek.TUESDAY)
    }

    @Test
    fun `a correction that changes nothing changes nothing`() {
        val candidate = ready()
        assertThat(candidate.edited(title = candidate.title)).isSameInstanceAs(candidate)
    }
}
