package com.okayanshul.docaction.document.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.okayanshul.docaction.domain.OcrInput
import java.io.File
import java.nio.ByteBuffer

/**
 * Rasterises PDF pages so a page with no text layer can still be read, via OCR.
 *
 * Uses the framework renderer rather than PdfBox: it is maintained, hardware-accelerated,
 * and adds no dependency. Note this is *rendering* only — the framework's text-extraction
 * APIs are API 35+ and flagged, which is why text still comes from PdfBox (ADR-001).
 *
 * Render size is clamped regardless of what the page declares. A page is free to claim
 * absurd dimensions, and multiplying that by a DPI scale is how a document turns into an
 * out-of-memory crash.
 */
class PdfPageRenderer(private val file: File) : AutoCloseable {

    private val descriptor: ParcelFileDescriptor =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)

    private val renderer: PdfRenderer = PdfRenderer(descriptor)

    val pageCount: Int get() = renderer.pageCount

    fun render(index: Int, dpi: Int = DEFAULT_DPI): OcrInput.RenderedPage? {
        if (index !in 0 until renderer.pageCount) return null

        return runCatching {
            renderer.openPage(index).use { page ->
                val scale = (dpi.toFloat() / POINTS_PER_INCH).coerceAtMost(
                    maxScaleFor(page.width, page.height),
                )
                val width = (page.width * scale).toInt().coerceAtLeast(1)
                val height = (page.height * scale).toInt().coerceAtLeast(1)

                // ARGB_8888 because PdfRenderer requires it. The bitmap is released as soon
                // as its pixels are copied out.
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE) // unpainted areas are transparent, not white
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                val buffer = ByteBuffer.allocate(bitmap.byteCount)
                bitmap.copyPixelsToBuffer(buffer)
                bitmap.recycle()

                OcrInput.RenderedPage(index, width, height, buffer.array())
            }
        }.getOrNull()
    }

    /**
     * A page as a bitmap to show someone, rather than as pixels to recognise.
     *
     * Sized to the space it will occupy instead of to a DPI: Source View renders one page
     * into a sheet a few hundred pixels wide, and rasterising A4 at 300 dpi to do that
     * would cost about thirty times the memory for no visible gain.
     *
     * The caller owns the bitmap and must recycle it.
     */
    fun renderForDisplay(index: Int, targetWidthPx: Int): Bitmap? {
        if (index !in 0 until renderer.pageCount) return null
        if (targetWidthPx <= 0) return null

        return runCatching {
            renderer.openPage(index).use { page ->
                val scale = (targetWidthPx.toFloat() / page.width.coerceAtLeast(1))
                    .coerceAtMost(maxScaleFor(page.width, page.height))
                val width = (page.width * scale).toInt().coerceAtLeast(1)
                val height = (page.height * scale).toInt().coerceAtLeast(1)

                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                    // Unpainted areas of a PDF are transparent; a page shown to a person is
                    // white paper.
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }.getOrNull()
    }

    /** Whatever scale keeps both edges inside the ceiling. */
    private fun maxScaleFor(widthPt: Int, heightPt: Int): Float {
        val longest = maxOf(widthPt, heightPt).coerceAtLeast(1)
        return MAX_EDGE_PX.toFloat() / longest
    }

    override fun close() {
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
    }

    private companion object {
        const val POINTS_PER_INCH = 72f

        /** Enough for reliable recognition; more costs time without improving accuracy. */
        const val DEFAULT_DPI = 300

        const val MAX_EDGE_PX = 4096
    }
}
