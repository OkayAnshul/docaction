package com.okayanshul.docaction.corpus

import com.google.common.truth.Truth.assertThat
import com.okayanshul.docaction.domain.DateOrder
import com.okayanshul.docaction.domain.PipelineAnswers
import com.okayanshul.docaction.domain.PipelineQuestion
import com.okayanshul.docaction.domain.PipelineResult
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * A question the user answers has to lead somewhere.
 *
 * The rule this enforces is not "every question is answerable" — a court cause list has
 * several sections and picking the first may genuinely yield nothing schedulable. It is that
 * answering *works*: the answer reaches the engine and changes the outcome. That was broken
 * twice, in different places, and neither break was visible without this.
 *
 * The first was a workbook whose 335 sections were listed cheaply and never built, so
 * choosing one returned the same empty placeholders. The second was subtler: the resolver
 * detected an ambiguous date, produced the question, and nothing carried it to the pipeline
 * — so a train ticket produced nothing and said nothing about why.
 */
@RunWith(Parameterized::class)
class CorpusAnswerTest(private val document: String) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun documents(): List<String> = CorpusRunner.documents()
    }

    @Test
    fun answeringChangesTheOutcome() {
        val first = CorpusRunner.run(document)
        if (first !is PipelineResult.NeedsAnswers) return

        val answers = first.questions.fold(PipelineAnswers(term = CorpusRunner.term)) { acc, q ->
            when (q) {
                is PipelineQuestion.WhichSchedule -> acc.copy(selectedGroup = q.groups.first().id)
                is PipelineQuestion.DateOrder -> acc.copy(dateOrder = DateOrder.DayFirst)
                is PipelineQuestion.TermEnd -> acc
            }
        }

        val second = CorpusRunner.run(document, answers = answers)

        // Asking the same thing again means the answer never arrived — which is exactly what
        // both bugs looked like from the outside.
        assertThat(second).isNotInstanceOf(PipelineResult.NeedsAnswers::class.java)
    }

    @Test
    fun answeringAnAmbiguousDateProducesTheEventItWasBlocking() {
        val first = CorpusRunner.run(document)
        val order = (first as? PipelineResult.NeedsAnswers)?.questions
            ?.filterIsInstance<PipelineQuestion.DateOrder>()
            ?.firstOrNull()
            ?: return

        // Both readings are real dates, so both must work — and must differ. A question
        // whose answers produce the same result was not worth asking.
        val readings = listOf(DateOrder.DayFirst, DateOrder.MonthFirst).map { choice ->
            val result = CorpusRunner.run(
                document,
                answers = PipelineAnswers(term = CorpusRunner.term, dateOrder = choice),
            )
            (result as PipelineResult.Ready).review.candidates
        }

        readings.forEach { assertThat(it).isNotEmpty() }
        assertThat(readings[0].map { it.start }).isNotEqualTo(readings[1].map { it.start })
        assertThat(order.dayFirst).isNotEqualTo(order.monthFirst)
    }
}
