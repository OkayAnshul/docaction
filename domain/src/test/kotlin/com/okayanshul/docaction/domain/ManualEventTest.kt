package com.okayanshul.docaction.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Events the user typed in themselves.
 *
 * The property under test is not really "does it build an event" — it is that adding a manual
 * path did not open a second door into [CalendarEventCandidate]. Everything here goes through
 * the same `from()` choke point as the engine's own output, which is why the guarantee that
 * the app never fabricates calendar data still reduces to auditing one function.
 */
class ManualEventTest {

    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")
    private val term = TermBounds(LocalDate.of(2026, 8, 3), LocalDate.of(2026, 12, 4))
    private val at = 1_700_000_000_000L

    private fun accept(entry: ScheduleEntry) =
        ManualEvent.candidate(entry, zone, term) as CalendarEventCandidate.Result.Accepted

    // --- what the user typed is what they get ---

    @Test
    fun `a typed event is ready, with nothing assumed`() {
        val candidate = accept(
            ManualEvent.dated(
                title = "Dentist",
                date = LocalDate.of(2026, 9, 14),
                start = LocalTime.of(10, 0),
                end = LocalTime.of(11, 0),
                location = "Clinic",
                at = at,
            ),
        ).candidate

        // Nothing here is uncertain, so nothing here should be flagged. A row that asks the
        // user to check a value they just typed teaches them to ignore the flag.
        assertThat(candidate.status).isEqualTo(CandidateStatus.Ready)
        assertThat(candidate.assumptions).isEmpty()
        assertThat(candidate.title).isEqualTo("Dentist")
        assertThat(candidate.location).isEqualTo("Clinic")
        assertThat(candidate.isAllDay).isFalse()
    }

    @Test
    fun `every field traces back to the user`() {
        val candidate = accept(
            ManualEvent.dated(
                title = "Dentist",
                date = LocalDate.of(2026, 9, 14),
                start = LocalTime.of(10, 0),
                end = LocalTime.of(11, 0),
                at = at,
            ),
        ).candidate

        // "Where did this come from?" must answer "you told us", not shrug. A hand-typed
        // value is as traceable as an extracted one — that is the whole point of
        // UserProvided being a source rather than a flag.
        val provided = candidate.sources.filterIsInstance<SourceReference.UserProvided>()
        assertThat(provided.map { it.field })
            .containsAtLeast("title", "date", "startTime", "endTime")
        assertThat(provided.map { it.atEpochMillis }.distinct()).containsExactly(at)

        // And it never claims to have read this off a page.
        assertThat(candidate.sources.filterIsInstance<SourceReference.PdfSpan>()).isEmpty()
        assertThat(candidate.sources.filterIsInstance<SourceReference.Assumed>()).isEmpty()
    }

    @Test
    fun `a weekly class becomes one repeating event, not fifteen`() {
        val candidate = accept(
            ManualEvent.weekly(
                title = "Data Structures",
                weekday = DayOfWeek.MONDAY,
                start = LocalTime.of(9, 0),
                end = LocalTime.of(10, 0),
                at = at,
            ),
        ).candidate

        assertThat(candidate.recurrence).isNotNull()
        assertThat(candidate.recurrence!!.byWeekday).containsExactly(DayOfWeek.MONDAY)
        // Bounded by construction — an unbounded weekly recurrence is never written.
        assertThat(candidate.recurrence!!.until).isEqualTo(term.end)
        assertThat(candidate.start.dayOfWeek).isEqualTo(DayOfWeek.MONDAY)
    }

    @Test
    fun `an all-day item is all-day, and says the time came from us`() {
        val candidate = accept(
            ManualEvent.allDay("Fee payment", LocalDate.of(2026, 9, 30), at = at),
        ).candidate

        assertThat(candidate.isAllDay).isTrue()
        // The user chose to give no time; we still record that the all-day shape was our
        // doing rather than something the input stated.
        assertThat(candidate.assumptions.map { it.rule }).containsExactly("all-day-from-date-only")
        assertThat(candidate.status).isEqualTo(CandidateStatus.NeedsAttention)
    }

    // --- the choke point still refuses what it always refused ---

    @Test
    fun `a nameless event is refused, not given a placeholder`() {
        val result = ManualEvent.candidate(
            ManualEvent.dated("   ", LocalDate.of(2026, 9, 14), LocalTime.of(10, 0), LocalTime.of(11, 0)),
            zone, term,
        )

        // Not "Untitled event". The form keeps Save disabled and this is the backstop.
        assertThat(result).isInstanceOf(CalendarEventCandidate.Result.Rejected::class.java)
        assertThat((result as CalendarEventCandidate.Result.Rejected).unresolved.field)
            .isEqualTo(Unresolved.Field.Title)
    }

    @Test
    fun `a zero-length event is refused rather than padded`() {
        val result = ManualEvent.candidate(
            ManualEvent.dated("Standup", LocalDate.of(2026, 9, 14), LocalTime.of(10, 0), LocalTime.of(10, 0)),
            zone, term,
        )

        assertThat(result).isInstanceOf(CalendarEventCandidate.Result.Rejected::class.java)
    }

    @Test
    fun `a weekly class with no term is refused rather than repeating for ever`() {
        val result = ManualEvent.candidate(
            ManualEvent.weekly("Lab", DayOfWeek.TUESDAY, LocalTime.of(14, 0), LocalTime.of(16, 0)),
            zone,
            term = null,
        )

        assertThat(result).isInstanceOf(CalendarEventCandidate.Result.Rejected::class.java)
        assertThat((result as CalendarEventCandidate.Result.Rejected).unresolved.field)
            .isEqualTo(Unresolved.Field.Recurrence)
    }

    @Test
    fun `an event crossing midnight is kept, because night shifts are real`() {
        val candidate = accept(
            ManualEvent.dated(
                title = "Night shift",
                date = LocalDate.of(2026, 9, 14),
                start = LocalTime.of(22, 0),
                end = LocalTime.of(6, 0),
                at = at,
            ),
        ).candidate

        assertThat(candidate.end.toLocalDate()).isEqualTo(LocalDate.of(2026, 9, 15))
        assertThat(candidate.status).isEqualTo(CandidateStatus.Ready)
    }

    // --- tidying, not inventing ---

    @Test
    fun `a blank location is absent rather than empty`() {
        val candidate = accept(
            ManualEvent.dated(
                title = "Dentist", date = LocalDate.of(2026, 9, 14),
                start = LocalTime.of(10, 0), end = LocalTime.of(11, 0),
                location = "   ", at = at,
            ),
        ).candidate

        assertThat(candidate.location).isNull()
        assertThat(candidate.sources.filterIsInstance<SourceReference.UserProvided>().map { it.field })
            .doesNotContain("location")
    }

    @Test
    fun `surrounding whitespace is trimmed from what was typed`() {
        val candidate = accept(
            ManualEvent.dated(
                title = "  Dentist  ", date = LocalDate.of(2026, 9, 14),
                start = LocalTime.of(10, 0), end = LocalTime.of(11, 0),
                location = "  Clinic  ", at = at,
            ),
        ).candidate

        assertThat(candidate.title).isEqualTo("Dentist")
        assertThat(candidate.location).isEqualTo("Clinic")
    }

    @Test
    fun `two manual events never share an id`() {
        val one = ManualEvent.dated("A", LocalDate.of(2026, 9, 14), LocalTime.of(9, 0), LocalTime.of(10, 0))
        val two = ManualEvent.dated("B", LocalDate.of(2026, 9, 14), LocalTime.of(9, 0), LocalTime.of(10, 0))

        // Undo and duplicate detection both key on this; colliding ids would make one manual
        // event delete another.
        assertThat(one.id).isNotEqualTo(two.id)
    }
}
