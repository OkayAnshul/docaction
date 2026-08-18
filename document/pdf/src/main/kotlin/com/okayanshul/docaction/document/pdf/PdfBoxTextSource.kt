package com.okayanshul.docaction.document.pdf

import android.content.Context
import com.okayanshul.docaction.domain.PageContent
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.io.File
import java.io.IOException
import java.io.InputStream

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

        override fun open(file: File): PdfTextSource {
            if (!file.exists() || file.length() == 0L) throw PdfOpenException(PdfOpenFailure.Empty)
            if (file.length() > MAX_BYTES) throw PdfOpenException(PdfOpenFailure.TooLarge)

            // Cap the parser's heap. A malformed or hostile document exhausts this budget and
            // fails cleanly rather than taking the process with it. Random access straight
            // off the file, spilling to a scratch file past the budget.
            return load { PDDocument.load(file, MemoryUsageSetting.setupMixed(MAX_MAIN_MEMORY_BYTES)) }
        }

        /**
         * Opens a document from an already-open descriptor, entirely in memory.
         *
         * This exists for the isolated parsing process, and the reason is worth writing down
         * because it is not obvious and it cost an afternoon.
         *
         * The obvious way to hand a file to a process that cannot open files is to pass the
         * descriptor and let it read `/proc/self/fd/N`. That path exists and `stat` works, so
         * everything *looks* fine — but opening it is a fresh open of the underlying file,
         * subject to the usual checks, and SELinux does not let an isolated process open an
         * app's data files. PdfBox's file loader builds a `RandomAccessFile`, so it hit
         * exactly that and every document came back `FileNotFoundException` → "Corrupt".
         *
         * That is the sandbox working, not failing. The descriptor itself is readable; only
         * re-opening the path is not. So the bytes are streamed off the descriptor instead.
         *
         * The cost is that the document is held in memory rather than paged off disk, which
         * is why [maxBytes] is enforced against the descriptor's own size — an isolated
         * process cannot `stat` the path either. A document past the budget fails cleanly,
         * and one that still exhausts the heap takes a throwaway process with it, which is
         * the whole point of parsing here.
         */
        fun openStream(input: InputStream, sizeBytes: Long, maxBytes: Long = MAX_IN_MEMORY_BYTES): PdfTextSource {
            if (sizeBytes <= 0L) throw PdfOpenException(PdfOpenFailure.Empty)
            if (sizeBytes > maxBytes) throw PdfOpenException(PdfOpenFailure.TooLarge)

            return load { PDDocument.load(input, MemoryUsageSetting.setupMainMemoryOnly(maxBytes)) }
        }

        /** The shared tail: translate the parser's vocabulary, and refuse what we must. */
        private inline fun load(open: () -> PDDocument): PdfTextSource {
            val document = try {
                open()
            } catch (e: InvalidPasswordException) {
                throw PdfOpenException(PdfOpenFailure.Encrypted, e)
            } catch (e: IOException) {
                throw PdfOpenException(PdfOpenFailure.Corrupt, e)
            } catch (e: RuntimeException) {
                // PdfBox throws unchecked exceptions on some malformed structures.
                throw PdfOpenException(PdfOpenFailure.Corrupt, e)
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

        /**
         * The ceiling when there is no disk to spill to.
         *
         * Lower than [MAX_BYTES] on purpose: a document read entirely into memory has to fit
         * there. Comfortably above any real timetable, exam schedule or notice — the largest
         * in the corpus is under 3 MB — and low enough that exceeding it is a clean refusal
         * rather than a process the system kills for us.
         */
        private const val MAX_IN_MEMORY_BYTES = 48L * 1024 * 1024
    }
}
