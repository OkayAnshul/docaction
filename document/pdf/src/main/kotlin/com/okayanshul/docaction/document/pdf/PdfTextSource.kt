package com.okayanshul.docaction.document.pdf

import com.okayanshul.docaction.domain.PageContent
import java.io.File

/** Basic facts about a PDF, read without parsing its content. */
data class PdfInfo(val pageCount: Int, val encrypted: Boolean)

/**
 * Reads text with geometry from a PDF, one page at a time.
 *
 * Page-at-a-time is a hard requirement, not an optimisation: an 84-page document must
 * never be materialised in memory, and progress reporting has to be able to say
 * "page 12 of 84" honestly.
 *
 * See docs/05-architecture.md ADR-001 for the tier strategy this interface abstracts.
 */
interface PdfTextSource : AutoCloseable {
    val info: PdfInfo

    /** Null when the page has no usable text layer — the caller may then fall back to OCR. */
    fun page(index: Int): PageContent?
}

/** Why a PDF could not be opened, in the domain's vocabulary rather than the parser's. */
sealed interface PdfOpenFailure {
    data object Encrypted : PdfOpenFailure
    data object Corrupt : PdfOpenFailure
    data object Empty : PdfOpenFailure
    data object TooLarge : PdfOpenFailure
}

class PdfOpenException(val failure: PdfOpenFailure) : Exception(failure.toString())

interface PdfTextSourceFactory {
    /** @throws PdfOpenException with a translated reason; never leaks a parser exception. */
    fun open(file: File): PdfTextSource
}
