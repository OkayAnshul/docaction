package com.okayanshul.docaction.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * An event the user typed in themselves.
 *
 * Every extraction failure used to be a dead end: the engine said "I couldn't read this" and
 * there was no way to get an event into the calendar except finding a document it *could*
 * read. This is the way out, and it is also the fastest path for someone with three dates to
 * add and no document at all.
 *
 * **It deliberately introduces no new construction path.** A manual event is a
 * [ScheduleEntry] whose every field is [Confident.High] with a
 * [SourceReference.UserProvided] source, handed to the same
 * [CalendarEventCandidate.from] choke point as everything the engine extracts. Nothing is
 * `Low`, nothing is assumed, so the candidate comes out [CandidateStatus.Ready] on its own —
 * not because this code says so, but because there is genuinely nothing uncertain about a
 * value a person just typed.
 *
 * That matters beyond tidiness. It means a hand-typed event answers "where did this come
 * from?" with *"you told us, on 14 September"* rather than with a shrug, it is undone by the
 * same provenance-based undo as an imported one, and the single function that has to be
 * correct for the app to never fabricate a calendar entry is still the single function.
 */
object ManualEvent {

    /**
     * A one-off event on a specific date.
     *
     * @param end null when the user gave no end time. Left [Confident.Missing] rather than
     *   filled in, so the same rules that govern a document with a missing end time govern
     *   this — the caller opts into an assumed duration or the choke point rejects it, and
     *   either way nobody quietly invents an hour.
     */
    fun dated(
        title: String,
        date: LocalDate,
        start: LocalTime?,
        end: LocalTime?,
        location: String? = null,
        id: EntryId = EntryId("manual-${java.util.UUID.randomUUID()}"),
        at: Long = System.currentTimeMillis(),
    ): ScheduleEntry = ScheduleEntry(
        id = id,
        title = given("title", title, at),
        date = Confident.High(date, source("date", at)),
        startTime = start?.let { Confident.High(it, source("startTime", at)) }
            ?: Confident.Missing("you didn't give a start time"),
        endTime = end?.let { Confident.High(it, source("endTime", at)) }
            ?: Confident.Missing("you didn't give an end time"),
        location = location?.trim()?.takeIf { it.isNotBlank() }
            ?.let { Confident.High(it, source("location", at)) }
            ?: Confident.Missing("you didn't give a place"),
    )

    /**
     * A weekly class or shift.
     *
     * Becomes a recurring event bounded by the term, exactly as an imported timetable row
     * does — one repeating event, never fifteen copies.
     */
    fun weekly(
        title: String,
        weekday: DayOfWeek,
        start: LocalTime,
        end: LocalTime,
        location: String? = null,
        id: EntryId = EntryId("manual-${java.util.UUID.randomUUID()}"),
        at: Long = System.currentTimeMillis(),
    ): ScheduleEntry = ScheduleEntry(
        id = id,
        title = given("title", title, at),
        weekday = Confident.High(weekday, source("weekday", at)),
        startTime = Confident.High(start, source("startTime", at)),
        endTime = Confident.High(end, source("endTime", at)),
        location = location?.trim()?.takeIf { it.isNotBlank() }
            ?.let { Confident.High(it, source("location", at)) }
            ?: Confident.Missing("you didn't give a place"),
    )

    /**
     * An all-day item: a deadline, a bill, a booking day.
     *
     * Distinct from [dated] with no times rather than a special case of it, because the
     * difference is a decision the user made — "this has no time" — not an absence we
     * discovered. It therefore carries no [Assumption]: nothing was inferred.
     */
    fun allDay(
        title: String,
        date: LocalDate,
        location: String? = null,
        id: EntryId = EntryId("manual-${java.util.UUID.randomUUID()}"),
        at: Long = System.currentTimeMillis(),
    ): ScheduleEntry = dated(title, date, start = null, end = null, location = location, id = id, at = at)

    /**
     * Builds the candidate, or says why it cannot.
     *
     * [allowAllDay] is on because a user who left the time blank has chosen an all-day item
     * rather than failed to read one. The resulting [Assumption.NoTimeOfDay] is still
     * recorded and still shown — the row will say the time came from us — which is the
     * honest description of what happened and costs the user one glance to confirm.
     */
    fun candidate(
        entry: ScheduleEntry,
        zone: ZoneId,
        term: TermBounds?,
        reminderMinutes: Int? = null,
    ): CalendarEventCandidate.Result = CalendarEventCandidate.from(
        entry = entry,
        zone = zone,
        term = term,
        reminderMinutes = reminderMinutes,
        allowAllDay = true,
    )

    /** A title is required, so it is the one field that has no `Missing` branch. */
    private fun given(field: String, value: String, at: Long): Confident<String> {
        val clean = value.trim()
        return if (clean.isEmpty()) {
            Confident.Missing("an event needs a name")
        } else {
            Confident.High(clean, source(field, at))
        }
    }

    private fun source(field: String, at: Long) = SourceReference.UserProvided(field, at)
}
