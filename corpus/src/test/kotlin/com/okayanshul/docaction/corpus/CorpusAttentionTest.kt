package com.okayanshul.docaction.corpus

import com.google.common.truth.Truth.assertThat
import com.okayanshul.docaction.domain.AssumedAnswer
import com.okayanshul.docaction.domain.AssumptionReview
import com.okayanshul.docaction.domain.CandidateStatus
import com.okayanshul.docaction.domain.PipelineResult
import org.junit.Test

/**
 * How much of the review screen is shouting, measured across all 151 documents.
 *
 * The number this pins is a **design** property, not an engine one. A flag on a review row is
 * only worth anything if it is rare; at 68% of rows it is wallpaper, and a user who learns to
 * scroll past the amber ones is a user who will scroll past the one that mattered. That
 * failure would never show up in a golden — every individual row is correct — which is
 * exactly why it needs its own measurement.
 *
 * If a future change makes the engine assume more, this test says so in one number rather
 * than in 151 diffs.
 */
class CorpusAttentionTest {

    private fun everyCandidate() = CorpusRunner.documents()
        .map { CorpusRunner.run(it) }
        .filterIsInstance<PipelineResult.Ready>()
        .flatMap { it.review.candidates }

    @Test
    fun theFlagIsRareOnceTheBulkQuestionsAreAnswered() {
        val before = everyCandidate()
        val flaggedBefore = before.count { it.status == CandidateStatus.NeedsAttention }

        // Every rule the corpus produces, accepted — which is what a user does when they read
        // "41 dates have no time" and tap "add them as all-day events".
        val after = AssumptionReview.questionsFor(before)
            .fold(before) { candidates, question ->
                AssumptionReview.apply(candidates, question.rule, AssumedAnswer.Accept, atEpochMillis = 0)
            }
        val flaggedAfter = after.count { it.status == CandidateStatus.NeedsAttention }

        println(
            "attention: $flaggedBefore of ${before.size} rows flagged before " +
                "(${percent(flaggedBefore, before.size)}%), " +
                "$flaggedAfter after (${percent(flaggedAfter, after.size)}%)",
        )

        // The state that made this worth building: two rows in three asking for attention.
        assertThat(flaggedBefore).isGreaterThan(before.size / 2)

        // And the state after: rare enough that a flag is worth reading. Not asserted as zero
        // — a document that genuinely reads a value badly *should* still flag it, and pinning
        // zero would make the honest case fail.
        assertThat(flaggedAfter).isAtMost(before.size / 20)
    }

    @Test
    fun twoQuestionsCoverTheWholeCorpus() {
        val questions = AssumptionReview.questionsFor(everyCandidate())

        // The entire justification for batching. If this ever grows past a handful, asking
        // them one at a time stops being kind and the design needs revisiting.
        assertThat(questions.map { it.rule })
            .containsExactly("all-day-from-date-only", "assumed-duration")

        // Largest first, so the question that clears the most rows is the one asked first.
        assertThat(questions.map { it.affected })
            .isInOrder(compareByDescending<Int> { it })
    }

    @Test
    fun everyQuestionCanShowTheUserWhatItIsAbout() {
        // A question about 175 invisible rows is not answerable. Each one carries real titles.
        AssumptionReview.questionsFor(everyCandidate()).forEach { question ->
            assertThat(question.examples).isNotEmpty()
            assertThat(question.examples.filter { it.isBlank() }).isEmpty()
        }
    }

    private fun percent(part: Int, whole: Int) = if (whole == 0) 0 else part * 100 / whole
}
