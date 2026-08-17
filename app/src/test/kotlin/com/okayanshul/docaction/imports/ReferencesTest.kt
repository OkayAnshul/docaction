package com.okayanshul.docaction.imports

import com.google.common.truth.Truth.assertThat
import com.okayanshul.docaction.domain.BoundingBox
import com.okayanshul.docaction.domain.SourceReference
import com.okayanshul.docaction.imports.source.References
import org.junit.Test

/**
 * Which of a candidate's references is worth showing.
 *
 * A candidate carries one reference per field, and they are rarely all in the same place —
 * a title read from a page heading, times read from a row four pages later. Pointing at
 * whichever came first would be technically true and useless, and "useless but technically
 * true" is precisely the failure mode Source View exists to rule out.
 */
class ReferencesTest {

    private fun span(page: Int, top: Float, bottom: Float) =
        SourceReference.PdfSpan(page, BoundingBox(0.1f, top, 0.9f, bottom))

    @Test
    fun `the page carrying most of the evidence wins`() {
        val sources = listOf(
            span(page = 0, top = 0.05f, bottom = 0.08f), // a heading, pages away
            span(page = 4, top = 0.30f, bottom = 0.34f),
            span(page = 4, top = 0.30f, bottom = 0.34f),
            span(page = 4, top = 0.35f, bottom = 0.39f),
        )

        val (page, box) = References.busiestPage(sources)!!
        assertThat(page).isEqualTo(4)
        // And the highlight covers everything read there, not just one field.
        assertThat(box!!.top).isWithin(1e-5f).of(0.30f)
        assertThat(box.bottom).isWithin(1e-5f).of(0.39f)
    }

    @Test
    fun `a derived reference is followed to the places it came from`() {
        val sources = listOf(
            SourceReference.Derived(
                from = listOf(span(2, 0.4f, 0.44f), span(2, 0.45f, 0.49f)),
                rule = "the next class in this column starts at 10:00",
            ),
        )

        val (page, box) = References.busiestPage(sources)!!
        assertThat(page).isEqualTo(2)
        assertThat(box!!.bottom).isWithin(1e-5f).of(0.49f)
    }

    @Test
    fun `a corrected value is reported as a correction, not as a place in the document`() {
        val edited = listOf(
            span(1, 0.2f, 0.24f),
            SourceReference.UserProvided("start", atEpochMillis = 1_700_000_000_000),
        )

        assertThat(References.corrections(edited).map { it.field }).containsExactly("start")
        // The user's edit is never offered as somewhere to look in the document.
        assertThat(References.placeable(edited)).doesNotContain(edited[1])
    }

    @Test
    fun `the most recent correction is the one worth reporting`() {
        val corrections = References.corrections(
            listOf(
                SourceReference.UserProvided("start", 1_000),
                SourceReference.UserProvided("title", 9_000),
            ),
        )
        assertThat(corrections.first().field).isEqualTo("title")
    }

    @Test
    fun `a photo groups under no page at all`() {
        val sources = listOf(
            SourceReference.ImageRegion(BoundingBox(0.1f, 0.2f, 0.9f, 0.3f)),
            SourceReference.ImageRegion(BoundingBox(0.1f, 0.3f, 0.9f, 0.4f)),
        )

        val (page, box) = References.busiestPage(sources)!!
        assertThat(page).isNull()
        assertThat(box!!.top).isWithin(1e-5f).of(0.2f)
    }

    @Test
    fun `a candidate with nowhere to point reports nothing rather than page zero`() {
        assertThat(References.busiestPage(emptyList())).isNull()
        assertThat(
            References.busiestPage(listOf(SourceReference.UserProvided("title", 1))),
        ).isNull()
    }

    @Test
    fun `a spreadsheet range points at the first cell of the range`() {
        val cell = SourceReference.SheetCell("CS1", row = 11, column = 2)
        val range = SourceReference.SheetRange("CS1", cell, SourceReference.SheetCell("CS1", 11, 4))

        assertThat(References.sheetCell(listOf(range))).isEqualTo(cell)
    }

    @Test
    fun `a column is named the way a spreadsheet names it`() {
        // Bijective base-26. Column 26 is AA — not Z, and not the character after Z.
        assertThat(References.columnName(0)).isEqualTo("A")
        assertThat(References.columnName(25)).isEqualTo("Z")
        assertThat(References.columnName(26)).isEqualTo("AA")
        assertThat(References.columnName(27)).isEqualTo("AB")
        assertThat(References.columnName(51)).isEqualTo("AZ")
        assertThat(References.columnName(52)).isEqualTo("BA")
        assertThat(References.columnName(701)).isEqualTo("ZZ")
        assertThat(References.columnName(702)).isEqualTo("AAA")
        // Excel's last column, and the one most likely to expose an off-by-one.
        assertThat(References.columnName(16_383)).isEqualTo("XFD")
    }
}
