package com.okayanshul.docaction.document.pdf

import android.content.Context
import com.okayanshul.docaction.domain.PageContent
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.io.File
import java.io.IOException

/**
 * ADR-001 tier 2: works on every supported device, and the only tier that supplies glyph
 * geometry below API 35.
 *
 * PdfBox-Android's last upstream release was 2023-01-02, so this class is expected to run
 * inside the isolated parsing process (ADR-002). Nothing here assumes that — it is a plain
 * reader — but nothing here should be called from the UI process either.
 */
class PdfBoxTextSource private constructor(
    private val document: PDDocument,
    override val info: PdfInfo,
) : PdfTextSource {

    private val stripper = PositionedTextStripper()

    override fun page(index: Int): PageContent? {
        require(index in 0 until info.pageCount) { "page $index out of range" }

        val page = document.getPage(index)
        val box = page.mediaBox
        val runs = try {
            stripper.runsFor(document, index)
        } catch (e: IOException) {
            // One unreadable page must cost that page, not the document.
            return null
        }

        if (!hasUsableTextLayer(runs.sumOf { it.text.length }, runs.size)) return null

        return PageContent(
            index = index,
            widthPt = box.width,
            heightPt = box.height,
            runs = runs,
        )
    }

    override fun close() {
        runCatching { document.close() }
    }

    companion object Factory : PdfTextSourceFactory {

        /**
         * A scanned page often carries a few stray characters from a header stamp or a
         * "Scanned by …" watermark, so "has any text" is the wrong test.
         *
         * Character density against page area was tried first and rejected: a timetable is
         * inherently sparse — roughly 600 characters on A4, about 6 per square inch — so
         * any density threshold high enough to exclude a watermark also excluded real
         * timetables.
         *
         * Run count discriminates far better. A watermark is one or two runs regardless of
         * how long it is; a page with real content has many, because the content is spread
         * across the page.
         */
        internal fun hasUsableTextLayer(characters: Int, runCount: Int): Boolean =
            characters >= MIN_CHARACTERS && runCount >= MIN_RUNS

        /** Loads PDFBox's font resources once per process. Cheap to call repeatedly. */
        fun initialise(context: Context) {
            PDFBoxResourceLoader.init(context.applicationContext)
        }

        override fun open(file: File): PdfTextSource = open(file, spillToDisk = true)

        /**
         * @param spillToDisk false when the caller has no writable storage.
         *
         * The isolated parsing process is the case: it has no data directory at all, so
         * PdfBox's scratch file cannot be created and every document fails to open with a
         * plain IOException that looks exactly like a corrupt file. Main-memory-only is also
         * the better answer there on its own terms — a spill file is filesystem access that
         * the sandbox exists to not have — and a document too big for the budget kills a
         * throwaway process rather than the app.
         */
        fun open(file: File, spillToDisk: Boolean): PdfTextSource {
            if (!file.exists() || file.length() == 0L) throw PdfOpenException(PdfOpenFailure.Empty)
            if (file.length() > MAX_BYTES) throw PdfOpenException(PdfOpenFailure.TooLarge)

            val document = try {
                // Cap the parser's heap. A malformed or hostile document exhausts this
                // budget and fails cleanly rather than taking the process with it.
                PDDocument.load(
                    file,
                    if (spillToDisk) {
                        MemoryUsageSetting.setupMixed(MAX_MAIN_MEMORY_BYTES)
                    } else {
                        MemoryUsageSetting.setupMainMemoryOnly(MAX_MAIN_MEMORY_BYTES)
                    },
                )
            } catch (e: InvalidPasswordException) {
                throw PdfOpenException(PdfOpenFailure.Encrypted)
            } catch (e: IOException) {
                throw PdfOpenException(PdfOpenFailure.Corrupt)
            } catch (e: RuntimeException) {
                // PdfBox throws unchecked exceptions on some malformed structures.
                throw PdfOpenException(PdfOpenFailure.Corrupt)
            }

            // We never attempt to bypass protection — not empty-password probes, not
            // owner-password stripping. An encrypted document is reported as such.
            if (document.isEncrypted) {
                document.close()
                throw PdfOpenException(PdfOpenFailure.Encrypted)
            }

            val pages = document.numberOfPages
            if (pages <= 0) {
                document.close()
                throw PdfOpenException(PdfOpenFailure.Corrupt)
            }

            return PdfBoxTextSource(document, PdfInfo(pageCount = pages, encrypted = false))
        }

        private const val MIN_CHARACTERS = 24
        private const val MIN_RUNS = 4
        private const val MAX_BYTES = 100L * 1024 * 1024
        private const val MAX_MAIN_MEMORY_BYTES = 32L * 1024 * 1024
    }
}
