package com.okayanshul.docaction.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * "Show us the part you need."
 *
 * The rescue path for a noticeboard photo where only one column is the user's, or a page
 * where the engine locked onto the wrong table. The rule that makes it safe is that nothing
 * is re-read: the surviving runs keep the coordinates they were read with, so a crop can
 * narrow what we look at without ever moving where we say it came from.
 */
class PageCropTest {

    private fun run(text: String, left: Float, top: Float) = TextRun(
        text = text,
        bounds = BoundingBox(left, top, left + 40f, top + 10f),
        confidence = null,
        origin = TextOrigin.PdfTextLayer,
    )

    private val page = PageContent(
        index = 0,
        widthPt = 400f,
        heightPt = 800f,
        runs = listOf(
            run("CS-1", left = 10f, top = 10f),
            run("CS-2", left = 200f, top = 10f),
            run("9:00", left = 10f, top = 400f),
            run("10:00", left = 200f, top = 400f),
        ),
    )

    @Test
    fun `a crop keeps only what falls inside it`() {
        // The left half of the page.
        val cropped = page.cropped(BoundingBox(0f, 0f, 0.5f, 1f))

        assertThat(cropped.runs.map { it.text }).containsExactly("CS-1", "9:00")
    }

    @Test
    fun `a crop never moves anything it keeps`() {
        val cropped = page.cropped(BoundingBox(0f, 0f, 0.5f, 1f))

        // Coordinates are what Source View points at. Rebasing them to the crop would make
        // every highlight wrong by the crop's offset.
        assertThat(cropped.runs.first().bounds).isEqualTo(page.runs.first().bounds)
        assertThat(cropped.widthPt).isEqualTo(400f)
        assertThat(cropped.heightPt).isEqualTo(800f)
    }

    @Test
    fun `a run that overhangs the region is kept when its middle is inside`() {
        // A heading wider than the column it heads is the common case, and a strict
        // containment test would silently drop exactly the row that names the schedule.
        val heading = run("Semester 3 Timetable", left = 90f, top = 0f)
        val withHeading = page.copy(runs = page.runs + heading)

        val cropped = withHeading.cropped(BoundingBox(0f, 0f, 0.35f, 1f))
        assertThat(cropped.runs.map { it.text }).contains("Semester 3 Timetable")
    }

    @Test
    fun `no crop changes nothing at all`() {
        assertThat(page.cropped(null)).isEqualTo(page)
    }

    @Test
    fun `a crop that selects nothing yields nothing rather than everything`() {
        // The tempting fallback — "empty selection means no filter" — would silently ignore
        // the user and hand back the result they had just rejected.
        val cropped = page.cropped(BoundingBox(0.9f, 0.9f, 0.95f, 0.95f))
        assertThat(cropped.runs).isEmpty()
    }
}
