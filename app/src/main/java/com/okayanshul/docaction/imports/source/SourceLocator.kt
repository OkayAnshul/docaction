package com.okayanshul.docaction.imports.source

import com.okayanshul.docaction.document.spreadsheet.XlsxReader
import com.okayanshul.docaction.domain.CalendarEventCandidate
import com.okayanshul.docaction.domain.DocumentFormat
import com.okayanshul.docaction.domain.SourceReference
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Answers "where did this come from?" by going back to the document.
 *
 * Re-reading rather than caching page images is the right trade here: a rendered page costs
 * megabytes, the question is asked rarely, and the staged file is already on disk precisely
 * so it can be read again. The alternative — holding every page of a 38-page timetable in
 * memory on the chance someone taps a row — is how a document app gets killed in the
 * background.
 *
 * Nothing here can fail loudly. A document that has since been deleted, a page that will not
 * render, a workbook that no longer parses: each produces an [SourceEvidence.Unavailable]
 * saying so, because a crash while explaining yourself is a particularly bad crash.
 */
class SourceLocator(
    private val file: File?,
    private val format: DocumentFormat,
    private val openStream: () -> java.io.InputStream,
    private val images: DocumentImages = DocumentImages(file, format, openStream),
) {

    suspend fun locate(candidate: CalendarEventCandidate, widthPx: Int): SourceEvidence {
        val corrections = References.corrections(candidate.sources)
        val places = References.placeable(candidate.sources)

        val assumed = References.assumed(candidate.sources)

        return when {
            // A value the user typed has no place in the document, and saying "page 3"
            // for it would be a lie about where the event's content came from.
            places.isEmpty() && corrections.isNotEmpty() -> told(corrections.first())
            places.isEmpty() && assumed.isNotEmpty() -> SourceEvidence.Told(
                label = "We filled this in",
                detail = "The document didn't say. Tap Edit to set it yourself.",
            )
            file == null || !file.exists() ->
                SourceEvidence.Unavailable("This document isn't available any more.")

            format == DocumentFormat.Xlsx -> withContext(Dispatchers.IO) { sheetEvidence(candidate) }
            format == DocumentFormat.Pdf -> pdfEvidence(candidate, widthPx)
            format == DocumentFormat.Image -> imageEvidence(candidate)
            else -> SourceEvidence.Unavailable("We can't show you inside this kind of file.")
        }
    }

    private fun told(correction: SourceReference.UserProvided) = SourceEvidence.Told(
        label = "You set this",
        detail = "You changed the ${correction.field} on " +
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(Date(correction.atEpochMillis)) +
            ". It's no longer what the document says.",
    )

    private suspend fun pdfEvidence(candidate: CalendarEventCandidate, widthPx: Int): SourceEvidence {
        val (page, box) = References.busiestPage(candidate.sources)
            ?: return SourceEvidence.Unavailable("We didn't record where this came from.")

        val index = page ?: 0
        val bitmap = images.render(index, widthPx)
            ?: return SourceEvidence.Unavailable("This page wouldn't open.")

        return SourceEvidence.Page(
            // One-based, because "page 0" is not a thing outside a program.
            label = "Page ${index + 1}",
            image = bitmap,
            highlight = box,
        )
    }

    private suspend fun imageEvidence(candidate: CalendarEventCandidate): SourceEvidence {
        val box = References.busiestPage(candidate.sources)?.second
        val bitmap = images.render(index = 0, widthPx = 0)
            ?: return SourceEvidence.Unavailable("We couldn't open this image again.")
        return SourceEvidence.Page("In your photo", bitmap, box)
    }

    /**
     * A spreadsheet shows its own grid.
     *
     * There is no picture of a workbook, and inventing one would be a rendering exercise
     * with no relationship to what the user sees when they open the file. A window of cells
     * around the one we read is the honest equivalent.
     */
    private fun sheetEvidence(candidate: CalendarEventCandidate): SourceEvidence {
        val cell = References.sheetCell(candidate.sources)
            ?: return SourceEvidence.Unavailable("We didn't record which cell this came from.")

        val workbook = runCatching { XlsxReader().read(file!!) }.getOrNull()
            ?: return SourceEvidence.Unavailable("This workbook wouldn't open again.")

        val sheet = workbook.sheets.firstOrNull { it.name == cell.sheet }
            ?: return SourceEvidence.Unavailable("The sheet \"${cell.sheet}\" isn't in this file.")

        val firstRow = (cell.row - CONTEXT).coerceAtLeast(0)
        val lastRow = (cell.row + CONTEXT).coerceAtMost(sheet.rowCount - 1)
        val firstColumn = (cell.column - CONTEXT).coerceAtLeast(0)
        val lastColumn = (cell.column + CONTEXT).coerceAtMost(sheet.columnCount - 1)
        if (lastRow < firstRow || lastColumn < firstColumn) {
            return SourceEvidence.Unavailable("That cell is no longer in this sheet.")
        }

        return SourceEvidence.Cells(
            label = "Sheet ${cell.sheet} · cell ${References.columnName(cell.column)}${cell.row + 1}",
            columnLabels = (firstColumn..lastColumn).map(References::columnName),
            rows = (firstRow..lastRow).map { row ->
                SourceEvidence.Cells.Row(
                    label = "${row + 1}",
                    values = (firstColumn..lastColumn).map { sheet.text(row, it) },
                    isFocus = row == cell.row,
                )
            },
            focusColumn = cell.column - firstColumn,
        )
    }

    private companion object {
        /** Rows and columns either side. Enough for context, few enough to read at a glance. */
        const val CONTEXT = 3
    }
}
