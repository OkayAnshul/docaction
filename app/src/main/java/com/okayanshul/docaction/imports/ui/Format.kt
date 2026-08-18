package com.okayanshul.docaction.imports.ui

import com.okayanshul.docaction.domain.CalendarEventCandidate
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * How dates and times are written on screen.
 *
 * Everything here is locale-formatted through `java.time`, never hand-assembled. The one
 * deliberate exception is the day-of-week heading, which is always spelled out: "Mon" beside
 * a date is the kind of abbreviation that reads as a guess.
 */
object Format {

    private val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    private val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    private val dayName = DateTimeFormatter.ofPattern("EEEE")

    fun time(at: ZonedDateTime): String = time.format(at)

    fun date(at: LocalDate): String = date.format(at)

    fun day(at: ZonedDateTime): String = dayName.format(at)

    /**
     * An instant already in the user's calendar, shown in their own zone.
     *
     * Used for duplicate rows, where the point is to let someone recognise an event they
     * already have — so it is read back in the zone they are standing in, not the one the
     * document was written in.
     */
    fun dateTime(epochMillis: Long): String {
        val at = java.time.Instant.ofEpochMilli(epochMillis).atZone(java.time.ZoneId.systemDefault())
        return "${date.format(at.toLocalDate())}, ${time.format(at)}"
    }

    /**
     * The middle line of a review row.
     *
     * A weekly class says which day it repeats and when it stops; a one-off says its date.
     * The end date is always present because an unbounded recurrence is never written, and
     * showing it is how the user can tell.
     */
    fun whenLine(candidate: CalendarEventCandidate): String {
        // Showing "12:00 AM – 12:00 AM" for a bill due on the 15th would be technically
        // derived from the same fields and completely wrong to read.
        if (candidate.isAllDay) {
            return "${day(candidate.start)}, ${date(candidate.start.toLocalDate())} · All day"
        }

        val times = "${time(candidate.start)} – ${time(candidate.end)}"
        val recurrence = candidate.recurrence
        return if (recurrence == null) {
            "${day(candidate.start)}, ${date(candidate.start.toLocalDate())} · $times"
        } else {
            "Every ${day(candidate.start).lowercase()} · $times · until ${date(recurrence.until)}"
        }
    }

    /** The heading a row is filed under in the review list. */
    fun grouping(candidate: CalendarEventCandidate): String =
        if (candidate.recurrence != null) {
            day(candidate.start)
        } else {
            "${day(candidate.start)}, ${date(candidate.start.toLocalDate())}"
        }
}
