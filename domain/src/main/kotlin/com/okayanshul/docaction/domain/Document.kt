package com.okayanshul.docaction.domain

/** What the user handed us. [declaredMimeType] is what the OS claimed — untrusted. */
data class DocumentSource(
    val uri: String,
    val displayName: String,
    val declaredMimeType: String?,
    val sizeBytes: Long,
)

/**
 * [Manual] is not a document at all — it is the user typing an event in by hand.
 *
 * It sits in this enum rather than beside it so that a hand-made review set travels through
 * exactly the same review, confirm, write and undo path as an extracted one. The places that
 * genuinely need a file — source rendering, page counts, crop — already branch on the
 * specific formats they can handle, and answer "nothing to show" for anything else, which is
 * the correct answer here too.
 */
enum class DocumentFormat { Pdf, Image, Xlsx, Csv, PlainText, Manual, Unsupported }

/** How a piece of text reached us. Determines the confidence baseline. */
enum class TextOrigin { PdfTextLayer, Ocr, SpreadsheetCell, CsvField, PlainText }

/**
 * A positioned piece of text — the atom of everything downstream.
 *
 * This is the interface between *format handling* and *understanding*. Every reader
 * produces these and nothing else; every extraction engine consumes these and nothing
 * else. That is what makes adding a format a real extension point rather than an
 * aspiration.
 *
 * [confidence] is null for a PDF text layer or a spreadsheet cell, because those are not
 * recognition results. Storing 1.0f there would conflate certainty with absence of
 * measurement.
 */
data class TextRun(
    val text: String,
    val bounds: BoundingBox,
    val confidence: Float?,
    val origin: TextOrigin,
)

data class PageContent(
    val index: Int,
    val widthPt: Float,
    val heightPt: Float,
    val runs: List<TextRun>,
) {
    /**
     * The page as it is after the user has said "this part, not the rest".
     *
     * Text is **filtered, not re-read**. Cropping the pixels and recognising again would be
     * the obvious implementation and it is worse in two ways: the recogniser would have to
     * run a second time, and every bound it returned would be in crop coordinates, so
     * "where did this come from?" would point at the wrong place on the page. Keeping the
     * page's own extent and dropping the runs outside the region leaves every remaining
     * coordinate exactly as true as it was.
     *
     * A run counts as inside when its centre is: a heading that overhangs the region by a
     * few points is still that region's heading, and a strict test would drop it.
     */
    fun cropped(region: BoundingBox?): PageContent {
        if (region == null) return this
        val left = region.left * widthPt
        val right = region.right * widthPt
        val top = region.top * heightPt
        val bottom = region.bottom * heightPt

        return copy(
            runs = runs.filter { run ->
                run.bounds.centerX in left..right && run.bounds.centerY in top..bottom
            },
        )
    }
}

data class DocumentContent(
    val format: DocumentFormat,
    val pages: List<PageContent>,
    val issues: List<Issue> = emptyList(),
)

/** Progress for the stage list on the processing screen. */
data class StageProgress(
    val stage: Stage,
    val index: Int = 0,
    val total: Int = 0,
) {
    /** False when the total genuinely isn't knowable — the UI must then show an indeterminate state. */
    val determinate: Boolean get() = total > 0
}

enum class Stage {
    Validating,
    DetectingFormat,
    ReadingDocument,
    DetectingStructure,
    FindingDates,
    FindingTimes,
    BuildingSchedule,
}

/**
 * What the user told us they are looking for. A contextual hint measurably improves
 * extraction, which is why rescue mode asks for it explicitly.
 */
data class ExtractionHints(
    val expecting: Expectation = Expectation.Unknown,
    val pageSelection: List<Int>? = null,
    val cropRegion: BoundingBox? = null,
)

enum class Expectation { Unknown, Timetable, ExamSchedule, Deadlines, Events, Appointments }
