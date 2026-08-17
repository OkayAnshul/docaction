package com.okayanshul.docaction.extraction.prose

import com.okayanshul.docaction.core.common.Text
import com.okayanshul.docaction.extraction.table.TextLine

/**
 * Reads `Label: value` documents — which is nearly every document that is not a timetable.
 *
 * Bills, tickets, appointment letters, admit cards and booking confirmations are all built
 * the same way: a stack of labelled fields. That structure is worth reading directly,
 * because the alternative — treating the page as prose and taking whatever characters sit
 * left of the date — produces titles like `INR /2` and `3,845.00 026`. Both are real output
 * from the electricity bill in the corpus.
 *
 * Two things come out of this that the prose path could not get any other way:
 *
 * 1. **A title that names the thing.** `Last date for payment` is the label; the event is
 *    *"Last date for payment"*, not the leftover characters around the date.
 * 2. **A time from a different line than the date.** `Date: 21 Sep 2026` on one line and
 *    `Reporting time: 09:30 AM` on the next is the normal shape of an appointment letter,
 *    and reading only within a line loses the time every time.
 *
 * Deliberately a small, closed vocabulary rather than a general parser. A label that means
 * something specific ("Departure", "Due date") is worth acting on; an arbitrary `X: Y` pair
 * is not, and treating every colon as a field is how a document's footer becomes an event.
 */
class LabelledFields {

    /** One `label: value` pair found on a line. */
    data class Field(
        val line: TextLine,
        val label: String,
        val value: String,
        val role: Role,
    )

    /**
     * What a label means. The role, not the wording, is what the extractor acts on — so
     * "Due date", "Last date for payment" and "Payable by" all behave identically.
     */
    enum class Role { When, Time, Subject, Where, Ignore }

    fun read(lines: List<TextLine>): List<Field> = lines.mapNotNull { line ->
        val match = SEPARATOR.find(line.text) ?: return@mapNotNull null
        val label = Text.collapseWhitespace(line.text.take(match.range.first)).trim()
        val value = Text.collapseWhitespace(line.text.substring(match.range.last + 1)).trim()

        // A long "label" is a sentence containing a colon, not a field. Real labels are
        // short: "Due date", "Reporting time", "Venue".
        if (label.isEmpty() || label.length > MAX_LABEL_LENGTH) return@mapNotNull null
        if (value.isEmpty()) return@mapNotNull null

        val role = roleOf(label)
        if (role == Role.Ignore) null else Field(line, label, value, role)
    }

    /**
     * The document's subject, if it announces one.
     *
     * Preferred over any label-derived title: a ticket that says `Passenger: A. Sharma` and
     * `Flight: AI 505` is about the flight, and the flight number is what the user wants to
     * see in their calendar.
     */
    fun subjectOf(fields: List<Field>): Field? = fields.firstOrNull { it.role == Role.Subject }

    fun whereOf(fields: List<Field>): Field? = fields.firstOrNull { it.role == Role.Where }

    private fun roleOf(label: String): Role {
        val normalised = label.lowercase().trim(*TRIM)
        return when {
            // Order matters: "reporting time" and "departure time" are times, and both
            // contain words that would otherwise read as a date label.
            TIME_LABELS.any { normalised == it || normalised.endsWith(" $it") } -> Role.Time
            DATE_LABELS.any { normalised == it || normalised.contains(it) } -> Role.When
            SUBJECT_LABELS.any { normalised == it } -> Role.Subject
            WHERE_LABELS.any { normalised == it } -> Role.Where
            else -> Role.Ignore
        }
    }

    private companion object {
        /** `:` or an en/em dash used as a separator, but not a hyphen inside a word. */
        val SEPARATOR = Regex("""\s*[:：]\s*|\s+[–—]\s+""")

        const val MAX_LABEL_LENGTH = 34

        val TRIM = charArrayOf(' ', '\t', '*', '(', ')', '.', ',', '-', '#')

        val TIME_LABELS = setOf(
            "time", "reporting time", "departure", "arrival", "check-in", "check in",
            "checkout", "check-out", "boarding", "start time", "end time", "timing",
            "timings", "slot",
        )

        val DATE_LABELS = setOf(
            "date", "due date", "due", "last date", "valid till", "valid until", "expiry",
            "deadline", "payable by", "pay by", "on or before", "appointment date",
            "examination date", "exam date", "issue date", "journey date",
        )

        val SUBJECT_LABELS = setOf(
            "subject", "event", "purpose", "flight", "train", "service", "test", "exam",
            "examination", "course", "appointment", "description", "particulars", "for",
            "regarding", "re", "title", "post", "position", "bill for",
        )

        val WHERE_LABELS = setOf(
            "venue", "location", "place", "address", "centre", "center", "hall", "room",
            "platform", "gate", "terminal", "clinic", "hospital", "branch", "reporting at",
        )
    }
}
