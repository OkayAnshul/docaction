package com.okayanshul.docaction.domain

import java.time.Duration
import java.time.LocalTime
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** Why a candidate can't be committed yet, phrased for the user. */
data class Unresolved(
    val entryId: EntryId,
    val field: Field,
    val question: String,
    val sources: List<SourceReference>,
) {
    enum class Field { Title, Date, Weekday, StartTime, EndTime, Location, Recurrence }
}

enum class CandidateStatus { Ready, NeedsAttention }

sealed interface ActionCandidate {
    val id: CandidateId
    val sources: List<SourceReference>
    val status: CandidateStatus
}

/**
 * Something this candidate carries that the document never said.
 *
 * The engine's original rule was absolute: invent nothing, ever. That rule was correct in
 * spirit and wrong in practice — it rejected a bill with a due date because the bill had no
 * end time, and 26 of 42 real documents produced nothing at all. The rule is now narrower
 * and, I think, truer: **we may fill a gap, but never quietly.**
 *
 * The enforcement is structural, not a convention. An assumption can only be created inside
 * [CalendarEventCandidate.from], a non-empty list forces [CandidateStatus.NeedsAttention],
 * and each one adds a [SourceReference.Assumed] so Source View says "we assumed this"
 * instead of pointing at a page that says no such thing. There is no way to add one and
 * leave the row looking certain.
 */
sealed interface Assumption {
    /** The field we filled, so the review row can offer the right fix. */
    val field: Unresolved.Field

    /** Names the rule, for goldens and for Source View. Never shown to the user raw. */
    val rule: String

    /** A start time with no end. We give it a length; the user sees that we did. */
    data class EndTime(val duration: Duration) : Assumption {
        override val field = Unresolved.Field.EndTime
        override val rule = "assumed-duration"
    }

    /** A date with no clock time at all — a due date, a deadline, a booking day. */
    data class NoTimeOfDay(val date: LocalDate) : Assumption {
        override val field = Unresolved.Field.StartTime
        override val rule = "all-day-from-date-only"
    }
}

/**
 * When this happens.
 *
 * Sealed because the *write* differs, not merely the value: the calendar provider needs
 * `ALL_DAY=1`, `EVENT_TIMEZONE=UTC` and midnight-UTC boundaries for an all-day row, and none
 * of that is derivable from a start/end pair. Collapsing the two into one shape is how an
 * all-day event ends up on the wrong day for everyone east of UTC.
 */
sealed interface EventTiming {
    data class Timed(val start: ZonedDateTime, val end: ZonedDateTime) : EventTiming

    data class AllDay(val date: LocalDate, val zone: ZoneId) : EventTiming
}

/**
 * A calendar event that is ready to be written.
 *
 * Note the fields are plain resolved values, not [Confident]. Confidence exists during
 * extraction and review; by the time something is a candidate, every required field has
 * been read with sufficient confidence or explicitly supplied by the user.
 *
 * The private constructor plus [from] is the single choke point where that transition is
 * enforced — one function to audit, rather than a property distributed across the
 * codebase and dependent on everyone remembering it. See docs/09-confidence.md.
 */
class CalendarEventCandidate private constructor(
    override val id: CandidateId,
    val entryId: EntryId,
    val title: String,
    val timing: EventTiming,
    val location: String?,
    val recurrence: Recurrence?,
    val reminderMinutes: Int?,
    val reminderKind: ReminderKind,
    /** Empty for a candidate read entirely from the document. See [Assumption]. */
    val assumptions: List<Assumption>,
    override val sources: List<SourceReference>,
    override val status: CandidateStatus,
) : ActionCandidate {

    /**
     * [start] and [end] stay available for everything that only needs a moment in time —
     * duplicate detection, reminder planning, sorting, the review row.
     *
     * For an all-day item they span the local day, which is the right answer for every one
     * of those callers. Only the calendar write needs to know the difference, and it asks
     * [timing] directly.
     */
    val start: ZonedDateTime
        get() = when (timing) {
            is EventTiming.Timed -> timing.start
            is EventTiming.AllDay -> timing.date.atStartOfDay(timing.zone)
        }

    val end: ZonedDateTime
        get() = when (timing) {
            is EventTiming.Timed -> timing.end
            is EventTiming.AllDay -> timing.date.plusDays(1).atStartOfDay(timing.zone)
        }

    val isAllDay: Boolean get() = timing is EventTiming.AllDay

    val duration: Duration get() = Duration.between(start, end)

    fun withReminder(minutes: Int?) = CalendarEventCandidate(
        id, entryId, title, timing, location, recurrence, minutes, reminderKind,
        assumptions, sources, status,
    )

    /**
     * The user corrected this event on the review screen.
     *
     * Corrections come through here rather than through a copy constructor so that each
     * changed field gains a [SourceReference.UserProvided] entry. A corrected value has to
     * stay as traceable as an extracted one — otherwise Source View would keep pointing at
     * a document region that no longer says what the event says.
     *
     * An edited event becomes [CandidateStatus.Ready]: the user has just supplied the
     * missing certainty, and continuing to flag it would teach people to ignore the flag.
     *
     * @return null when the edit would produce an event that cannot exist — a zero-length
     *   one. Never throws: this is called straight from a button, and a crash is a far
     *   worse answer than a disabled Save.
     */
    fun edited(
        title: String = this.title,
        start: ZonedDateTime = this.start,
        end: ZonedDateTime = this.end,
        location: String? = this.location,
        atEpochMillis: Long = System.currentTimeMillis(),
    ): CalendarEventCandidate? {
        if (!end.isAfter(start)) return null
        val cleanTitle = title.trim().ifBlank { return null }

        val changed = buildList {
            if (cleanTitle != this@CalendarEventCandidate.title) add("title")
            if (start != this@CalendarEventCandidate.start) add("start")
            if (end != this@CalendarEventCandidate.end) add("end")
            if (location != this@CalendarEventCandidate.location) add("location")
        }
        if (changed.isEmpty()) return this

        // Moving a weekly class to another day moves the whole series with it. Leaving the
        // rule behind would write a Tuesday event that then repeats every Monday.
        val movedRecurrence = recurrence?.let {
            if (start.dayOfWeek == this.start.dayOfWeek) it
            else it.copy(byWeekday = setOf(start.dayOfWeek))
        }

        // Setting a time on an all-day item makes it a timed one — that is the whole point
        // of offering "add a time" — and any assumption the user has just overruled is gone.
        // Leaving a stale "end time assumed" on a row the user has since set by hand would
        // be the app arguing with someone who is right.
        val settled = changed.mapNotNull { field ->
            when (field) {
                "start" -> Unresolved.Field.StartTime
                "end" -> Unresolved.Field.EndTime
                else -> null
            }
        }.toSet()

        return CalendarEventCandidate(
            id = id,
            entryId = entryId,
            title = cleanTitle,
            timing = EventTiming.Timed(start, end),
            location = location?.trim()?.ifBlank { null },
            recurrence = movedRecurrence,
            reminderMinutes = reminderMinutes,
            reminderKind = reminderKind,
            assumptions = assumptions.filterNot { it.field in settled },
            sources = sources + changed.map { SourceReference.UserProvided(it, atEpochMillis) },
            status = CandidateStatus.Ready,
        )
    }

    /**
     * A correction that leaves the item all-day — its name, its date, or where it is.
     *
     * Separate from [edited] because that one always produces a timed event: it exists to
     * record that the user supplied clock times. Someone fixing the *name* of a bill has not
     * decided it now happens at nine in the morning, and forcing a time on them would be the
     * app inventing something under cover of an edit.
     */
    fun editedAllDay(
        title: String = this.title,
        date: LocalDate = (timing as? EventTiming.AllDay)?.date ?: start.toLocalDate(),
        location: String? = this.location,
        atEpochMillis: Long = System.currentTimeMillis(),
    ): CalendarEventCandidate? {
        val cleanTitle = title.trim().ifBlank { return null }
        val zone = (timing as? EventTiming.AllDay)?.zone ?: start.zone

        val changed = buildList {
            if (cleanTitle != this@CalendarEventCandidate.title) add("title")
            if (date != (timing as? EventTiming.AllDay)?.date) add("date")
            if (location != this@CalendarEventCandidate.location) add("location")
        }
        if (changed.isEmpty()) return this

        return CalendarEventCandidate(
            id = id,
            entryId = entryId,
            title = cleanTitle,
            timing = EventTiming.AllDay(date, zone),
            location = location?.trim()?.ifBlank { null },
            recurrence = recurrence,
            reminderMinutes = reminderMinutes,
            reminderKind = reminderKind,
            // The item is still all-day because we made it so, and correcting its name does
            // not change that. The flag stays until the user gives it a time.
            assumptions = assumptions,
            sources = sources + changed.map { SourceReference.UserProvided(it, atEpochMillis) },
            status = status,
        )
    }

    override fun toString() =
        "CalendarEventCandidate($title, $timing, recurring=${recurrence != null}, " +
            "assumed=${assumptions.map { it.rule }})"

    companion object {

        /**
         * The only way to construct a candidate.
         *
         * Rejects, in order: a missing or unusable title, an unbounded recurrence, a missing
         * date, a missing start time, a missing end time, and any interval that is not
         * strictly positive. Anything rejected here becomes an [Unresolved] question in the
         * review screen rather than a weak candidate — "2 items need your attention" is a
         * list of questions, not of guesses.
         *
         * [assumedDuration] and [allowAllDay] are the two places where that absolutism was
         * relaxed, and both default to **off** so the strict behaviour remains the behaviour
         * of this function unless a caller deliberately opts in. See [Assumption] for why,
         * and ADR-012 for the reversal this represents.
         *
         * @param assumedDuration when set, a start time with no end gets this length and an
         *   [Assumption.EndTime]. Null keeps the original rule: reject.
         * @param allowAllDay when true, a date with no time at all becomes an all-day item
         *   with an [Assumption.NoTimeOfDay]. False keeps the original rule: reject.
         */
        fun from(
            entry: ScheduleEntry,
            zone: ZoneId,
            term: TermBounds?,
            reminderMinutes: Int? = null,
            reminderKind: ReminderKind = ReminderKind.Class,
            assumedDuration: Duration? = null,
            allowAllDay: Boolean = false,
        ): Result {
            val unresolved = mutableListOf<Unresolved>()
            val assumptions = mutableListOf<Assumption>()

            val title = entry.title.takeIfUsable()
                ?: return reject(entry, Unresolved.Field.Title, "What is this called?")

            // A recurring entry needs a weekday and a term to bound it; a dated entry
            // needs a date. Neither is ever synthesised.
            val recurring = entry.weekday.isUsable
            val firstDate: LocalDate
            val recurrence: Recurrence?

            if (recurring) {
                val weekday = entry.weekday.takeIfUsable()!!
                if (term == null) {
                    return reject(
                        entry,
                        Unresolved.Field.Recurrence,
                        "When does this schedule end? Weekly classes need an end date.",
                    )
                }
                firstDate = firstOccurrenceOf(weekday, term.start)
                if (firstDate.isAfter(term.end)) {
                    return reject(
                        entry,
                        Unresolved.Field.Recurrence,
                        "This class doesn't occur before the term ends.",
                    )
                }
                recurrence = Recurrence(Frequency.Weekly, setOf(weekday), term.end)
            } else {
                firstDate = entry.date.takeIfUsable()
                    ?: return reject(entry, Unresolved.Field.Date, "What date is this on?")
                recurrence = null
            }

            val start = entry.startTime.takeIfUsable()

            if (start == null) {
                // A weekly entry with no time must never become all-day. One unreadable time
                // column would otherwise put fifteen weeks of all-day rows across five days
                // of someone's calendar — the worst blast radius available here.
                if (recurring) {
                    return reject(entry, Unresolved.Field.StartTime, "When does this start?")
                }
                // Deliberately `is Missing`, not `!isUsable`. A Low reading means we *did*
                // read something and read it badly; replacing that with an invention is
                // worse than admitting we could not read it.
                if (!allowAllDay || entry.startTime !is Confident.Missing) {
                    return reject(entry, Unresolved.Field.StartTime, "When does this start?")
                }

                assumptions += Assumption.NoTimeOfDay(firstDate)
                return accept(
                    entry, title, EventTiming.AllDay(firstDate, zone), recurrence,
                    reminderMinutes, reminderKind, assumptions, unresolved,
                )
            }

            val declaredEnd = entry.endTime.takeIfUsable()
            val end = when {
                declaredEnd != null -> declaredEnd
                // Same reasoning as above: only an absent end time may be filled in.
                assumedDuration != null && entry.endTime is Confident.Missing -> {
                    assumptions += Assumption.EndTime(assumedDuration)
                    start.plus(assumedDuration)
                }

                else -> return reject(entry, Unresolved.Field.EndTime, "When does this end?")
            }

            // An end before the start means the event crosses midnight, which is legal.
            // An end equal to the start is not — a zero-length event is never padded.
            if (end == start) {
                return reject(entry, Unresolved.Field.EndTime, "This starts and ends at the same time.")
            }
            val endDate = if (end.isBefore(start)) firstDate.plusDays(1) else firstDate

            return accept(
                entry, title,
                EventTiming.Timed(
                    ZonedDateTime.of(firstDate, start, zone),
                    ZonedDateTime.of(endDate, end, zone),
                ),
                recurrence, reminderMinutes, reminderKind, assumptions, unresolved,
            )
        }

        /**
         * The one place a candidate is built, so the flagging rule cannot be forgotten.
         *
         * A row is flagged when any field was read weakly **or** when we assumed anything.
         * That second clause is what makes [Assumption] safe to have at all: there is no
         * path that adds an assumption and leaves the row looking certain.
         */
        private fun accept(
            entry: ScheduleEntry,
            title: String,
            timing: EventTiming,
            recurrence: Recurrence?,
            reminderMinutes: Int?,
            reminderKind: ReminderKind,
            assumptions: List<Assumption>,
            unresolved: List<Unresolved>,
        ): Result {
            val readWeakly = listOf(
                entry.title, entry.startTime, entry.endTime, entry.location,
            ).any { it is Confident.Low }

            return Result.Accepted(
                CalendarEventCandidate(
                    id = CandidateId(entry.id.value),
                    entryId = entry.id,
                    title = title,
                    timing = timing,
                    location = entry.location.takeIfUsable(),
                    recurrence = recurrence,
                    reminderMinutes = reminderMinutes,
                    reminderKind = reminderKind,
                    assumptions = assumptions,
                    // Source View must be able to say "we assumed this" rather than point at
                    // a page that says no such thing.
                    sources = entry.sources + assumptions.map {
                        SourceReference.Assumed(it.field.name, it.rule)
                    },
                    status = if (readWeakly || assumptions.isNotEmpty()) {
                        CandidateStatus.NeedsAttention
                    } else {
                        CandidateStatus.Ready
                    },
                ),
                unresolved,
            )
        }

        private fun <T : Any> Confident<T>.takeIfUsable(): T? = if (isUsable) valueOrNull else null

        private fun reject(entry: ScheduleEntry, field: Unresolved.Field, question: String) =
            Result.Rejected(Unresolved(entry.id, field, question, entry.sources))

        private fun firstOccurrenceOf(weekday: java.time.DayOfWeek, onOrAfter: LocalDate): LocalDate {
            val delta = (weekday.value - onOrAfter.dayOfWeek.value + 7) % 7
            return onOrAfter.plusDays(delta.toLong())
        }
    }

    sealed interface Result {
        data class Accepted(
            val candidate: CalendarEventCandidate,
            val unresolved: List<Unresolved>,
        ) : Result

        data class Rejected(val unresolved: Unresolved) : Result
    }
}

/**
 * Something to be reminded about: a point in time, with no duration.
 *
 * This is what the prose path's all-day entries become — a fee deadline, a premium due
 * date, a delivery window. They deliberately have no start time, so they can never be
 * calendar events without the user inventing one, but they are exactly what a reminder is
 * for.
 *
 * Same private-constructor + [from] choke point as [CalendarEventCandidate], so a
 * [Confident.Missing] field still cannot become an action.
 */
class ReminderCandidate private constructor(
    override val id: CandidateId,
    val entryId: EntryId,
    val title: String,
    /** When the thing itself happens. Reminders fire *before* this. */
    val dueAt: ZonedDateTime,
    /** True when the document gave a date but no time. */
    val allDay: Boolean,
    val kind: ReminderKind,
    val ladder: ReminderLadder,
    override val sources: List<SourceReference>,
    override val status: CandidateStatus,
) : ActionCandidate {

    fun withLadder(ladder: ReminderLadder) =
        ReminderCandidate(id, entryId, title, dueAt, allDay, kind, ladder, sources, status)

    override fun toString() = "ReminderCandidate($title, $dueAt, allDay=$allDay)"

    companion object {

        /**
         * The only construction path.
         *
         * An all-day item is anchored at [allDayAnchor] rather than midnight — a deadline
         * notification at 00:00 is useless. That anchor is a presentation choice about when
         * to *notify*, not an invented start time: [allDay] stays true and the underlying
         * entry still has no time of its own.
         */
        fun from(
            entry: ScheduleEntry,
            zone: ZoneId,
            kind: ReminderKind,
            ladder: ReminderLadder,
            allDayAnchor: LocalTime = LocalTime.of(9, 0),
        ): Result {
            val title = entry.title.let { if (it.isUsable) it.valueOrNull else null }
                ?: return Result.Rejected(
                    Unresolved(entry.id, Unresolved.Field.Title, "What is this called?", entry.sources)
                )

            val date = entry.date.let { if (it.isUsable) it.valueOrNull else null }
                ?: return Result.Rejected(
                    Unresolved(entry.id, Unresolved.Field.Date, "What date is this on?", entry.sources)
                )

            val time = entry.startTime.let { if (it.isUsable) it.valueOrNull else null }
            val needsAttention = listOf(entry.title, entry.date, entry.startTime)
                .any { it is Confident.Low }

            return Result.Accepted(
                ReminderCandidate(
                    id = CandidateId(entry.id.value),
                    entryId = entry.id,
                    title = title,
                    dueAt = ZonedDateTime.of(date, time ?: allDayAnchor, zone),
                    allDay = time == null,
                    kind = kind,
                    ladder = ladder,
                    sources = entry.sources,
                    status = if (needsAttention) CandidateStatus.NeedsAttention else CandidateStatus.Ready,
                )
            )
        }
    }

    sealed interface Result {
        data class Accepted(val candidate: ReminderCandidate) : Result
        data class Rejected(val unresolved: Unresolved) : Result
    }
}
