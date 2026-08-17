package com.okayanshul.docaction.extraction.prose

import com.okayanshul.docaction.core.common.Text
import com.okayanshul.docaction.domain.Confident
import com.okayanshul.docaction.domain.EntryId
import com.okayanshul.docaction.domain.GroupId
import com.okayanshul.docaction.domain.ScheduleEntry
import com.okayanshul.docaction.domain.ScheduleGroup
import com.okayanshul.docaction.domain.ScheduleKind
import com.okayanshul.docaction.domain.SourceReference
import com.okayanshul.docaction.domain.TextRun
import com.okayanshul.docaction.domain.capAtMedium
import com.okayanshul.docaction.domain.sourceOrNull
import com.okayanshul.docaction.extraction.confidence.ConfidenceScorer
import com.okayanshul.docaction.extraction.date.DateEngine
import com.okayanshul.docaction.extraction.date.DateResolver
import com.okayanshul.docaction.extraction.date.LocatedDate
import com.okayanshul.docaction.extraction.table.TableBuilder
import com.okayanshul.docaction.extraction.table.TextLine
import com.okayanshul.docaction.extraction.time.MeridiemResolver
import com.okayanshul.docaction.extraction.time.TimeEngine
import java.time.LocalDate
import java.time.LocalTime

/**
 * What a document is asking the reader to do. Set by cue words, which classify the
 * document and **nothing else** — a cue never supplies a date, a time, or a title. That
 * separation is what keeps "due" from inventing a deadline.
 */
enum class DocumentKind { Deadline, Appointment, Travel, Event, Unknown }

/**
 * The classification, in the domain's vocabulary.
 *
 * This mapping existed nowhere: the extractor worked the kind out on every document and the
 * finder discarded it, so a fee deadline and a lecture were treated identically all the way
 * to the calendar. It decides the reminder ladder and whether a bare date may become an
 * all-day item.
 */
internal fun DocumentKind.toScheduleKind(): ScheduleKind = when (this) {
    DocumentKind.Deadline -> ScheduleKind.Deadline
    DocumentKind.Appointment -> ScheduleKind.Appointment
    DocumentKind.Travel -> ScheduleKind.Travel
    DocumentKind.Event -> ScheduleKind.Event
    DocumentKind.Unknown -> ScheduleKind.Unknown
}

data class ProseResult(
    val kind: DocumentKind,
    val group: ScheduleGroup?,
    val reason: String? = null,
)

/**
 * Extracts dated events from prose, for the documents that are not grids at all: notices,
 * circulars, bills, booking confirmations, appointment letters, academic calendars,
 * conference programmes.
 *
 * Reuses the deterministic engines wholesale — [DateEngine], [DateResolver], [TimeEngine],
 * [MeridiemResolver]. In particular the whole page's dates are resolved *together*, so a
 * single unambiguous date elsewhere in a notice settles the reading of an ambiguous one,
 * exactly as it does inside a table.
 *
 * Everything here produces **dated** entries. It never infers weekly recurrence: a notice
 * that mentions "Monday" is talking about one Monday, and reading it as a repeating class
 * is the mistake that a conference programme triggered on the real corpus.
 */
class ProseExtractor(
    private val labels: LabelledFields = LabelledFields(),
    private val dateEngine: DateEngine = DateEngine(),
    private val dateResolver: DateResolver = DateResolver(),
    private val timeEngine: TimeEngine = TimeEngine(),
    private val meridiem: MeridiemResolver = MeridiemResolver(),
    private val scorer: ConfidenceScorer = ConfidenceScorer(),
    private val tables: TableBuilder = TableBuilder(),
) {

    fun extract(
        runs: List<TextRun>,
        label: String,
        sourceOf: (TextLine) -> SourceReference,
        assumedYear: Int? = null,
    ): ProseResult {
        val usable = runs.filter { it.text.isNotBlank() }
        if (usable.isEmpty()) return ProseResult(DocumentKind.Unknown, null, "there is no text here")

        val unit = tables.medianHeight(usable)
        if (unit <= 0f) return ProseResult(DocumentKind.Unknown, null, "there is no text here")
        val lines = tables.clusterIntoLines(usable, unit)

        val kind = classify(lines.joinToString(" ") { it.text })

        // Read before anything else: on a label-value document these decide the title, the
        // location, and — when the date and the time are on different lines — the time.
        val fields = labels.read(lines)

        // Every date on the page resolves as one document, so ambiguity is settled by the
        // strongest evidence available anywhere in it rather than line by line.
        val anchors = lines.mapNotNull { line ->
            val match = dateEngine.parse(line.text).firstOrNull { it.readings.isNotEmpty() }
                ?: return@mapNotNull null
            val weekday = dateEngine.parse(line.text).firstOrNull { it.weekday != null }?.weekday
            Anchor(line, LocatedDate(match, sourceOf(line), weekday))
        }

        if (anchors.isEmpty()) {
            return ProseResult(kind, null, "no dates found in this document")
        }

        val resolution = dateResolver.resolve(anchors.map { it.located }, assumedYear = assumedYear)

        val entries = anchors.mapIndexedNotNull { index, anchor ->
            val interpretation = resolution.interpretations.getOrNull(index) ?: return@mapIndexedNotNull null
            val date = interpretation.resolved
                // An unresolved-ambiguous or impossible date must not become an entry. It
                // is a question, and the pipeline raises it from resolution.questions.
                ?: return@mapIndexedNotNull null
            entryFor(anchor, date, lines, fields, sourceOf)
        }

        if (entries.isEmpty()) {
            return ProseResult(kind, null, "found dates but couldn't tell what they refer to")
        }

        return ProseResult(
            kind = kind,
            group = ScheduleGroup(
                id = GroupId(label.ifBlank { "document" }),
                label = label,
                // De-duplicated on what the document actually distinguishes, not on the
                // title alone. An academic calendar's thirty rows all take their title from
                // the same heading, so keying on title collapsed the whole document into one
                // or two entries — the reason four real calendars produced almost nothing.
                // A title we read from the line itself still de-duplicates normally.
                entries = entries.distinctBy { entry ->
                    val fromHeading = entry.title.sourceOrNull is SourceReference.Derived
                    listOf(
                        if (fromHeading) entry.id.value else entry.title.valueOrNullSafe(),
                        entry.date.valueOrNullSafe(),
                        entry.startTime.valueOrNullSafe(),
                    )
                },
                source = sourceOf(anchors.first().line),
                kind = kind.toScheduleKind(),
            ),
        )
    }

    private fun entryFor(
        anchor: Anchor,
        date: LocalDate,
        lines: List<TextLine>,
        fields: List<LabelledFields.Field>,
        sourceOf: (TextLine) -> SourceReference,
    ): ScheduleEntry? {
        val line = anchor.line
        val source = sourceOf(line)

        val ranges = timeEngine.parse(line.text)
        val resolvedStarts = meridiem.resolve(ranges.map { it.start }, context = ranges.mapNotNull { it.end })
        val resolvedEnds = meridiem.resolve(ranges.mapNotNull { it.end }, context = ranges.map { it.start })

        var start = resolvedStarts.firstOrNull()
        val end = resolvedEnds.firstOrNull()
        var timeSource = source
        var timeReason: String? = null

        // A labelled time elsewhere on the page — "Reporting time: 09:30 AM" — belongs to
        // this date when the page states only one. More than one dated line means the
        // association is a guess, and a guessed time is worse than none.
        if (start == null && anchor.line.text.isNotBlank()) {
            val onlyDate = lines.count { candidate ->
                dateEngine.parse(candidate.text).any { it.readings.isNotEmpty() }
            } == 1
            val labelled = fields.firstOrNull { it.role == LabelledFields.Role.Time }
            if (onlyDate && labelled != null) {
                val parsed = timeEngine.parse(labelled.value)
                val resolved = meridiem.resolve(parsed.map { it.start }, context = parsed.mapNotNull { it.end })
                resolved.firstOrNull()?.let {
                    start = it
                    timeSource = sourceOf(labelled.line)
                    timeReason = "read from the \"${labelled.label}\" line"
                }
            }
        }

        val title = titleFor(anchor, lines, fields, sourceOf)
            ?: return null // nothing to call it — better nothing than a date with no subject

        return ScheduleEntry(
            id = EntryId("prose-${line.index}"),
            title = title,
            date = scorer.score(date, line.runs, source),
            startTime = start?.time
                ?.let { time ->
                    val scored = scorer.score(time, line.runs, timeSource)
                    when {
                        // Read from another line: true, but assembled by us rather than
                        // stated together, so it can never claim high confidence.
                        timeReason != null -> scored.capAtMedium(timeReason!!)
                        start!!.inferred -> scored.capAtMedium(start!!.reason ?: "meridiem inferred")
                        else -> scored
                    }
                }
                // A date with no time stays timeless. It becomes a reminder, never an event
                // that silently starts at nine in the morning.
                ?: Confident.Missing("no time given — this is an all-day item"),
            endTime = end?.time
                ?.let { scorer.score(it, line.runs, source) }
                ?: Confident.Missing("no end time given"),
        )
    }

    /**
     * The subject is what the line says once the date and time are taken out of it. When
     * that leaves nothing — a bare date in a calendar column — the nearest preceding line
     * with real words is used instead, and marked as derived so it cannot claim high
     * confidence.
     */
    private fun titleFor(
        anchor: Anchor,
        lines: List<TextLine>,
        fields: List<LabelledFields.Field>,
        sourceOf: (TextLine) -> SourceReference,
    ): Confident<String>? {
        val line = anchor.line

        // On a labelled line the label *is* the subject: "Last date for payment: 18/09/2026"
        // is an event called "Last date for payment", not one called "INR /2" — which is
        // what taking the leftover characters produced on a real electricity bill.
        fields.firstOrNull { it.line.index == line.index && it.role == LabelledFields.Role.When }
            ?.let { field ->
                // A document-level subject beats a field label when it names the thing
                // itself: a ticket's "Flight: AI 505" is better than "Journey date".
                val subject = labels.subjectOf(fields)?.value?.takeIf { namesSomething(it) }
                val name = subject?.let { "$it — ${field.label}" } ?: field.label
                if (namesSomething(name)) {
                    return scorer.score(name, line.runs, sourceOf(line))
                        .capAtMedium("read from the \"${field.label}\" label")
                }
            }

        // What comes *before* the date is the subject, in prose and in tables alike:
        // "Last date for submission of …" and "Infosys | 08/10/2026 | 08:30 | Auditorium"
        // both put the meaningful text first. Taking everything left over instead glues the
        // trailing columns on, producing titles like "Infosys Auditorium".
        val firstDate = dateEngine.parse(line.text).firstOrNull { it.readings.isNotEmpty() }
        val prefix = firstDate
            ?.let { Text.collapseWhitespace(line.text.take(it.range.first).trim(*TRIM_CHARS)) }
            .orEmpty()

        val trimmedPrefix = trimmedTitle(prefix)
        if (namesSomething(trimmedPrefix) &&
            !isColumnHeader(trimmedPrefix) &&
            !isPartOfTheDate(trimmedPrefix)
        ) {
            return scorer.score(trimmedPrefix, line.runs, sourceOf(line))
        }

        var remainder = line.text
        dateEngine.parse(line.text).forEach { remainder = remainder.replace(it.raw, " ") }
        timeEngine.parse(line.text).forEach { remainder = remainder.replace(it.raw, " ") }
        remainder = Text.collapseWhitespace(remainder.trim(*TRIM_CHARS))

        if (namesSomething(remainder) && !isColumnHeader(remainder)) {
            return scorer.score(remainder, line.runs, sourceOf(line))
        }

        val heading = lines
            .filter { it.index < line.index }
            .lastOrNull { candidate ->
                val text = Text.collapseWhitespace(candidate.text)
                // Held to the same standard as a title read from the line itself. It was not,
                // and that gap is where "Su 7142128" (a month grid's day-number row) and
                // "the online application form, the National Te…" came from — both admitted
                // by a fallback that only asked whether the line was long enough.
                namesSomething(text) &&
                    !isColumnHeader(text) &&
                    dateEngine.parse(text).none { it.readings.isNotEmpty() }
            }
            ?: return null

        return scorer.derived(
            value = Text.collapseWhitespace(heading.text),
            from = listOf(sourceOf(heading)),
            rule = "nearest-heading",
            reason = "taken from the heading above this date",
        )
    }

    /**
     * A table's column headings — `Date`, `Venue`, `Company` — are never the name of an
     * event. Without this, a row whose subject cell is empty borrows the header above it and
     * produces an entry called "Date".
     */
    private fun isColumnHeader(text: String): Boolean {
        val words = text.lowercase().split(' ', '\t').filter { it.isNotBlank() }
        return words.isNotEmpty() && words.all { it.trim(*TRIM_CHARS) in COLUMN_HEADERS }
    }

    /**
     * Does this text name a thing, or is it wreckage?
     *
     * Necessary the moment a bare date could become an event on its own. Without it an
     * electricity bill produced three rows — "INR /2", "3,845.00 026" and the one real
     * deadline — because any line containing a date became a candidate and the leftover
     * characters became its title. Three flagged rows of which two are gibberish is worse
     * than none: the user stops reading the flag, which is the only thing keeping the
     * inference honest.
     *
     * The test is deliberately about *letters*, not length. A currency amount, a consumer
     * number and a column of figures all fail it; "AFL", "Spin" and "Viva" all pass.
     */
    /**
     * Whether the text before the date is really part of the date itself.
     *
     * `Friday, August 21st, 2026` is one date written the ordinary way, but the date engine
     * reports the weekday and the calendar date as two separate matches. Taking "everything
     * before the first *readable* date" then hands back `"Friday"`, which passes every test
     * for naming something — and a fixture list became fifty all-day events titled Friday,
     * Saturday and Sunday. A weekday standing alone names the day, never the event.
     */
    private fun isPartOfTheDate(text: String): Boolean {
        val words = text.split(' ', ',').filter { it.isNotBlank() }
        return words.isNotEmpty() && words.all { word ->
            dateEngine.parse(word).any { it.weekday != null && it.readings.isEmpty() }
        }
    }

    private fun namesSomething(text: String): Boolean {
        if (text.length < MIN_TITLE_LENGTH) return false
        val letters = text.count { it.isLetter() }
        if (letters < MIN_TITLE_LETTERS) return false
        // A line that is mostly digits and punctuation is a figure, not a name.
        if (letters.toFloat() / text.count { !it.isWhitespace() } < MIN_LETTER_SHARE) return false

        // Past this length it is a sentence someone wrote, not a name they gave something.
        // "The primary objective of issuing this advisory was to support…" is a true
        // statement about a document and a useless thing to see in a calendar.
        if (text.length > MAX_TITLE_LENGTH) return false

        // Prose beginning with an article or a pronoun is narration. A real subject line —
        // "Mid-term viva", "Project demo day", "Last date for payment" — never does.
        val firstWord = text.substringBefore(' ').lowercase().trim(*TRIM_CHARS)
        if (firstWord in NARRATION_OPENERS) return false

        // Starting with a figure means the line was cut out of the middle of a sentence:
        // "28,000 is payable on or before the 5th of each month" is what is left of a rent
        // clause once its date is removed, and it names nothing.
        return text.first().isLetter()
    }

    /**
     * Removes the word that used to join the title to the date.
     *
     * "Mid-term viva **on** 25/09/2026" and "Assignment 3 deadline **is** 22/09/2026" leave a
     * dangling preposition once the date is cut away, and "Mid-term viva on" reads as if
     * something is missing — because it is. The date it pointed at is now the event's own.
     */
    private fun trimmedTitle(text: String): String {
        var trimmed = text.trim(*TRIM_CHARS).trim()
        while (true) {
            val last = trimmed.substringAfterLast(' ', "").lowercase().trim(*TRIM_CHARS)
            if (last.isEmpty() || last !in DANGLING_WORDS) return trimmed
            trimmed = trimmed.substringBeforeLast(' ').trim(*TRIM_CHARS).trim()
        }
    }

    /** Cue words say what *kind* of document this is. They never supply a value. */
    internal fun classify(text: String): DocumentKind {
        val lower = text.lowercase()
        return when {
            TRAVEL_CUES.any { it in lower } -> DocumentKind.Travel
            APPOINTMENT_CUES.any { it in lower } -> DocumentKind.Appointment
            DEADLINE_CUES.any { it in lower } -> DocumentKind.Deadline
            EVENT_CUES.any { it in lower } -> DocumentKind.Event
            else -> DocumentKind.Unknown
        }
    }

    private data class Anchor(val line: TextLine, val located: LocatedDate)

    private fun Confident<*>.valueOrNullSafe(): Any? = when (this) {
        is Confident.High -> value
        is Confident.Medium -> value
        is Confident.Low -> value
        is Confident.Missing -> null
    }

    private companion object {
        val TRIM_CHARS = charArrayOf(' ', ':', '-', '–', '—', ',', '.', '(', ')', '|', '\t')
        const val MIN_TITLE_LENGTH = 3

        /** "AFL" and "Viva" are real subjects; "/2" and "026" are not. */
        const val MIN_TITLE_LETTERS = 3

        /** Below this a line is a figure with some stray letters, not a name. */
        const val MIN_LETTER_SHARE = 0.5f

        /**
         * Longer than this and it is a sentence, not a subject.
         *
         * Tuned against the corpus rather than chosen: at sixty it still admitted
         * "identity document. The last date to report discrepancies…", which is a clause
         * someone wrote and not a name they gave something.
         */
        const val MAX_TITLE_LENGTH = 46

        /** Words left dangling when the date they introduced is removed. */
        val DANGLING_WORDS = setOf(
            "is", "on", "at", "by", "from", "to", "for", "of", "in", "the", "a", "an",
            "will", "be", "was", "are", "and", "upto", "till", "until", "before", "after",
        )

        val NARRATION_OPENERS = setOf(
            "the", "this", "these", "those", "please", "kindly", "it", "we", "you", "all",
            "any", "as", "in", "on", "for", "with", "submissions", "applicants", "candidates",
            "students", "note", "notice",
        )

        val DEADLINE_CUES = listOf(
            "last date", "due date", "due on", "deadline", "submit by", "submission",
            "valid till", "valid until", "expires", "premium", "payable", "outstanding",
            "last day", "closing date",
        )
        val TRAVEL_CUES = listOf(
            "departure", "departs", "boarding", "check-in", "check in", "arrival", "arrives",
            "pnr", "itinerary", "gate", "terminal", "coach", "seat no",
        )
        val APPOINTMENT_CUES = listOf(
            "appointment", "consultation", "interview", "hearing", "visit us", "reporting time",
            "admit card", "reporting at",
        )
        val EVENT_CUES = listOf("programme", "program", "agenda", "session", "conference", "fixture")

        /** Words that only ever label a column, never name an event. */
        val COLUMN_HEADERS = setOf(
            "date", "dates", "day", "time", "times", "venue", "location", "room", "hall",
            "company", "status", "subject", "course", "sl", "no", "sr", "s", "from", "to",
            "reporting", "arrival", "departure", "seat", "coach", "test", "round",
        )
    }
}
