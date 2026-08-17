package com.okayanshul.docaction.domain

import java.time.Duration
import java.time.LocalTime

/**
 * One question that settles many rows.
 *
 * [affected] is the count and [examples] are real titles from those rows, because a question
 * about 41 invisible things is unanswerable — someone needs to see what they are agreeing to
 * before they agree to it.
 */
data class AssumedQuestion(
    val rule: String,
    val field: Unresolved.Field,
    val affected: Int,
    val examples: List<String>,
)

/** What the user said about a whole group of assumed values. */
sealed interface AssumedAnswer {
    /** "That's fine." The rows settle, and the source still records that we filled them. */
    data object Accept : AssumedAnswer

    /** "They're all at this time." Only meaningful for a date with no clock time. */
    data class UseTime(val start: LocalTime, val duration: Duration) : AssumedAnswer

    /** "I'll look at them myself." Nothing changes; the rows stay flagged. */
    data object Individually : AssumedAnswer
}

/**
 * Turns a pile of identical flags into a couple of questions.
 *
 * Across the 151-document corpus, 252 of 369 candidates arrive as `NeedsAttention`, and every
 * one of them is flagged for one of exactly two reasons: a date with no time of day (175), or
 * a start time with no end (77). Both are real and both deserve saying. Neither deserves
 * saying 175 times.
 *
 * A review screen where two rows in three ask for attention has no signal left in it — the
 * user learns that the warning colour means nothing and stops reading it, which is precisely
 * the state the confidence system exists to avoid. The fix is not to hide the assumptions. It
 * is to notice that 175 rows are asking the *same question*, ask it once, and let the answer
 * apply to all of them. Anyone who wants to go row by row still can.
 *
 * The ordering rule matters: questions come **before** review, so the review screen a user
 * actually reads is one where a flag is rare and therefore worth looking at.
 */
object AssumptionReview {

    /**
     * The questions worth asking about this set, largest group first.
     *
     * Empty when nothing was assumed, which is the common case for a clean timetable and the
     * reason this step is usually invisible.
     */
    fun questionsFor(candidates: List<CalendarEventCandidate>): List<AssumedQuestion> =
        candidates
            .flatMap { candidate -> candidate.assumptions.map { it to candidate } }
            .groupBy { (assumption, _) -> assumption.rule }
            .map { (rule, pairs) ->
                val first = pairs.first().first
                AssumedQuestion(
                    rule = rule,
                    field = first.field,
                    affected = pairs.size,
                    // Distinct, because three rows called "Lecture" show the user nothing.
                    examples = pairs.map { (_, candidate) -> candidate.title }
                        .distinct()
                        .take(EXAMPLE_COUNT),
                )
            }
            .sortedByDescending { it.affected }

    /**
     * Applies one answer to every row that asked that question.
     *
     * Rows the answer does not concern are returned untouched and in place — the order of a
     * review list is the order of the document, and shuffling it would cost the user the
     * ability to check our work against the page.
     */
    fun apply(
        candidates: List<CalendarEventCandidate>,
        rule: String,
        answer: AssumedAnswer,
        atEpochMillis: Long = System.currentTimeMillis(),
    ): List<CalendarEventCandidate> = when (answer) {
        AssumedAnswer.Individually -> candidates

        AssumedAnswer.Accept -> candidates.map { candidate ->
            if (candidate.assumptions.any { it.rule == rule }) {
                candidate.acceptAssumptions(setOf(rule), atEpochMillis)
            } else {
                candidate
            }
        }

        is AssumedAnswer.UseTime -> candidates.map { candidate ->
            if (candidate.assumptions.any { it.rule == rule }) {
                // Null when the row cannot take a time after all — a recurring entry, or one
                // whose assumption was of a different kind. Keeping the original is the safe
                // answer; it simply stays flagged and the user sees it in review.
                candidate.withChosenTime(answer.start, answer.duration, atEpochMillis) ?: candidate
            } else {
                candidate
            }
        }
    }

    /** Enough to recognise the kind of thing, few enough to read at a glance. */
    private const val EXAMPLE_COUNT = 3
}
