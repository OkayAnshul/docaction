package com.okayanshul.docaction.imports

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.okayanshul.docaction.document.pdf.PdfBoxTextSource
import com.okayanshul.docaction.document.pdf.PdfDocumentReader
import com.okayanshul.docaction.domain.BoundingBox
import com.okayanshul.docaction.domain.DocumentSource
import com.okayanshul.docaction.domain.ExtractionHints
import com.okayanshul.docaction.domain.Outcome
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What a crop actually selects, on a real page.
 *
 * Written because the rescue screen looked correct and produced nothing: the rectangle was
 * over the right rows and the result was empty. Reasoning about which coordinate space had
 * gone wrong was getting nowhere, so this measures it instead.
 */
@RunWith(AndroidJUnit4::class)
class CropPipelineTest {

    private val assets = InstrumentationRegistry.getInstrumentation().context.assets
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var file: File

    companion object {
        @JvmStatic
        @BeforeClass
        fun init() = PdfBoxTextSource.initialise(
            InstrumentationRegistry.getInstrumentation().targetContext,
        )
    }

    @Before
    fun setUp() {
        val directory = File(context.cacheDir, "crop-test").apply { deleteRecursively(); mkdirs() }
        file = File(directory, "dtu-central-tt.pdf").also { target ->
            assets.open("webcorpus/dtu-central-tt.pdf").use { input ->
                target.outputStream().use(input::copyTo)
            }
        }
    }

    private fun source() = DocumentSource(file.absolutePath, file.name, "application/pdf", file.length())

    private fun read(hints: ExtractionHints) = runBlocking {
        val reader = PdfDocumentReader(fileFor = { file })
        when (val outcome = reader.read(source(), hints) {}) {
            is Outcome.Success -> outcome.value
            is Outcome.Partial -> outcome.value
            is Outcome.Failure -> error("could not read: ${outcome.reason}")
        }
    }

    @Test
    fun theWholePageIsTheBaseline() {
        val page = read(ExtractionHints(pageSelection = listOf(0))).pages.single()
        assertThat(page.runs).isNotEmpty()
        assertThat(page.widthPt).isGreaterThan(0f)
        assertThat(page.heightPt).isGreaterThan(0f)
    }

    @Test
    fun theTopOfThePageIsTheTopOfThePage() {
        val whole = read(ExtractionHints(pageSelection = listOf(0))).pages.single()
        val topThird = read(
            ExtractionHints(pageSelection = listOf(0), cropRegion = BoundingBox(0f, 0f, 1f, 0.34f)),
        ).pages.single()

        // Whatever the reader's own units, a crop of the top third must select the runs
        // nearest the top — not the bottom, which is what a y-axis disagreement looks like.
        val topByCoordinate = whole.runs.sortedBy { it.bounds.top }.take(topThird.runs.size)
        assertThat(topThird.runs.map { it.text }.toSet())
            .containsExactlyElementsIn(topByCoordinate.map { it.text }.toSet())
    }

    @Test
    fun aCropSelectsFewerRunsThanTheWholePage() {
        val whole = read(ExtractionHints(pageSelection = listOf(0))).pages.single()
        val part = read(
            ExtractionHints(pageSelection = listOf(0), cropRegion = BoundingBox(0f, 0f, 1f, 0.25f)),
        ).pages.single()

        assertThat(part.runs.size).isGreaterThan(0)
        assertThat(part.runs.size).isLessThan(whole.runs.size)
    }

    // Deliberately not asserted here: that a cropped region still becomes calendar events.
    // It often does not, and that is the engine behaving as designed — the timetable guards
    // want several weekdays and an intact header row before they will call something a
    // weekly schedule, and a hand-drawn box frequently cuts one of those away. Rescue mode's
    // job is to narrow what is read, which is what the tests above pin down; when the result
    // is not a plausible week the user is told so specifically, which FailureScreenTest
    // covers. Asserting a green end-to-end path here would mean either loosening those
    // guards or picking a document that flatters the feature.
}
