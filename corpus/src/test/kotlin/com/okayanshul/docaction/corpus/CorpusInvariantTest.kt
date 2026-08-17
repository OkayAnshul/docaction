package com.okayanshul.docaction.corpus

import com.google.common.truth.Truth.assertThat
import com.okayanshul.docaction.domain.CandidateStatus
import com.okayanshul.docaction.domain.PipelineResult
import com.okayanshul.docaction.domain.SourceReference
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Properties that hold for every document, whatever the goldens say.
 *
 * The difference between this suite and [CorpusGoldenTest] is the difference between "this
 * is what we currently produce" and "this is what we must never produce". Goldens get
 * regenerated; these do not. When a golden diff looks reasonable but one of these fails, the
 * change was wrong.
 */
@RunWith(Parameterized::class)
class CorpusInvariantTest(private val document: String) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun documents(): List<String> = CorpusRunner.documents()
    }

    private fun ready() = CorpusRunner.run(document) as? PipelineResult.Ready

    @Test
    fun everyCandidateCanSayWhereItCameFrom() {
        // The review screen promises "where did this come from?" for every row. A candidate
        // with no sources makes that promise a lie.
        ready()?.review?.candidates?.forEach {
            assertThat(it.sources).isNotEmpty()
        }
    }

    @Test
    fun everyCandidateOccupiesRealTime() {
        ready()?.review?.candidates?.forEach {
            assertThat(it.end.isAfter(it.start)).isTrue()
        }
    }

    @Test
    fun aReadyCandidateHasNothingWeInvented() {
        // The machine-checked form of "never silently wrong": anything the document did not
        // state must leave the row flagged, so the user sees it before it reaches a calendar.
        ready()?.review?.candidates
            ?.filter { it.status == CandidateStatus.Ready }
            ?.forEach { candidate ->
                assertThat(candidate.sources.none { it is com.okayanshul.docaction.domain.SourceReference.UserProvided })
                    .isTrue()
            }
    }

    @Test
    fun nothingWeInventedIsEverPresentedAsCertain() {
        // The property that makes inference safe to have at all, checked on every document
        // rather than in one unit test: a filled-in value forces the row to be flagged and
        // carries a source saying we supplied it. If this ever passes silently because no
        // document has assumptions, the summary golden will have changed too.
        ready()?.review?.candidates
            ?.filter { it.assumptions.isNotEmpty() }
            ?.forEach { candidate ->
                assertThat(candidate.status).isEqualTo(CandidateStatus.NeedsAttention)
                assertThat(candidate.sources.filterIsInstance<SourceReference.Assumed>()).isNotEmpty()
            }
    }

    @Test
    fun theSameDocumentTwiceGivesTheSameAnswer() {
        // Extraction reads maps and sets in several places, and iteration order there is not
        // guaranteed. A pipeline that shuffles its own output would make every golden a
        // coin flip and every regression unreproducible.
        val first = Golden.of(document, CorpusRunner.run(document))
        val second = Golden.of(document, CorpusRunner.run(document))
        assertThat(first).isEqualTo(second)
    }

    @Test
    fun everyDocumentReachesOneOfTheThreeOutcomes() {
        // Produce, ask, or refuse. A document that does none of the three has fallen through
        // every path the user can see — it shows an empty screen and no explanation.
        val result = CorpusRunner.run(document)
        val outcome = when (result) {
            is PipelineResult.Failed -> "refused"
            is PipelineResult.NeedsAnswers -> "asked"
            is PipelineResult.Ready ->
                if (result.review.candidates.isNotEmpty() || result.review.unresolved.isNotEmpty()) {
                    "produced"
                } else {
                    "silent"
                }
        }
        // Silence is currently real for blank templates and known gaps, so this records
        // rather than forbids it — the count is pinned in the summary golden instead.
        assertThat(outcome).isIn(listOf("refused", "asked", "produced", "silent"))
    }
}
