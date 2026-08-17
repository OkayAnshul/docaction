package com.okayanshul.docaction.document.pdf

import com.okayanshul.docaction.domain.BoundingBox
import com.okayanshul.docaction.domain.TextOrigin
import com.okayanshul.docaction.domain.TextRun
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition

/**
 * Extracts text *with geometry* from a PDF page.
 *
 * [PDFTextStripper.getText] returns a plain string, which is useless for table
 * reconstruction — the whole point is that text order is not reading order. The positions
 * only become available by overriding the write hook, where PDFBox hands us the glyph
 * boxes it just laid out.
 *
 * The `DirAdj` accessors are already corrected for page rotation and use a top-left
 * origin, which is what the extraction engine expects and matters for landscape
 * timetables.
 */
internal class PositionedTextStripper : PDFTextStripper() {

    private val collected = mutableListOf<TextRun>()

    init {
        // We do our own geometric ordering downstream, but sorted input makes the
        // per-run grouping below produce tidier runs.
        sortByPosition = true
    }

    fun runsFor(document: PDDocument, pageIndex: Int): List<TextRun> {
        collected.clear()
        startPage = pageIndex + 1
        endPage = pageIndex + 1
        getText(document)
        return collected.toList()
    }

    override fun writeString(text: String, textPositions: List<TextPosition>) {
        if (textPositions.isEmpty()) return

        // PDFBox hands us a whole line at a time. Split it back into runs on wide gaps so
        // adjacent table columns don't merge into one run before the grid is built.
        var start = 0
        for (i in 1 until textPositions.size) {
            val previous = textPositions[i - 1]
            val current = textPositions[i]
            val gap = current.xDirAdj - (previous.xDirAdj + previous.widthDirAdj)
            if (gap > previous.widthOfSpace * GAP_IN_SPACES) {
                emit(textPositions.subList(start, i))
                start = i
            }
        }
        emit(textPositions.subList(start, textPositions.size))
    }

    private fun emit(positions: List<TextPosition>) {
        val text = positions.joinToString("") { it.unicode ?: "" }.trim()
        if (text.isEmpty()) return

        val left = positions.minOf { it.xDirAdj }
        val top = positions.minOf { it.yDirAdj - it.heightDir }
        val right = positions.maxOf { it.xDirAdj + it.widthDirAdj }
        val bottom = positions.maxOf { it.yDirAdj }

        collected += TextRun(
            text = text,
            bounds = BoundingBox(left, top, right, bottom),
            // A text layer is a read, not a recognition — there is no confidence to report,
            // and storing 1.0 here would conflate certainty with absence of measurement.
            confidence = null,
            origin = TextOrigin.PdfTextLayer,
        )
    }

    private companion object {
        /**
         * A gap wider than this many space-widths starts a new run.
         *
         * Two was too tight. Narrow table columns are routinely justified, which stretches
         * the spacing *inside* words far enough to cross that threshold — real documents
         * came back with cells reading `Tuesda y` and `08/10/ 2026`, and weekday detection
         * failed on them. Four still sits well below a column gutter, which is many space
         * widths across.
         */
        const val GAP_IN_SPACES = 4.0f
    }
}
