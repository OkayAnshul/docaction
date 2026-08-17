package com.okayanshul.docaction.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Asking one question instead of the same question 175 times.
 *
 * The corpus is the reason this exists: 252 of 369 candidates arrive flagged, from exactly two
 * causes. These tests pin the two properties that make batching safe — the answer reaches
 * every row it should and no row it shouldn't, and a row flagged for an *unrelated* reason is
 * never settled as a side effect.
 */
class AssumptionReviewTest {

    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")
    private val term = TermBounds(LocalDate.of(2026, 8, 3), LocalDate.of(2026, 12, 4))
    private val at = 1_700_000_000_000L
    private val source = SourceReference.PdfSpan(1, BoundingBox(0f, 0f, 10f, 10f))

    /** A date with no clock time — becomes all-day, with an assumption. */
    private fun dateOnly(id: String, title: String, day: Int = 14) = build(
        ScheduleEntry(
            id = EntryId(id),
            title = Confident.High(title, source),
            date = Confident.High(LocalDate.of(2026, 9, day), source),
            startTime = Confident.Missing("no time given"),
            endTime = Confident.Missing("no end given"),
        ),
        allowAllDay = true,
    )

    /** A start with no end — gets an assumed hour. */
    private fun startOnly(id: String, title: String) = build(
        ScheduleEntry(
            id = EntryId(id),
            title = Confident.High(title, source),
            date = Confident.High(LocalDate.of(2026, 9, 14), source),
            startTime = Confident.High(LocalTime.of(10, 0), source),
            endTime = Confident.Missing("no end given"),
        ),
        assumedDuration = Duration.ofHours(1),
    )

    /** Fully read, but one field read badly. Flagged for a reason batching must not touch. */
    private fun weak(id: String, title: String) = build(
        ScheduleEntry(
            id = EntryId(id),
            title = Confident.High(title, source),
            date = Confident.High(LocalDate.of(2026, 9, 14), source),
            startTime = Confident.High(LocalTime.of(10, 0), source),
            endTime = Confident.Missing("no end given"),
            location = Confident.Low("K1O", source, "this could be K10 or K1O"),
        ),
        assumedDuration = Duration.ofHours(1),
    )

    private fun build(
        entry: ScheduleEntry,
        assumedDuration: Duration? = null,
        allowAllDay: Boolean = false,
    ) = (
        CalendarEventCandidate.from(
            entry, zone, term, assumedDuration = assumedDuration, allowAllDay = allowAllDay,
        ) as CalendarEventCandidate.Result.Accepted
        ).candidate

    // --- what gets asked ---

    @Test
    fun `identical flags collapse into one question each, biggest first`() {
        val candidates = listOf(
            dateOnly("a", "Fee payment"),
            dateOnly("b", "Insurance premium"),
            dateOnly("c", "Licence renewal"),
            startOnly("d", "Interview"),
        )

        val questions = AssumptionReview.questionsFor(candidates)

        assertThat(questions.map { it.rule })
            .containsExactly("all-day-from-date-only", "assumed-duration").inOrder()
        assertThat(questions.first().affected).isEqualTo(3)
        // Real titles, so the user can see what they are agreeing to.
        assertThat(questions.first().examples)
            .containsExactly("Fee payment", "Insurance premium", "Licence renewal")
    }

    @Test
    fun `a clean set asks nothing at all`() {
        val clean = build(
            ScheduleEntry(
                id = EntryId("a"),
                title = Confident.High("Data Structures", source),
                date = Confident.High(LocalDate.of(2026, 9, 14), source),
                startTime = Confident.High(LocalTime.of(9, 0), source),
                endTime = Confident.High(LocalTime.of(10, 0), source),
            ),
        )

        assertThat(AssumptionReview.questionsFor(listOf(clean))).isEmpty()
    }

    @Test
    fun `examples do not repeat the same title three times`() {
        val questions = AssumptionReview.questionsFor(
            listOf(dateOnly("a", "Lecture"), dateOnly("b", "Lecture"), dateOnly("c", "Lecture")),
        )

        assertThat(questions.single().examples).containsExactly("Lecture")
        assertThat(questions.single().affected).isEqualTo(3)
    }

    // --- accepting ---

    @Test
    fun `accepting settles every row that asked, and only those`() {
        val candidates = listOf(
            dateOnly("a", "Fee payment"),
            dateOnly("b", "Insurance premium"),
            startOnly("c", "Interview"),
        )

        val settled = AssumptionReview.apply(
            candidates, "all-day-from-date-only", AssumedAnswer.Accept, at,
        )

        assertThat(settled.filter { it.status == CandidateStatus.Ready }.map { it.title })
            .containsExactly("Fee payment", "Insurance premium")
        // The unrelated question is still outstanding, and still asks.
        assertThat(settled.single { it.title == "Interview" }.status)
            .isEqualTo(CandidateStatus.NeedsAttention)
    }

    @Test
    fun `accepting keeps saying the value was ours`() {
        val settled = AssumptionReview.apply(
            listOf(dateOnly("a", "Fee payment")), "all-day-from-date-only", AssumedAnswer.Accept, at,
        ).single()

        // Agreement does not rewrite history: the row still records that we filled this in,
        // and now also records that the user accepted it.
        assertThat(settled.sources.filterIsInstance<SourceReference.Assumed>()).isNotEmpty()
        assertThat(
            settled.sources.filterIsInstance<SourceReference.UserProvided>().map { it.field },
        ).contains("assumption:all-day-from-date-only")
        assertThat(settled.assumptions).isEmpty()
        assertThat(settled.isAllDay).isTrue()
    }

    @Test
    fun `a row flagged for a bad reading stays flagged after accepting`() {
        // The whole hazard of a bulk answer: someone agreeing that an hour is a fine default
        // has not confirmed that the room is K10 rather than K1O.
        val settled = AssumptionReview.apply(
            listOf(weak("a", "Compiler Design")), "assumed-duration", AssumedAnswer.Accept, at,
        ).single()

        assertThat(settled.assumptions).isEmpty()
        assertThat(settled.status).isEqualTo(CandidateStatus.NeedsAttention)
    }

    // --- choosing a time instead ---

    @Test
    fun `choosing a time turns all-day rows into timed ones the user owns`() {
        val settled = AssumptionReview.apply(
            listOf(dateOnly("a", "Fee payment")),
            "all-day-from-date-only",
            AssumedAnswer.UseTime(LocalTime.of(9, 0), Duration.ofMinutes(30)),
            at,
        ).single()

        assertThat(settled.isAllDay).isFalse()
        assertThat(settled.start.toLocalTime()).isEqualTo(LocalTime.of(9, 0))
        assertThat(settled.end.toLocalTime()).isEqualTo(LocalTime.of(9, 30))
        assertThat(settled.status).isEqualTo(CandidateStatus.Ready)
        assertThat(settled.sources.filterIsInstance<SourceReference.UserProvided>().map { it.field })
            .containsAtLeast("startTime", "endTime")
    }

    @Test
    fun `a row that cannot take a time is left alone rather than mangled`() {
        // UseTime only makes sense for an all-day row. Applying it to an assumed *end* time
        // must not silently move the event.
        val original = startOnly("a", "Interview")
        val settled = AssumptionReview.apply(
            listOf(original),
            "assumed-duration",
            AssumedAnswer.UseTime(LocalTime.of(9, 0), Duration.ofHours(1)),
            at,
        ).single()

        assertThat(settled.start).isEqualTo(original.start)
        assertThat(settled.status).isEqualTo(CandidateStatus.NeedsAttention)
    }

    // --- declining ---

    @Test
    fun `choosing to look individually changes nothing`() {
        val candidates = listOf(dateOnly("a", "Fee payment"), startOnly("b", "Interview"))
        val after = AssumptionReview.apply(
            candidates, "all-day-from-date-only", AssumedAnswer.Individually, at,
        )

        assertThat(after).isEqualTo(candidates)
    }

    @Test
    fun `order is preserved, because review is read against the page`() {
        val candidates = listOf(
            dateOnly("a", "First", day = 1),
            startOnly("b", "Second"),
            dateOnly("c", "Third", day = 3),
        )

        val after = AssumptionReview.apply(
            candidates, "all-day-from-date-only", AssumedAnswer.Accept, at,
        )

        assertThat(after.map { it.title }).containsExactly("First", "Second", "Third").inOrder()
    }
}
