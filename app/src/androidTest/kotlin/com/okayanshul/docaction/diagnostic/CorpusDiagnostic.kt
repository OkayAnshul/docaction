package com.okayanshul.docaction.diagnostic

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.okayanshul.docaction.document.pdf.FormatSignature
import com.okayanshul.docaction.document.pdf.PdfBoxTextSource
import com.okayanshul.docaction.document.image.ImageDocumentReader
import com.okayanshul.docaction.document.image.MlKitOcrEngine
import com.okayanshul.docaction.document.pdf.PdfDocumentReader
import com.okayanshul.docaction.document.spreadsheet.SpreadsheetSchedules
import com.okayanshul.docaction.document.spreadsheet.XlsxException
import com.okayanshul.docaction.domain.BoundingBox
import com.okayanshul.docaction.domain.CalendarEventCandidate
import com.okayanshul.docaction.domain.DocumentFormat
import com.okayanshul.docaction.domain.DocumentSource
import com.okayanshul.docaction.domain.ExtractionHints
import com.okayanshul.docaction.domain.Outcome
import com.okayanshul.docaction.domain.ScheduleGroup
import com.okayanshul.docaction.domain.SourceReference
import com.okayanshul.docaction.domain.TermBounds
import com.okayanshul.docaction.domain.valueOrNull
import com.okayanshul.docaction.extraction.table.Cell
import com.okayanshul.docaction.extraction.table.TableBuilder
import com.okayanshul.docaction.extraction.timetable.Orientation
import com.okayanshul.docaction.extraction.prose.DocumentKind
import com.okayanshul.docaction.extraction.prose.ProseExtractor
import com.okayanshul.docaction.extraction.timetable.TimetableBuilder
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs every document in the corpus through the real pipeline and reports, per document,
 * exactly where it got to.
 *
 * A **diagnostic**, not a gate. The point is to find out where a deterministic engine
 * stops coping with documents nobody designed it against, so failures here are findings.
 * The one column that would be a genuine defect is a document that produces confident but
 * *wrong* output — everything else is either working or honestly declining.
 */
@RunWith(AndroidJUnit4::class)
class CorpusDiagnostic {

    /** Assets live in the test APK; scratch files go in the app-under-test's cache. */
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets
    private val scratch = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
    private val tables = TableBuilder()
    private val timetables = TimetableBuilder()
    private val spreadsheets = SpreadsheetSchedules()
    private val prose = ProseExtractor()
    private val ocr = MlKitOcrEngine(InstrumentationRegistry.getInstrumentation().targetContext)
    private val term = TermBounds(LocalDate.of(2026, 8, 17), LocalDate.of(2026, 12, 5))

    companion object {
        /** Long documents are sampled rather than fully walked, to keep the run quick. */
        const val MAX_PAGES = 12

        @BeforeClass @JvmStatic
        fun init() = PdfBoxTextSource.initialise(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    private fun stage(name: String): File {
        val out = File(File(scratch, "corpus-diagnostic"), name)
        out.parentFile?.mkdirs()
        assets.open("webcorpus/$name").use { i -> out.outputStream().use { i.copyTo(it) } }
        return out
    }

    private fun sourceOf(cell: Cell): SourceReference =
        SourceReference.PdfSpan(0, cell.bounds ?: BoundingBox(0f, 0f, 0f, 0f))

    private fun countCandidates(group: ScheduleGroup): Int = group.entries.count { entry ->
        CalendarEventCandidate.from(entry, ZoneId.systemDefault(), term) is
            CalendarEventCandidate.Result.Accepted
    }

    @Test
    fun diagnose() = runBlocking {
        val names = assets.list("webcorpus")!!.sorted()
        println("DIAG ===== ${names.size} documents =====")

        names.forEach { name ->
            val staged = runCatching { stage(name) }
            val file = staged.getOrElse {
                return@forEach println("DIAG $name | STAGE-FAILED ${it::class.simpleName}: ${it.message}")
            }

            val head = ByteArray(FormatSignature.PROBE_BYTES)
            val read = file.inputStream().use { it.read(head) }
            val detected = FormatSignature.detect(head.copyOf(maxOf(read, 0)))

            val line = runCatching {
                when (detected) {
                    DocumentFormat.Pdf -> diagnosePdf(file, name)
                    DocumentFormat.Xlsx -> diagnoseXlsx(file)
                    // Nothing reads these yet. The engine must decline, not misbehave.
                    DocumentFormat.Image -> diagnoseImage(file, name)
                    else -> diagnosePdf(file, name) // let the reader produce the honest reason
                }
            }.getOrElse { "THREW ${it::class.simpleName}: ${it.message?.take(90)}" }

            println("DIAG $name | $detected | $line")
        }
        println("DIAG ===== end =====")
    }

    private suspend fun diagnosePdf(file: File, name: String): String {
        val reader = PdfDocumentReader(fileFor = { file }, ocr = ocr)
        val outcome = reader.read(DocumentSource(name, name, null, file.length()), ExtractionHints()) {}

        if (outcome is Outcome.Failure) return "DECLINED ${outcome.reason}"

        val content = outcome.valueOrNull!!
        val runs = content.pages.sumOf { it.runs.size }
        var bestCount = 0
        var bestOrientation = Orientation.None
        var bestPage = -1
        var note = "no timetable structure on any sampled page"

        content.pages.take(MAX_PAGES).forEach { page ->
            val grid = tables.build(page.runs) ?: return@forEach
            val result = timetables.build(grid, name) { sourceOf(it) }
            val entries = result.group?.entries.orEmpty()
            if (entries.size > bestCount) {
                bestCount = entries.size
                bestOrientation = result.orientation
                bestPage = page.index
                note = result.reason ?: "ok"
            } else if (bestCount == 0 && result.reason != null) {
                note = result.reason!!
                bestOrientation = result.orientation
            }
        }

        val candidates = if (bestPage >= 0) {
            val grid = tables.build(content.pages.first { it.index == bestPage }.runs)!!
            timetables.build(grid, name) { sourceOf(it) }.group?.let(::countCandidates) ?: 0
        } else {
            0
        }

        if (bestCount > 0) {
            val verdict = if (candidates > 0) "WORKS-grid" else "PARTIAL-grid"
            return "$verdict pages=${content.pages.size} runs=$runs orient=$bestOrientation " +
                "entries=$bestCount candidates=$candidates page=$bestPage | $note"
        }

        // No grid. Try reading it as prose — notices, calendars, bookings, programmes.
        var proseBest = 0
        var proseKind = DocumentKind.Unknown
        var proseNote = note
        var prosePage = -1
        content.pages.take(MAX_PAGES).forEach { page ->
            val result = prose.extract(page.runs, name, { line -> SourceReference.PdfSpan(page.index, line.bounds) })
            val found = result.group?.entries?.size ?: 0
            if (found > proseBest) {
                proseBest = found
                proseKind = result.kind
                prosePage = page.index
                proseNote = "ok"
            } else if (proseBest == 0 && result.reason != null) {
                proseNote = result.reason!!
            }
        }

        val verdict = if (proseBest > 0) "WORKS-prose" else "NO-EXTRACTION"
        return "$verdict pages=${content.pages.size} runs=$runs kind=$proseKind " +
            "proseEntries=$proseBest page=$prosePage | $proseNote"
    }

    private suspend fun diagnoseImage(file: File, name: String): String {
        val reader = ImageDocumentReader(ocr)
        val source = DocumentSource("file://" + file.absolutePath, name, null, file.length())
        val outcome = reader.read(source, ExtractionHints()) {}
        if (outcome is Outcome.Failure) return "DECLINED " + outcome.reason

        val page = outcome.valueOrNull!!.pages.first()
        val runs = page.runs.size

        val gridResult = tables.build(page.runs)?.let { g -> timetables.build(g, name) { sourceOf(it) } }
        val gridEntries = gridResult?.group?.entries.orEmpty()
        if (gridEntries.isNotEmpty()) {
            val candidates = gridResult?.group?.let(::countCandidates) ?: 0
            val verdict = if (candidates > 0) "WORKS-grid" else "PARTIAL-grid"
            return "$verdict ocrRuns=$runs orient=${gridResult?.orientation} " +
                "entries=${gridEntries.size} candidates=$candidates"
        }

        val proseResult = prose.extract(
            runs = page.runs,
            label = name,
            sourceOf = { line -> SourceReference.ImageRegion(line.bounds) },
        )
        val proseEntries = proseResult.group?.entries?.size ?: 0
        return if (proseEntries > 0) {
            "WORKS-prose ocrRuns=$runs kind=${proseResult.kind} proseEntries=$proseEntries"
        } else {
            "NO-EXTRACTION ocrRuns=$runs | ${gridResult?.reason ?: proseResult.reason}"
        }
    }

    private fun diagnoseXlsx(file: File): String {
        val workbook = try {
            spreadsheets.open(file)
        } catch (e: XlsxException) {
            return "DECLINED ${e.failure}"
        }

        val detected = spreadsheets.detect(workbook)
        if (detected.isEmpty()) {
            return "NO-EXTRACTION sheets=${workbook.sheets.map { it.name }} — no schedule blocks found"
        }

        val first = detected.first()
        val group = spreadsheets.extract(workbook, first).group
        val entries = group?.entries?.size ?: 0
        val candidates = group?.let(::countCandidates) ?: 0
        val verdict = if (candidates > 0) "WORKS" else if (entries > 0) "PARTIAL" else "NO-EXTRACTION"

        return "$verdict sheets=${workbook.sheets.size} schedules=${detected.size} " +
            "first='${first.label.take(40)}' entries=$entries candidates=$candidates"
    }
}
