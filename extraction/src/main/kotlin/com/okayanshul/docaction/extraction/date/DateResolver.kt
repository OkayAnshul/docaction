package com.okayanshul.docaction.extraction.date

import com.okayanshul.docaction.domain.DateInterpretation
import com.okayanshul.docaction.domain.DateOrder
import com.okayanshul.docaction.domain.ResolutionEvidence
import com.okayanshul.docaction.domain.SourceReference
import java.time.DayOfWeek
import java.time.LocalDate

/** A date match paired with where in the document it was found. */
data class LocatedDate(
    val match: DateMatch,
    val source: SourceReference,
    /** A weekday stated alongside this date, if the document gave one. */
    val statedWeekday: DayOfWeek? = null,
)

/** What the resolver could not work out on its own. The pipeline turns these into questions. */
sealed interface DateQuestion {
    /** Which way round are numeric dates written? Asked once, applied document-wide. */
    data class Order(val example: String, val dayFirst: LocalDate, val monthFirst: LocalDate) : DateQuestion

    /** The document never stated a year. */
    data class Year(val example: String) : DateQuestion
}

data class DateResolution(
    val interpretations: List<DateInterpretation>,
    val questions: List<DateQuestion>,
    val order: DateOrder?,
    val orderEvidence: ResolutionEvidence?,
)

/**
 * Resolves a document's numeric date order from evidence, and applies it to every date.
 *
 * Evidence is tried strongest-first. Device locale is not evidence and appears nowhere
 * in this class — it may order the options shown to the user, but it never picks one.
 * See docs/05-architecture.md ADR-004 and docs/08-extraction.md § Date engine.
 */
class DateResolver {

    fun resolve(
        dates: List<LocatedDate>,
        userOrder: DateOrder? = null,
        userOrderAtEpochMillis: Long = 0L,
        assumedYear: Int? = null,
    ): DateResolution {
        val evidence = userOrder?.let { ResolutionEvidence.UserChoice(it, userOrderAtEpochMillis) }
            ?: siblingEvidence(dates)
            ?: weekdayEvidence(dates)
            ?: sequenceEvidence(dates)

        val order = userOrder ?: evidence?.impliedOrder()
        val questions = mutableListOf<DateQuestion>()

        val interpretations = dates.map { located ->
            interpret(located, order, evidence, assumedYear, questions)
        }

        return DateResolution(interpretations, questions.distinct(), order, evidence)
    }

    private fun interpret(
        located: LocatedDate,
        order: DateOrder?,
        evidence: ResolutionEvidence?,
        assumedYear: Int?,
        questions: MutableList<DateQuestion>,
    ): DateInterpretation {
        val match = located.match

        // Impossible values keep their raw text and produce no candidates. They are never
        // coerced to a nearby valid date — the document has told us something is wrong.
        if (match.isInvalid || match.readings.isEmpty()) {
            return DateInterpretation(match.raw, emptyList(), null, located.source)
        }

        val yearMissing = match.readings.any { it.year == null }
        if (yearMissing && assumedYear == null) {
            questions += DateQuestion.Year(match.raw)
        }

        val candidates = match.readings.mapNotNull { it.toLocalDate(assumedYear) }
        if (candidates.isEmpty()) {
            return DateInterpretation(match.raw, emptyList(), null, located.source)
        }

        // Unambiguous to begin with.
        if (candidates.size == 1) {
            return DateInterpretation(
                raw = match.raw,
                candidates = candidates,
                resolvedBy = ResolutionEvidence.Unambiguous,
                source = located.source,
            )
        }

        // A weekday stated on this very row beats any document-wide convention.
        located.statedWeekday?.let { weekday ->
            val agreeing = candidates.filter { it.dayOfWeek == weekday }
            if (agreeing.size == 1) {
                return DateInterpretation(
                    raw = match.raw,
                    candidates = agreeing,
                    resolvedBy = ResolutionEvidence.WeekdayAgreement(weekday),
                    source = located.source,
                )
            }
        }

        if (order != null) {
            val chosen = when (order) {
                DateOrder.DayFirst -> candidates.first()
                DateOrder.MonthFirst -> candidates.last()
            }
            return DateInterpretation(match.raw, listOf(chosen), evidence, located.source)
        }

        // Genuinely ambiguous. Both readings are kept and resolvedBy stays null, which
        // blocks candidate creation until the user answers.
        questions += DateQuestion.Order(match.raw, candidates.first(), candidates.last())
        return DateInterpretation(match.raw, candidates, null, located.source)
    }

    /** Strongest evidence: some other date in the document had a component > 12. */
    private fun siblingEvidence(dates: List<LocatedDate>): ResolutionEvidence? {
        val proofs = dates.mapNotNull { located ->
            located.match.provenOrder?.let { it to located.source }
        }
        val orders = proofs.map { it.first }.distinct()
        // Contradictory proof means the document is inconsistent; trust neither.
        if (orders.size != 1) return null
        return ResolutionEvidence.SiblingDate(proofs.first().second, orders.single())
    }

    /**
     * A stated weekday that agrees with exactly one reading proves the order for the
     * whole document, not just that row.
     */
    private fun weekdayEvidence(dates: List<LocatedDate>): ResolutionEvidence? {
        val votes = dates.mapNotNull { located ->
            val weekday = located.statedWeekday ?: return@mapNotNull null
            val readings = located.match.readings.takeIf { it.size == 2 } ?: return@mapNotNull null
            val dayFirst = readings[0].toLocalDate()
            val monthFirst = readings[1].toLocalDate()
            when {
                dayFirst?.dayOfWeek == weekday && monthFirst?.dayOfWeek != weekday -> DateOrder.DayFirst
                monthFirst?.dayOfWeek == weekday && dayFirst?.dayOfWeek != weekday -> DateOrder.MonthFirst
                else -> null
            }
        }.distinct()

        if (votes.size != 1) return null
        val weekday = dates.firstNotNullOfOrNull { it.statedWeekday } ?: return null
        return ResolutionEvidence.WeekdayAgreement(weekday, votes.single())
    }

    /**
     * Weakest evidence, applied conservatively: with enough dates, only one reading
     * produces a strictly increasing sequence. Requires at least [MIN_SEQUENCE] dates
     * because a short list orders monotonically by coincidence too often.
     */
    private fun sequenceEvidence(dates: List<LocatedDate>): ResolutionEvidence? {
        val ambiguous = dates.filter { it.match.readings.size == 2 }
        if (ambiguous.size < MIN_SEQUENCE) return null

        val dayFirst = ambiguous.mapNotNull { it.match.readings[0].toLocalDate() }
        val monthFirst = ambiguous.mapNotNull { it.match.readings[1].toLocalDate() }
        if (dayFirst.size != ambiguous.size || monthFirst.size != ambiguous.size) return null

        val dayFirstOrdered = dayFirst.zipWithNext().all { (a, b) -> a < b }
        val monthFirstOrdered = monthFirst.zipWithNext().all { (a, b) -> a < b }

        return when {
            dayFirstOrdered && !monthFirstOrdered ->
                ResolutionEvidence.SequenceCoherence(ambiguous.size, DateOrder.DayFirst)

            monthFirstOrdered && !dayFirstOrdered ->
                ResolutionEvidence.SequenceCoherence(ambiguous.size, DateOrder.MonthFirst)

            else -> null
        }
    }

    private fun ResolutionEvidence.impliedOrder(): DateOrder? = when (this) {
        is ResolutionEvidence.SiblingDate -> order
        is ResolutionEvidence.DocumentDeclaration -> order
        is ResolutionEvidence.SequenceCoherence -> order
        is ResolutionEvidence.UserChoice -> order
        is ResolutionEvidence.WeekdayAgreement -> order
        ResolutionEvidence.Unambiguous -> null
    }

    private companion object {
        const val MIN_SEQUENCE = 4
    }
}
