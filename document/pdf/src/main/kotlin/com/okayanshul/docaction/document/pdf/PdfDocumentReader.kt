package com.okayanshul.docaction.document.pdf

import com.okayanshul.docaction.domain.DocumentContent
import com.okayanshul.docaction.domain.DocumentFormat
import com.okayanshul.docaction.domain.DocumentReader
import com.okayanshul.docaction.domain.DocumentSource
import com.okayanshul.docaction.domain.ExtractionHints
import com.okayanshul.docaction.domain.FailureReason
import com.okayanshul.docaction.domain.Issue
import com.okayanshul.docaction.domain.IssueKind
import com.okayanshul.docaction.domain.OcrEngine
import com.okayanshul.docaction.domain.Outcome
import com.okayanshul.docaction.domain.PageContent
import com.okayanshul.docaction.domain.Stage
import com.okayanshul.docaction.domain.StageProgress
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Reads a PDF into positioned text, page by page.
 *
 * Everything about this class is bounded: one page in memory at a time, a per-page
 * timeout so a pathological page costs that page rather than the document, and
 * cancellation checked at every page boundary.
 *
 * Pages with no usable text layer are reported as issues rather than failures — a text
 * document with three scanned appendix pages is a [Outcome.Partial], not a dead end.
 */
class PdfDocumentReader(
    private val factory: PdfTextSourceFactory = PdfBoxTextSource,
    private val fileFor: (DocumentSource) -> File?,
    private val perPageTimeoutMillis: Long = 15_000,
    /**
     * ADR-001 tier 3. When supplied, pages with no text layer are rasterised and read by
     * OCR instead of being lost. Optional by design: the reader stays fully functional
     * without it, and a build with no recogniser simply reports `NoTextLayer`.
     */
    private val ocr: OcrEngine? = null,
) : DocumentReader {

    override fun supports(format: DocumentFormat) = format == DocumentFormat.Pdf

    override suspend fun read(
        source: DocumentSource,
        hints: ExtractionHints,
        onProgress: (StageProgress) -> Unit,
    ): Outcome<DocumentContent> {
        val file = fileFor(source) ?: return Outcome.Failure(FailureReason.PermissionRevoked)

        val textSource = try {
            factory.open(file)
        } catch (e: PdfOpenException) {
            return Outcome.Failure(
                when (e.failure) {
                    PdfOpenFailure.Encrypted -> FailureReason.Encrypted
                    PdfOpenFailure.Corrupt -> FailureReason.Corrupt
                    PdfOpenFailure.Empty -> FailureReason.Empty
                    PdfOpenFailure.TooLarge -> FailureReason.TooLarge
                }
            )
        }

        // Only opened if a page actually needs rasterising.
        var renderer: PdfPageRenderer? = null

        return textSource.use { pdf ->
            val requested = hints.pageSelection?.filter { it in 0 until pdf.info.pageCount }
                ?: (0 until pdf.info.pageCount).toList()

            val pages = mutableListOf<PageContent>()
            val issues = mutableListOf<Issue>()

            requested.forEachIndexed { position, index ->
                currentCoroutineContext().ensureActive()
                onProgress(StageProgress(Stage.ReadingDocument, position + 1, requested.size))

                val page = try {
                    withTimeoutOrNull(perPageTimeoutMillis) { pdf.page(index) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The parser is third-party and stale; a page that blows up is a page
                    // we skip, with the reason kept out of the message (it would quote
                    // document content into crash reports).
                    null
                }

                when {
                    // The crop, when the user has drawn one, applies to whatever this page
                    // yielded — text layer or recognition, it is the same filter.
                    page != null -> pages += page.cropped(hints.cropRegion)

                    // Tier 3. Decided per page, so a text document with a scanned appendix
                    // reads the appendix instead of failing as a whole.
                    ocr != null -> {
                        if (renderer == null) renderer = runCatching { PdfPageRenderer(file) }.getOrNull()
                        val recognised = renderer?.let { readByOcr(it, index) }
                        if (recognised != null) {
                            pages += recognised.cropped(hints.cropRegion)
                            issues += Issue(
                                kind = IssueKind.NoTextOnPage,
                                detail = "Page ${index + 1} was read from the image",
                            )
                        } else {
                            issues += Issue(
                                kind = IssueKind.NoTextOnPage,
                                detail = "Page ${index + 1} has no selectable text",
                            )
                        }
                    }

                    else -> issues += Issue(
                        kind = IssueKind.NoTextOnPage,
                        detail = "Page ${index + 1} has no selectable text",
                    )
                }
            }

            renderer?.close()

            when {
                pages.isEmpty() && issues.isEmpty() -> Outcome.Failure(FailureReason.Corrupt)

                // Every page was an image. This is the "use OCR or screenshot" path, and
                // it is a distinct outcome from a broken file.
                pages.isEmpty() -> Outcome.Failure(FailureReason.NoTextLayer)
                issues.isEmpty() -> Outcome.Success(DocumentContent(DocumentFormat.Pdf, pages))
                else -> Outcome.Partial(DocumentContent(DocumentFormat.Pdf, pages, issues), issues)
            }
        }
    }

    /**
     * Rasterise one page and recognise it.
     *
     * Note the coordinates that come back are *pixels* of the rendered bitmap, not PDF
     * points. That is safe because a page is read either from its text layer or by OCR,
     * never both, and everything downstream measures in units of median text height rather
     * than absolute distance.
     */
    private suspend fun readByOcr(renderer: PdfPageRenderer, index: Int): PageContent? {
        val rendered = renderer.render(index) ?: return null
        val runs = when (val result = ocr?.recognise(rendered)) {
            is Outcome.Success -> result.value
            is Outcome.Partial -> result.value
            else -> return null
        }
        if (runs.isEmpty()) return null

        return PageContent(
            index = index,
            widthPt = rendered.widthPx.toFloat(),
            heightPt = rendered.heightPx.toFloat(),
            runs = runs,
        )
    }
}
