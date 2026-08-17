package com.okayanshul.docaction.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

@JvmInline value class GroupId(val value: String)

@JvmInline value class EntryId(val value: String)

@JvmInline value class ImportId(val value: String)

@JvmInline value class CandidateId(val value: String)

/**
 * One readable schedule found in a document. A workbook with four stacked timetables
 * produces four of these, and the user chooses — we never import all of them.
 */
/**
 * What sort of thing this schedule is.
 *
 * The prose extractor has always worked this out and the engine has always thrown it away.
 * It decides two things that matter: whether a date with no time should become an all-day
 * item, and which reminder ladder the user gets — a class wants a nudge fifteen minutes
 * before, a fee deadline wants one a week before.
 */
enum class ScheduleKind { Weekly, Deadline, Appointment, Travel, Event, Unknown }

data class ScheduleGroup(
    val id: GroupId,
    val label: String,
    val entries: List<ScheduleEntry>,
    val source: SourceReference,
    val kind: ScheduleKind = ScheduleKind.Unknown,
    /**
     * Roughly how many entries this holds, for a group that has not been built yet.
     *
     * A workbook with hundreds of sections is listed before any of them is extracted — see
     * [ScheduleSource] — and the picker still has to tell the user how big each one is.
     * Without this it showed "0 entries" beside every section of a real institutional
     * export, which reads as "all of these are empty" and is simply false.
     */
    val estimatedSize: Int? = null,
) {
    /** Which ladder the reminders for this schedule should use. */
    val reminderKind: ReminderKind
        get() = when (kind) {
            ScheduleKind.Weekly -> ReminderKind.Class
            ScheduleKind.Deadline -> ReminderKind.Deadline
            else -> ReminderKind.Appointment
        }

    /** What to show in a picker: what we built, or what we counted before building. */
    val size: Int get() = if (entries.isNotEmpty()) entries.size else estimatedSize ?: 0
}

/**
 * One class, exam, or appointment before it becomes an action.
 *
 * Exactly one of [date] and [weekday] is expected to be present, depending on whether
 * this is a dated schedule or a recurring one. That is a validation rule rather than a
 * type constraint — "one of these two" is awkward to express and easy to state.
 */
data class ScheduleEntry(
    val id: EntryId,
    val title: Confident<String>,
    val date: Confident<LocalDate> = Confident.Missing("no date in this entry"),
    val weekday: Confident<DayOfWeek> = Confident.Missing("no weekday in this entry"),
    val startTime: Confident<LocalTime> = Confident.Missing("no start time in this entry"),
    val endTime: Confident<LocalTime> = Confident.Missing("no end time in this entry"),
    val location: Confident<String> = Confident.Missing("no location in this entry"),
    val instructor: Confident<String> = Confident.Missing("no instructor in this entry"),
) {
    /** Every source this entry can point at, for the "View source" sheet. */
    val sources: List<SourceReference>
        get() = listOfNotNull(
            title.sourceOrNull,
            date.sourceOrNull,
            weekday.sourceOrNull,
            startTime.sourceOrNull,
            endTime.sourceOrNull,
            location.sourceOrNull,
            instructor.sourceOrNull,
        )
}

enum class Frequency { Weekly }

/**
 * [until] is non-null by construction — an unbounded weekly recurrence is never written.
 * Where no end date is derivable the user is asked, and may decline recurrence entirely
 * in favour of dated events. See docs/05-architecture.md ADR-005.
 */
data class Recurrence(
    val frequency: Frequency,
    val byWeekday: Set<DayOfWeek>,
    val until: LocalDate,
    val exceptions: List<LocalDate> = emptyList(),
) {
    /**
     * The occurrences of this recurrence that start inside `[from, to)`.
     *
     * Expanded lazily over the requested window and never materialised: a term's worth of
     * weekly classes is hundreds of occurrences, and the scheduler only ever needs the next
     * couple of days.
     *
     * The expansion walks **local dates** and only then applies the zone, so a class at
     * 09:00 stays at 09:00 across a daylight-saving transition. Doing the arithmetic in
     * instants instead would silently shift every class by an hour for half the term.
     */
    fun occurrencesBetween(
        from: java.time.Instant,
        to: java.time.Instant,
        at: java.time.LocalTime,
        zone: java.time.ZoneId,
    ): List<java.time.ZonedDateTime> {
        if (byWeekday.isEmpty() || !from.isBefore(to)) return emptyList()

        val firstDay = from.atZone(zone).toLocalDate()
        val lastDay = minOf(to.atZone(zone).toLocalDate(), until)

        val occurrences = mutableListOf<java.time.ZonedDateTime>()
        var day = firstDay
        while (!day.isAfter(lastDay)) {
            if (day.dayOfWeek in byWeekday && day !in exceptions) {
                val start = java.time.ZonedDateTime.of(day, at, zone)
                val instant = start.toInstant()
                if (!instant.isBefore(from) && instant.isBefore(to)) occurrences += start
            }
            day = day.plusDays(1)
        }
        return occurrences
    }
}

/** Term start and end, supplied by the document or by the user. */
data class TermBounds(val start: LocalDate, val end: LocalDate) {
    init {
        require(!end.isBefore(start)) { "term end must not precede term start" }
    }
}
