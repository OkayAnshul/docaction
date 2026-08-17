package com.okayanshul.docaction.imports.source

import android.graphics.Bitmap
import com.okayanshul.docaction.document.image.SafeImageDecoder
import com.okayanshul.docaction.document.pdf.PdfPageRenderer
import com.okayanshul.docaction.domain.DocumentFormat
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Turning a staged document back into pictures of pages.
 *
 * The one place that knows how to do it, because two features need it for different reasons
 * — Source View shows the page a value came from, and rescue mode shows the page so the user
 * can point at the part that matters — and a second implementation would drift from this one
 * on exactly the documents that are hard to render.
 *
 * The caller owns every bitmap returned and must recycle it.
 */
class DocumentImages(
    private val file: File?,
    private val format: DocumentFormat,
    private val openStream: () -> InputStream,
) {

    /** 0 when the document cannot be opened at all; 1 for anything that isn't paged. */
    suspend fun pageCount(): Int = withContext(Dispatchers.IO) {
        val readable = file?.takeIf { it.exists() } ?: return@withContext 0
        when (format) {
            DocumentFormat.Pdf ->
                runCatching { PdfPageRenderer(readable).use { it.pageCount } }.getOrDefault(0)

            DocumentFormat.Image -> 1
            else -> 0
        }
    }

    suspend fun render(index: Int, widthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        val readable = file?.takeIf { it.exists() } ?: return@withContext null
        when (format) {
            DocumentFormat.Pdf -> runCatching {
                PdfPageRenderer(readable).use { it.renderForDisplay(index, widthPx) }
            }.getOrNull()

            DocumentFormat.Image ->
                (SafeImageDecoder().decode(openStream) as? SafeImageDecoder.Result.Decoded)?.bitmap

            else -> null
        }
    }
}
