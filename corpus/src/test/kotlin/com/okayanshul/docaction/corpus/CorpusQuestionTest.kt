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
 * A question has to lead somewhere for *some* answer.
 *
 * [CorpusAnswerTest] already proves an answer arrives and changes the outcome. It does not
 * prove the outcome is worth having, and the corpus showed why that matters: two dozen
 * documents offered a list of schedules to choose from and produced no events whichever one
 * was chosen. Asking someone to pick between eleven sections and then handing them nothing is
 * a worse failure than saying plainly that we found nothing — it spends their attention and
 * returns nothing for it.
 *
 * The rule is deliberately weak: **at least one** choice must produce. A court cause list
 * genuinely has sections that hold nothing schedulable, and demanding that every section
 * produce would be false. What must not happen is every door being locked.
 */
@RunWith(Parameterized::class)
class CorpusQuestionTest(private val document: String) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun documents(): List<String> = CorpusRunner.documents()
    }

    @Test
    fun someAnswerToWhichScheduleProducesEvents() {
        val first = CorpusRunner.run(document)
        val question = (first as? PipelineResult.NeedsAnswers)?.questions
            ?.filterIsInstance<PipelineQuestion.WhichSchedule>()
            ?.firstOrNull()
            ?: return

        val produced = question.groups.any { group ->
            val result = CorpusRunner.run(
                document,
                answers = PipelineAnswers(term = CorpusRunner.term, selectedGroup = group.id),
            )
            (result as? PipelineResult.Ready)?.review?.candidates?.isNotEmpty() == true
        }

        assertThat(produced).isTrue()
    }

    @Test
    fun someAnswerToDateOrderProducesEvents() {
        val first = CorpusRunner.run(document)
        val question = (first as? PipelineResult.NeedsAnswers)?.questions
            ?.filterIsInstance<PipelineQuestion.DateOrder>()
            ?.firstOrNull()
            ?: return

        val produced = listOf(DateOrder.DayFirst, DateOrder.MonthFirst).any { order ->
            val result = CorpusRunner.run(
                document,
                answers = PipelineAnswers(term = CorpusRunner.term, dateOrder = order),
            )
            (result as? PipelineResult.Ready)?.review?.candidates?.isNotEmpty() == true
        }

        assertThat(produced).isTrue()
    }
}
