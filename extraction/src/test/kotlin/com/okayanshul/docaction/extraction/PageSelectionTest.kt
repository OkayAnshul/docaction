package com.okayanshul.docaction.extraction

import com.google.common.truth.Truth.assertThat
import com.okayanshul.docaction.domain.BoundingBox
import com.okayanshul.docaction.domain.DocumentContent
import com.okayanshul.docaction.domain.DocumentFormat
import com.okayanshul.docaction.domain.PageContent
import com.okayanshul.docaction.domain.PipelineAnswers
import com.okayanshul.docaction.domain.TextOrigin
import com.okayanshul.docaction.domain.TextRun
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Which pages of a long document actually get read.
 *
 * A 38-page central timetable is a real document in the corpus, and the cap of twelve pages
 * used to be applied as `take(12)` — the *first* twelve. On a document that opens with a
 * cover, an index and a page of regulations, that reads the preamble and stops before any
 * timetable, silently, with nothing to tell the user that most of their document was never
 * looked at.
 *
 * The corpus does not currently contain a document whose only schedule sits past page twelve,
 * so this is proved here instead of there. Cheap counting of date-, time- and weekday-shaped
 * runs decides what is worth reading in full.
 */
class PageSelectionTest {

    private val finder = EngineScheduleFinder()

    private fun run(text: String, row: Int): TextRun = TextRun(
        text = text,
        bounds = BoundingBox(0f, row * 20f, 80f, row * 20f + 10f),
        confidence = null,
        origin = TextOrigin.PdfTextLayer,
    )

    /** A page of prose with nothing schedule-shaped on it. */
    private fun filler(index: Int) = PageContent(
        index = index,
        widthPt = 600f,
        heightPt = 800f,
        runs = listOf(
            run("Regulations governing the conduct of examinations", 0),
            run("Candidates are advised to read the following carefully", 1),
        ),
    )

    /** A page carrying an actual dated schedule. */
    private fun schedule(index: Int) = PageContent(
        index = index,
        widthPt = 600f,
        heightPt = 800f,
        runs = listOf(
            run("Event", 0), run("Date", 0), run("Time", 0),
            run("Orientation", 1), run("25/03/2026", 1), run("10:00", 1),
            run("Registration", 2), run("26/03/2026", 2), run("11:00", 2),
            run("Induction", 3), run("27/03/2026", 3), run("14:00", 3),
        ),
    )

    @Test
    fun `a schedule past the page cap is still found`() = runTest {
        // Twenty pages of preamble, then the timetable. Reading the first twelve finds
        // nothing at all.
        val pages = (0 until 20).map { filler(it) } + schedule(20)

        val found = finder.find(
            content = DocumentContent(format = DocumentFormat.Pdf, pages = pages),
            label = "prospectus.pdf",
            answers = PipelineAnswers(),
        )

        assertThat(found.groups).isNotEmpty()
        assertThat(found.groups.flatMap { it.entries }).isNotEmpty()
    }

    @Test
    fun `a short document is read whole and in order`() = runTest {
        val pages = listOf(filler(0), schedule(1), filler(2))

        val found = finder.find(
            content = DocumentContent(format = DocumentFormat.Pdf, pages = pages),
            label = "notice.pdf",
            answers = PipelineAnswers(),
        )

        assertThat(found.groups.flatMap { it.entries }).isNotEmpty()
    }
}
