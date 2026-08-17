package com.okayanshul.docaction.imports

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.okayanshul.docaction.domain.BoundingBox
import com.okayanshul.docaction.domain.DocumentSource
import com.okayanshul.docaction.domain.ExtractionHints
import com.okayanshul.docaction.domain.GroupId
import com.okayanshul.docaction.domain.PipelineAnswers
import com.okayanshul.docaction.domain.TermBounds
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Picking up an import that process death interrupted.
 *
 * What has to survive is exactly the user's own contribution — the term they chose, the
 * schedule they picked, the region they pointed at. Losing any of it means asking the same
 * question twice, which is the specific insult this feature exists to avoid.
 */
@RunWith(AndroidJUnit4::class)
class ImportJournalTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val journal = ImportJournal(context)
    private val staging = DocumentStaging(context)
    private lateinit var staged: DocumentSource

    @Before
    fun setUp() = runBlocking {
        journal.forget()
        val file = File(context.filesDir, "timetable.pdf").apply { writeBytes("%PDF-1.4".toByteArray()) }
        staged = staging.stage(android.net.Uri.fromFile(file))!!
    }

    @After
    fun tearDown() = runBlocking {
        journal.forget()
        staging.clear()
    }

    private val answers = PipelineAnswers(
        term = TermBounds(LocalDate.of(2026, 8, 17), LocalDate.of(2026, 12, 5)),
        selectedGroup = GroupId("section-cs1"),
        assumedYear = 2026,
    )

    private val hints = ExtractionHints(
        pageSelection = listOf(3, 4),
        cropRegion = BoundingBox(0.1f, 0.2f, 0.8f, 0.6f),
    )

    @Test
    fun everythingTheUserToldUsSurvives() = runBlocking {
        journal.remember(staged, answers, hints)

        val resumed = journal.interrupted()!!
        assertThat(resumed.source.displayName).isEqualTo("timetable.pdf")
        assertThat(resumed.answers.term).isEqualTo(answers.term)
        assertThat(resumed.answers.selectedGroup).isEqualTo(answers.selectedGroup)
        assertThat(resumed.answers.assumedYear).isEqualTo(2026)
        assertThat(resumed.hints.pageSelection).containsExactly(3, 4).inOrder()
        assertThat(resumed.hints.cropRegion).isEqualTo(hints.cropRegion)
    }

    @Test
    fun anImportWithNoAnswersYetResumesWithNoAnswers() = runBlocking {
        journal.remember(staged, PipelineAnswers(), ExtractionHints())

        val resumed = journal.interrupted()!!
        // Not "restored as defaults": a term we never asked about must not come back as one
        // the user appears to have chosen.
        assertThat(resumed.answers.term).isNull()
        assertThat(resumed.answers.selectedGroup).isNull()
        assertThat(resumed.hints.pageSelection).isNull()
        assertThat(resumed.hints.cropRegion).isNull()
    }

    @Test
    fun answeringAgainReplacesTheEarlierAnswer() = runBlocking {
        journal.remember(staged, answers, hints)
        journal.remember(staged, PipelineAnswers(), ExtractionHints())

        // A stale term left behind by the previous write would resume the import into a
        // schedule the user had already changed their mind about.
        val resumed = journal.interrupted()!!
        assertThat(resumed.answers.term).isNull()
        assertThat(resumed.hints.cropRegion).isNull()
    }

    @Test
    fun aRecordWhoseDocumentIsGoneOffersNothing() = runBlocking {
        journal.remember(staged, answers, hints)
        File(staged.uri).delete()

        // An offer that fails when tapped is worse than no offer.
        assertThat(journal.interrupted()).isNull()
    }

    @Test
    fun finishingAnImportLeavesNothingToResume() = runBlocking {
        journal.remember(staged, answers, hints)
        journal.forget()

        assertThat(journal.interrupted()).isNull()
    }

    @Test
    fun theSweepSparesTheDocumentBehindAnOffer() = runBlocking {
        journal.remember(staged, answers, hints)
        val directory = File(staged.uri).parentFile!!
        // An interrupted import is easily older than the abandonment threshold by the time
        // the user comes back to it.
        val other = DocumentStaging(context)

        other.sweepAbandoned(
            now = System.currentTimeMillis() + 6 * 60 * 60 * 1000,
            keep = setOf(directory),
        )

        assertThat(File(staged.uri).exists()).isTrue()
        assertThat(journal.interrupted()).isNotNull()
    }

    @Test
    fun resumingAdoptsTheDirectoryRatherThanLeavingItBehind() = runBlocking {
        val resuming = DocumentStaging(context)
        resuming.adopt(File(staged.uri))

        // Clearing after a resumed import must take the adopted directory with it; otherwise
        // the document lingers until the sweep an hour later.
        resuming.clear()
        assertThat(File(staged.uri).exists()).isFalse()
    }
}
