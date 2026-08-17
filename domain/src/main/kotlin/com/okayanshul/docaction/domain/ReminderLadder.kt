package com.okayanshul.docaction.domain

import java.time.Duration
import java.time.Instant

/** Who is responsible for telling the user about an event. Chosen per import. */
enum class NotificationOwner {
    /** DocAction schedules its own ladder; the calendar event carries no reminder. */
    DocAction,

    /** The calendar app notifies; DocAction schedules nothing. */
    CalendarApp,
}

/** What a reminder is about, so the user can tune each kind separately in system settings. */
enum class ReminderKind { Class, Deadline, Appointment }

/**
 * When to nudge the user before something starts.
 *
 * [offsets] fire once each, furthest first. [repeatEvery], if set, keeps nudging from the
 * last offset until the event actually begins — the behaviour a calendar app does not
 * offer and the reason this exists at all.
 *
 * Pure data with pure functions: the entire scheduling brain is testable on the JVM with no
 * device, no clock, and no alarms.
 */
data class ReminderLadder(
    val offsets: List<Duration>,
    val repeatEvery: Duration? = null,
    val owner: NotificationOwner = NotificationOwner.DocAction,
) {
    init {
        require(offsets.all { !it.isNegative }) { "a reminder offset cannot be negative" }
        require(repeatEvery == null || !repeatEvery.isZero) { "repeat interval cannot be zero" }
    }

    /**
     * The instants at which this ladder should fire for one occurrence.
     *
     * Deliberately *not* filtered to a window — [dueWithin] does that. Keeping the full
     * ladder separate makes the maths easy to reason about and to test.
     */
    fun instantsFor(occurrenceStart: Instant): List<Instant> {
        val rungs = offsets.map { occurrenceStart.minus(it) }.toMutableList()

        if (repeatEvery != null) {
            // Repeat from the closest offset up to (but not including) the start itself.
            var next = (offsets.minOrNull()?.let { occurrenceStart.minus(it) } ?: occurrenceStart)
                .plus(repeatEvery)
            var guard = 0
            while (next.isBefore(occurrenceStart) && guard++ < MAX_REPEATS) {
                rungs += next
                next = next.plus(repeatEvery)
            }
        }

        return rungs.distinct().sorted()
    }

    /**
     * The instants that fall inside `[from, to)` — what the scheduler actually arms.
     *
     * A rung already in the past is dropped rather than fired late: a notification saying
     * "in 15 minutes" delivered an hour afterwards is worse than silence.
     */
    fun dueWithin(occurrenceStart: Instant, from: Instant, to: Instant): List<Instant> =
        instantsFor(occurrenceStart).filter { !it.isBefore(from) && it.isBefore(to) }

    companion object {
        /**
         * Guard against a pathological interval producing an unbounded list. At one minute
         * apart over a one-day offset this is already far more than any sane ladder.
         */
        const val MAX_REPEATS = 2_000

        /** Sensible default: a day ahead, an hour ahead, then closing in. */
        val Default = ReminderLadder(
            offsets = listOf(
                Duration.ofDays(1),
                Duration.ofHours(1),
                Duration.ofMinutes(15),
                Duration.ofMinutes(5),
            ),
        )

        /** Classes need the last-minute nudge; that is the whole point of the feature. */
        val Classes = ReminderLadder(
            offsets = listOf(Duration.ofMinutes(30), Duration.ofMinutes(10), Duration.ofMinutes(5)),
        )

        /** Deadlines are worth knowing about well in advance, and repeatedly on the day. */
        val Deadlines = ReminderLadder(
            offsets = listOf(Duration.ofDays(7), Duration.ofDays(1), Duration.ofHours(3)),
        )
    }
}
