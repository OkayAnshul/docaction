package com.okayanshul.docaction.extraction.time

import java.time.LocalTime

/**
 * A time after meridiem resolution.
 *
 * [time] is null when nothing in the document could settle whether `10:00` means morning
 * or evening. That is a question for the user, not a coin flip — "classes are usually in
 * the morning" is not evidence.
 */
data class ResolvedTime(
    val token: TimeToken,
    val time: LocalTime?,
    val inferred: Boolean,
    val reason: String?,
)

/**
 * Decides AM/PM for a column of times using only evidence from the document.
 *
 * Permitted evidence, in order:
 *  1. an explicit marker on the value itself
 *  2. a value above 12, or a zero-padded hour, somewhere in the column — either proves
 *     24-hour notation
 *  3. exactly one way of assigning meridiems that makes the column strictly increasing
 *
 * Rule 3 is what lets a normal academic column — `8, 9, 10, 11, 12, 1, 2, 3` — resolve
 * cleanly, which is why a typical timetable reaches "40 ready, 2 need review" rather than
 * flagging every row. Where more than one assignment works (`8, 9, 10, 11` reads equally
 * well as morning or evening), nothing is inferred.
 *
 * See docs/08-extraction.md § The AM/PM rule.
 */
class MeridiemResolver {

    /**
     * @param context other times from the same column that constrain the notation without
     *   being part of this sequence — typically the end times when resolving starts. A
     *   start and end written in one cell are the same clock, so `09:00–10:00` lets the
     *   padded start settle the unpadded end.
     */
    fun resolve(column: List<TimeToken>, context: List<TimeToken> = emptyList()): List<ResolvedTime> {
        if (column.isEmpty()) return emptyList()

        // 1. Values that state their own meridiem, or are already unambiguous.
        if (column.all { it.asWritten() != null }) {
            return column.map { ResolvedTime(it, it.asWritten(), inferred = false, reason = null) }
        }

        // 2. A value above 12, or a zero-padded hour, proves 24-hour notation.
        if ((column + context).any { it.proves24Hour }) {
            return column.map { token ->
                val written = token.asWritten()
                if (written != null) {
                    ResolvedTime(token, written, inferred = false, reason = null)
                } else {
                    ResolvedTime(
                        token = token,
                        // Literal, not `under(pm = false)`: this column has proved it is
                        // 24-hour, so 12:00 is noon and not midnight.
                        time = token.literal(),
                        inferred = true,
                        reason = "this column uses 24-hour times",
                    )
                }
            }
        }

        // 3. Exactly one assignment makes the column strictly increasing.
        val solved = solveByMonotonicity(column)
        if (solved != null) {
            return column.mapIndexed { index, token ->
                val written = token.asWritten()
                if (written != null) {
                    ResolvedTime(token, written, inferred = false, reason = null)
                } else {
                    ResolvedTime(
                        token = token,
                        time = solved[index],
                        inferred = true,
                        reason = "read from the order of times in this column",
                    )
                }
            }
        }

        // Nothing resolved it. The value stands, unresolved, and becomes a question.
        return column.map { token ->
            val written = token.asWritten()
            ResolvedTime(
                token = token,
                time = written,
                inferred = false,
                reason = if (written == null) "couldn't tell if this is morning or evening" else null,
            )
        }
    }

    /**
     * Tries every single-transition AM→PM split. Returns the assignment only when exactly
     * one produces a strictly increasing column; several working splits means the column
     * genuinely doesn't say, and inferring one would be a guess.
     */
    private fun solveByMonotonicity(column: List<TimeToken>): List<LocalTime>? {
        if (column.size < MIN_COLUMN) return null
        if (column.any { !it.isValid }) return null

        val solutions = mutableListOf<List<LocalTime>>()

        for (split in 0..column.size) {
            val assignment = column.mapIndexed { index, token ->
                // A stated meridiem is fixed; only unstated values follow the split.
                when (token.meridiem) {
                    Meridiem.Am, Meridiem.Pm -> token.asWritten()
                    Meridiem.Unstated -> token.under(pm = index >= split)
                }
            }
            if (assignment.any { it == null }) continue

            @Suppress("UNCHECKED_CAST")
            val times = assignment as List<LocalTime>
            if (times.zipWithNext().all { (a, b) -> a < b }) {
                if (solutions.none { it == times }) solutions += times
            }
        }

        return solutions.singleOrNull()
    }

    private companion object {
        /** Below this, monotonicity is coincidence rather than evidence. */
        const val MIN_COLUMN = 3
    }
}
