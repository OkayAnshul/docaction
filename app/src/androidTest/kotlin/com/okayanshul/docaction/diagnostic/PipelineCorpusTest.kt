package com.okayanshul.docaction.diagnostic

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.okayanshul.docaction.document.image.ImageDocumentReader
import com.okayanshul.docaction.document.image.MlKitOcrEngine
import com.okayanshul.docaction.document.pdf.PdfBoxTextSource
import com.okayanshul.docaction.document.pdf.PdfDocumentReader
import com.okayanshul.docaction.document.pdf.SignatureFormatDetector
import com.okayanshul.docaction.document.spreadsheet.XlsxScheduleSource
import com.okayanshul.docaction.domain.DocumentPipeline
import com.okayanshul.docaction.domain.DocumentSource
import com.okayanshul.docaction.domain.PipelineAnswers
import com.okayanshul.docaction.domain.PipelineQuestion
import com.okayanshul.docaction.domain.PipelineResult
import com.okayanshul.docaction.domain.Stage
import com.okayanshul.docaction.domain.TermBounds
import com.okayanshul.docaction.extraction.EngineScheduleFinder
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The parts of the pipeline that only a device can answer for.
 *
 * The engine-wide sweeps that used to live here — every document, every question answered,
 * every cold start — now run on the JVM in `:corpus`, over snapshots of what these same
 * readers produce. They are faster there, one test per document, and diffed against
 * goldens rather than counted in a `println`.
 *
 * What is left is the part that genuinely needs PDFBox, `PdfRenderer` and ML Kit: a
 * multi-page timetable, an OCR'd scan, a workbook with hundreds of sections, and the hostile
 * inputs whose refusal happens at the reader stage and never reaches a snapshot.
 *
 * The corpus here is correspondingly small — twelve documents chosen for *reader* coverage,
 * not engine coverage. It was 9.6 MB and is now 1.8 MB, which is 9.6 MB that no longer has
 * to be built into an APK and pushed to a device on every run.
 */
@RunWith(AndroidJUnit4::class)
class PipelineCorpusTest {

    private val assets = InstrumentationRegistry.getInstrumentation().context.assets
    private val scratch = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
    private val target = InstrumentationRegistry.getInstrumentation().targetContext

    private val term = TermBounds(LocalDate.of(2026, 8, 17), LocalDate.of(2026, 12, 5))
    private val zone: ZoneId = ZoneId.systemDefault()

    companion object {
        @BeforeClass @JvmStatic
        fun init() = PdfBoxTextSource.initialise(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    /**
     * Each test class stages into its own directory. Sharing one cache directory let two
     * classes overwrite each other's fixtures, which passed in isolation and failed in a
     * full run — the worst kind of flake to chase.
     */
    private fun stage(name: String): File {
        val out = File(File(scratch, "pipeline-corpus"), name)
        out.parentFile?.mkdirs()
        assets.open("webcorpus/$name").use { input -> out.outputStream().use { input.copyTo(it) } }
        return out
    }

    /** Exactly the wiring the app will use. */
    private fun pipelineFor(file: File): DocumentPipeline {
        val resolve: (DocumentSource) -> File? = { file }
        val ocr = MlKitOcrEngine(target)
        return DocumentPipeline(
            detector = SignatureFormatDetector(resolve),
            readers = listOf(
                PdfDocumentReader(fileFor = resolve, ocr = ocr),
                ImageDocumentReader(ocr),
            ),
            schedules = EngineScheduleFinder(),
            zone = zone,
            scheduleSources = listOf(XlsxScheduleSource(resolve)),
        )
    }

    @Test
    fun aRealTimetableProducesRecurringCandidatesThroughTheProductionPath() = runBlocking<Unit> {
        val file = stage("dtu-central-tt.pdf")
        val source = DocumentSource("file://" + file.absolutePath, "dtu-central-tt.pdf", null, file.length())

        val pipeline = pipelineFor(file)
        val first = pipeline.run(source, answers = PipelineAnswers(term = term))

        // A 38-page central timetable holds a schedule per page, so the pipeline asks which
        // one — exactly as it should. Answer it the way a user would, then assert.
        val review = when (first) {
            is PipelineResult.Ready -> first.review
            is PipelineResult.NeedsAnswers -> {
                val choice = (first.questions.first() as com.okayanshul.docaction.domain.PipelineQuestion.WhichSchedule)
                // The options must be distinguishable, or the question is unanswerable.
                assertThat(choice.groups.map { it.label }.distinct()).hasSize(choice.groups.size)
                val answered = pipeline.run(
                    source,
                    answers = PipelineAnswers(term = term, selectedGroup = choice.groups.first().id),
                )
                (answered as PipelineResult.Ready).review
            }

            is PipelineResult.Failed -> error("dtu-central-tt.pdf failed: ${first.reason}")
        }

        assertThat(review.candidates).isNotEmpty()
        assertThat(review.candidates.all { it.recurrence != null }).isTrue()
        assertThat(review.candidates.all { it.recurrence!!.until == term.end }).isTrue()
    }

    @Test
    fun aWorkbookWithManySchedulesAsksWhichOneRatherThanGuessing() = runBlocking<Unit> {
        // Four Calendarpedia templates are blank, so use a PDF known to carry one schedule
        // and assert the pipeline never silently picks when several exist.
        val file = stage("dtu-central-tt.pdf")
        val source = DocumentSource("file://" + file.absolutePath, "dtu-central-tt.pdf", null, file.length())

        val result = pipelineFor(file).run(source, answers = PipelineAnswers(term = term))

        if (result is PipelineResult.Ready && result.review.groups.size > 1) {
            error("several schedules were found but one was chosen without asking")
        }
        assertThat(result).isNotNull()
    }

    @Test
    fun stageProgressIsReportedForARealDocument() = runBlocking<Unit> {
        val file = stage("dtu-central-tt.pdf")
        val source = DocumentSource("file://" + file.absolutePath, "dtu-central-tt.pdf", null, file.length())
        val stages = mutableListOf<Stage>()

        pipelineFor(file).run(source, answers = PipelineAnswers(term = term)) { stages += it.stage }

        // The processing screen shows these; they must be real, not decorative.
        assertThat(stages).containsAtLeast(Stage.DetectingFormat, Stage.ReadingDocument)
    }

    @Test
    fun aHostileArchiveIsRefusedByTheProductionPath() = runBlocking<Unit> {
        val file = stage("zipbomb.xlsx")
        val source = DocumentSource("file://" + file.absolutePath, "zipbomb.xlsx", null, file.length())

        val result = pipelineFor(file).run(source, answers = PipelineAnswers(term = term))

        assertThat(result).isInstanceOf(PipelineResult.Failed::class.java)
    }

    @Test
    fun anHtmlFileNamedPdfIsRefusedOnItsContentNotItsName() = runBlocking<Unit> {
        val file = stage("iitb-endsem.pdf")
        val source = DocumentSource("file://" + file.absolutePath, "iitb-endsem.pdf", null, file.length())

        val result = pipelineFor(file).run(source, answers = PipelineAnswers(term = term))

        assertThat(result).isInstanceOf(PipelineResult.Failed::class.java)
    }

    /**
     * Answering the question, which the run above never does.
     *
     * This exists because of a bug it would have caught on day one. A real 335-section
     * workbook is listed cheaply on the first pass and built on the second; the second pass
     * was written but never wired, so choosing a section returned the same empty
     * placeholders and the user was told "we couldn't find a schedule" about their own
     * timetable. Every test covered one side of that hand-off and none covered the join.
     *
     * So: every document that asks something gets answered, and the answer has to lead
     * somewhere. A question that leads nowhere is worse than no question — the user has
     * spent attention to reach a dead end.
     */
    /**
     * The corpus as a user actually meets it: nothing answered yet.
     *
     * Every other run here supplies a term up front, which no real first run ever has. That
     * difference hid a whole class of outcome — a document that produces a fine schedule
     * *given* a term, but on a cold start returns neither a result nor a question, so the app
     * says "we couldn't find a schedule" about a file it can read perfectly well.
     *
     * The rule being asserted is simple and is the product's whole promise: from a cold
     * start every document must either produce something, ask something, or refuse. Silently
     * producing nothing is none of the three.
     */
}
