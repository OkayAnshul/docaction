package com.okayanshul.docaction.document.image

import com.okayanshul.docaction.domain.DocumentContent
import com.okayanshul.docaction.domain.DocumentFormat
import com.okayanshul.docaction.domain.DocumentReader
import com.okayanshul.docaction.domain.DocumentSource
import com.okayanshul.docaction.domain.ExtractionHints
import com.okayanshul.docaction.domain.FailureReason
import com.okayanshul.docaction.domain.OcrEngine
import com.okayanshul.docaction.domain.OcrInput
import com.okayanshul.docaction.domain.Outcome
import com.okayanshul.docaction.domain.PageContent
import com.okayanshul.docaction.domain.Stage
import com.okayanshul.docaction.domain.StageProgress

/**
 * Reads a screenshot or photo by recognising the text in it.
 *
 * One "page", since an image is one page. Everything downstream — table reconstruction,
 * timetable building, prose extraction — is unchanged, because the reader emits the same
 * positioned [com.okayanshul.docaction.domain.TextRun]s a PDF does.
 */
class ImageDocumentReader(private val ocr: OcrEngine) : DocumentReader {

    override fun supports(format: DocumentFormat) = format == DocumentFormat.Image

    override suspend fun read(
        source: DocumentSource,
        hints: ExtractionHints,
        onProgress: (StageProgress) -> Unit,
    ): Outcome<DocumentContent> {
        onProgress(StageProgress(Stage.ReadingDocument, 1, 1))

        val runs = when (val result = ocr.recognise(OcrInput.ImageUri(source.uri))) {
            is Outcome.Success -> result.value
            is Outcome.Partial -> result.value
            is Outcome.Failure -> return result
        }

        if (runs.isEmpty()) return Outcome.Failure(FailureReason.NothingActionable)

        // Bounds come from the recogniser, so the page extent is whatever it actually read.
        val right = runs.maxOf { it.bounds.right }
        val bottom = runs.maxOf { it.bounds.bottom }

        val page = PageContent(index = 0, widthPt = right, heightPt = bottom, runs = runs)
            .cropped(hints.cropRegion)

        // A crop that selects nothing is a question the user can answer, not an empty result
        // to puzzle over.
        if (page.runs.isEmpty()) return Outcome.Failure(FailureReason.NothingActionable)

        return Outcome.Success(DocumentContent(format = DocumentFormat.Image, pages = listOf(page)))
    }
}
