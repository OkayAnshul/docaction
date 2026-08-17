package com.okayanshul.docaction.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/** Which component of a numeric date comes first. */
enum class DateOrder { DayFirst, MonthFirst }

/**
 * Why we believe a particular reading of an ambiguous date.
 *
 * Device locale is deliberately absent. It orders the options we present to the user;
 * it never resolves one. A device set to en-US processing an Indian college timetable
 * would otherwise produce confident dates that are months wrong, with no signal to the
 * user that anything happened. See docs/05-architecture.md ADR-004.
 */
sealed interface ResolutionEvidence {
    /** Another date in the same document had a component > 12, proving the order. */
    data class SiblingDate(val at: SourceReference, val order: DateOrder) : ResolutionEvidence

    /**
     * The document named a weekday and only one reading falls on it. [order] is set when
     * the agreement also proves the document's numeric convention, letting one confirmed
     * row resolve every other date in the document.
     */
    data class WeekdayAgreement(val weekday: DayOfWeek, val order: DateOrder? = null) : ResolutionEvidence

    /** The document declared its format, e.g. a "(DD/MM/YYYY)" note. */
    data class DocumentDeclaration(val at: SourceReference, val order: DateOrder) : ResolutionEvidence

    /** Only one reading produces a monotonic sequence across an ordered list of dates. */
    data class SequenceCoherence(val sampleSize: Int, val order: DateOrder) : ResolutionEvidence

    /** The user was asked once and the answer applies document-wide. */
    data class UserChoice(val order: DateOrder, val atEpochMillis: Long) : ResolutionEvidence

    /** The form was unambiguous to begin with (ISO, month name, or a component > 12). */
    data object Unambiguous : ResolutionEvidence
}

/**
 * A date as read from the document, with its ambiguity preserved rather than resolved.
 *
 * More than one candidate with a null [resolvedBy] means the pipeline must ask before
 * this can become an event — enforced in [CalendarEventCandidate.from].
 */
data class DateInterpretation(
    val raw: String,
    val candidates: List<LocalDate>,
    val resolvedBy: ResolutionEvidence?,
    val source: SourceReference,
) {
    val isAmbiguous: Boolean get() = candidates.size > 1 && resolvedBy == null
    val resolved: LocalDate? get() = if (candidates.size == 1 || resolvedBy != null) candidates.firstOrNull() else null
}

/**
 * A time or time range as read.
 *
 * [meridiemInferred] records that we decided AM/PM rather than reading it, which caps
 * confidence at Medium so the entry surfaces in review.
 */
data class TimeInterpretation(
    val raw: String,
    val start: LocalTime,
    val end: LocalTime?,
    val meridiemInferred: Boolean,
    val source: SourceReference,
)
