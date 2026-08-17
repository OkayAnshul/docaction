package com.okayanshul.docaction.imports

import com.okayanshul.docaction.domain.BoundingBox
import com.okayanshul.docaction.domain.CalendarEventCandidate
import com.okayanshul.docaction.domain.Confident
import com.okayanshul.docaction.domain.DocumentFormat
import com.okayanshul.docaction.domain.DocumentSource
import com.okayanshul.docaction.domain.EntryId
import com.okayanshul.docaction.domain.GroupId
import com.okayanshul.docaction.domain.ReviewSet
import com.okayanshul.docaction.domain.ScheduleEntry
import com.okayanshul.docaction.domain.ScheduleGroup
import com.okayanshul.docaction.domain.SourceReference
import com.okayanshul.docaction.domain.TermBounds
import com.okayanshul.docaction.domain.Unresolved
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId

/**
 * Review sets built the way the pipeline builds them — through
 * [CalendarEventCandidate.from], never by hand.
 *
 * Constructing candidates directly would let a test assert against a state the production
 * choke point cannot actually produce, which is how UI tests come to pass against
 * a screen that can never show what they claim.
 */
object Fixtures {

    val zone: ZoneId = ZoneId.of("Asia/Kolkata")
    private val source = SourceReference.PdfSpan(1, BoundingBox(0f, 0f, 10f, 10f))
    private val term = TermBounds(LocalDate.of(2026, 8, 17), LocalDate.of(2026, 12, 5))

    private fun <T : Any> high(value: T) = Confident.High(value, source)

    fun candidate(
        id: String,
        title: String = "Data Structures",
        weekday: DayOfWeek? = DayOfWeek.MONDAY,
        date: LocalDate? = null,
        start: LocalTime = LocalTime.of(9, 0),
        end: LocalTime = LocalTime.of(10, 0),
        location: Confident<String> = high("K10"),
    ): CalendarEventCandidate {
        val entry = ScheduleEntry(
            id = EntryId(id),
            title = high(title),
            date = date?.let { high(it) } ?: Confident.Missing("no date"),
            weekday = weekday?.let { high(it) } ?: Confident.Missing("no weekday"),
            startTime = high(start),
            endTime = high(end),
            location = location,
        )
        val result = CalendarEventCandidate.from(entry, zone, term)
        return (result as CalendarEventCandidate.Result.Accepted).candidate
    }

    /** A start time with no end — what an interview letter or a flight gives us. */
    fun assumedEnd(id: String, title: String = "Interview") = ScheduleEntry(
        id = EntryId(id),
        title = high(title),
        date = high(LocalDate.of(2026, 9, 21)),
        startTime = high(LocalTime.of(10, 30)),
        endTime = Confident.Missing("this row gave no end time"),
    ).let {
        (CalendarEventCandidate.from(it, zone, term, assumedDuration = Duration.ofHours(1))
            as CalendarEventCandidate.Result.Accepted).candidate
    }

    /** A date with no time at all — a bill, a deadline, a booking day. */
    fun allDay(id: String, title: String = "Electricity bill due") = ScheduleEntry(
        id = EntryId(id),
        title = high(title),
        date = high(LocalDate.of(2026, 9, 15)),
        startTime = Confident.Missing("no time given"),
        endTime = Confident.Missing("no end time given"),
    ).let {
        (CalendarEventCandidate.from(it, zone, term, allowAllDay = true)
            as CalendarEventCandidate.Result.Accepted).candidate
    }

    /** A candidate the engine flagged, because one of its fields was read weakly. */
    fun flagged(id: String, title: String = "Compiler Design") = candidate(
        id = id,
        title = title,
        location = Confident.Low("K1O", source, "this could be K10 or K1O"),
    )

    fun review(
        candidates: List<CalendarEventCandidate>,
        unresolved: List<Unresolved> = emptyList(),
    ) = ReviewSet(
        source = DocumentSource("/tmp/timetable.pdf", "timetable.pdf", "application/pdf", 1_024),
        format = DocumentFormat.Pdf,
        groups = listOf(
            ScheduleGroup(GroupId("g1"), "Section CS-1", emptyList(), source),
        ),
        selectedGroup = GroupId("g1"),
        candidates = candidates,
        unresolved = unresolved,
    )

    fun unresolved(id: String, field: Unresolved.Field) =
        Unresolved(EntryId(id), field, "question", listOf(source))
}
