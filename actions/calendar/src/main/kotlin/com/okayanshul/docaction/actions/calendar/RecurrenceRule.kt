package com.okayanshul.docaction.actions.calendar

import com.okayanshul.docaction.domain.Recurrence
import java.time.DayOfWeek
import java.time.Duration
import java.time.format.DateTimeFormatter

/**
 * Formats recurrence for the Calendar Provider.
 *
 * Two details here are the difference between a timetable that works and one that silently
 * doesn't:
 *
 * 1. A recurring event must specify **`DURATION`, never `DTEND`**. The provider rejects a
 *    recurring row carrying `DTEND`, and inside a batch that rejection surfaces as a
 *    partial write rather than an obvious error.
 * 2. `UNTIL` is in **UTC**, marked with a trailing `Z`. A local-time `UNTIL` drifts the end
 *    of the series by the offset — a term that stops a day early or late.
 */
object RecurrenceRule {

    private val untilFormat = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

    /** `FREQ=WEEKLY;BYDAY=MO,WE;UNTIL=20261205T000000Z` */
    fun toRRule(recurrence: Recurrence): String {
        val days = recurrence.byWeekday
            .sortedBy { it.value }
            .joinToString(",") { it.toIcs() }

        // End of the final day, so a class *on* the last day is still included.
        val until = recurrence.until
            .plusDays(1)
            .atStartOfDay(java.time.ZoneOffset.UTC)
            .format(untilFormat)

        return "FREQ=WEEKLY;BYDAY=$days;UNTIL=$until"
    }

    /**
     * RFC 5545 duration in seconds — the form the provider accepts most reliably.
     * A zero or negative duration is never emitted; the candidate builder already rejects
     * those, and this is the second line of defence.
     */
    fun toDuration(duration: Duration): String {
        val seconds = duration.seconds.coerceAtLeast(MIN_SECONDS)
        return "P${seconds}S"
    }

    /** `EXDATE` for skipped occurrences. Empty when there are none — never an empty property. */
    fun toExDate(recurrence: Recurrence, timeZone: String): String? {
        if (recurrence.exceptions.isEmpty()) return null
        val dates = recurrence.exceptions.joinToString(",") {
            it.atStartOfDay().format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
        }
        return "TZID=$timeZone:$dates"
    }

    private fun DayOfWeek.toIcs(): String = when (this) {
        DayOfWeek.MONDAY -> "MO"
        DayOfWeek.TUESDAY -> "TU"
        DayOfWeek.WEDNESDAY -> "WE"
        DayOfWeek.THURSDAY -> "TH"
        DayOfWeek.FRIDAY -> "FR"
        DayOfWeek.SATURDAY -> "SA"
        DayOfWeek.SUNDAY -> "SU"
    }

    /** One minute. Shorter than any real class, longer than zero. */
    private const val MIN_SECONDS = 60L
}
