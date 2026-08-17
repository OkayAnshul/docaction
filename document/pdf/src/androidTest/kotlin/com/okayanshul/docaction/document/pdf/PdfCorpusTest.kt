package com.okayanshul.docaction.document.pdf

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.okayanshul.docaction.domain.CalendarEventCandidate
import com.okayanshul.docaction.domain.Confident
import com.okayanshul.docaction.domain.DocumentSource
import com.okayanshul.docaction.domain.ExtractionHints
import com.okayanshul.docaction.domain.FailureReason
import com.okayanshul.docaction.domain.Outcome
import com.okayanshul.docaction.domain.SourceReference
import com.okayanshul.docaction.domain.TermBounds
import com.okayanshul.docaction.domain.valueOrNull
import com.okayanshul.docaction.extraction.table.Cell
import com.okayanshul.docaction.extraction.table.TableBuilder
import com.okayanshul.docaction.extraction.timetable.Orientation
import com.okayanshul.docaction.extraction.timetable.TimetableBuilder
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real thing: genuine PDFs, the real PdfBox parser, the real extraction engine, on a
 * real device. Everything up to this point was a hypothesis about documents we had not
 * actually read.
 *
 * Corpus lives in `src/androidTest/assets/corpus`.
 */
@RunWith(AndroidJUnit4::class)
class PdfCorpusTest {

    private val context = InstrumentationRegistry.getInstrumentation().context
    private val tables = TableBuilder()
    private val timetables = TimetableBuilder()

    private lateinit var reader: PdfDocumentReader

    companion object {
        @BeforeClass
        @JvmStatic
        fun loadPdfBoxResources() {
            PdfBoxTextSource.initialise(InstrumentationRegistry.getInstrumentation().targetContext)
        }
    }

    @Before
    fun setUp() {
        reader = PdfDocumentReader(fileFor = ::copyFromAssets)
    }

    private fun copyFromAssets(source: DocumentSource): File {
        val name = source.displayName
        val out = File(context.cacheDir, name)
        context.assets.open("corpus/$name").use { input ->
            out.outputStream().use { input.copyTo(it) }
        }
        return out
    }

    private fun sourceFor(name: String) =
        DocumentSource(uri = "asset://corpus/$name", displayName = name, declaredMimeType = null, sizeBytes = 0)

    private fun read(name: String) = runBlocking {
        reader.read(sourceFor(name), ExtractionHints()) {}
    }

    private fun sourceOf(cell: Cell): SourceReference =
        SourceReference.PdfSpan(0, cell.bounds ?: com.okayanshul.docaction.domain.BoundingBox(0f, 0f, 0f, 0f))

    // --- reading ---

    @Test
    fun columnTimetableYieldsPositionedText() {
        val outcome = read("column-timetable.pdf")
        val content = outcome.valueOrNull ?: error("expected content, got $outcome")

        assertThat(content.pages).hasSize(1)
        val runs = content.pages.first().runs
        assertThat(runs).isNotEmpty()
        // Geometry is the whole point — a run with no extent is useless downstream.
        assertThat(runs.all { it.bounds.width > 0f && it.bounds.height > 0f }).isTrue()
        assertThat(runs.map { it.text }).contains("Monday")
    }

    @Test
    fun anEmptyFileIsReportedAsEmpty() {
        assertThat(read("empty.pdf")).isEqualTo(Outcome.Failure(FailureReason.Empty))
    }

    @Test
    fun aCorruptFileIsReportedAsCorruptRatherThanCrashing() {
        assertThat(read("corrupt.pdf")).isEqualTo(Outcome.Failure(FailureReason.Corrupt))
    }

    @Test
    fun aPageWithNoTextLayerIsReportedAsSuch() {
        // The scanned-style page carries no selectable text. That is the "use OCR or
        // screenshot" path, and it must be distinguishable from a broken file.
        assertThat(read("scan.pdf")).isEqualTo(Outcome.Failure(FailureReason.NoTextLayer))
    }

    // --- end to end: PDF bytes to calendar candidates ---

    @Test
    fun columnTimetableBecomesRecurringCalendarCandidates() {
        val content = read("column-timetable.pdf").valueOrNull!!
        val grid = tables.build(content.pages.first().runs)!!
        val result = timetables.build(grid, "CSE Semester 5 Section B", ::sourceOf)

        assertThat(result.orientation).isEqualTo(Orientation.ColumnOriented)
        val group = result.group ?: error("no schedule found: ${result.reason}")

        // 5 time rows x 5 weekdays = 25 classes in this document.
        assertThat(group.entries.size).isAtLeast(20)

        val term = TermBounds(LocalDate.of(2026, 8, 17), LocalDate.of(2026, 12, 5))
        val results = group.entries.map { it to CalendarEventCandidate.from(it, ZoneId.of("Asia/Kolkata"), term) }
        val accepted = results.mapNotNull { (_, r) ->
            (r as? CalendarEventCandidate.Result.Accepted)?.candidate
        }

        if (accepted.isEmpty()) {
            val gridDump = (0 until minOf(grid.rowCount, 6)).joinToString("\n") { r ->
                "row $r: " + grid.row(r).joinToString(" | ") { "[${it.text}]" }
            }
            val why = results.take(3).joinToString("\n") { (entry, result) ->
                "entry=${entry.id.value} start=${entry.startTime} end=${entry.endTime} -> $result"
            }
            error("every entry was rejected:\n$gridDump\n$why")
        }
        // Every class repeats weekly — one recurring event each, never one per occurrence.
        assertThat(accepted.all { it.recurrence != null }).isTrue()
        assertThat(accepted.all { it.recurrence!!.until == term.end }).isTrue()
        // Every candidate traces back to the document.
        assertThat(accepted.all { it.sources.isNotEmpty() }).isTrue()

        val mondayNine = accepted.first {
            it.recurrence!!.byWeekday.contains(DayOfWeek.MONDAY) && it.start.toLocalTime() == LocalTime.of(9, 0)
        }
        assertThat(mondayNine.title).isEqualTo("Data Structures")
        assertThat(mondayNine.location).isEqualTo("K10")
        assertThat(mondayNine.end.toLocalTime()).isEqualTo(LocalTime.of(10, 0))
    }

    @Test
    fun afternoonTimesSurviveTheTwentyFourHourColumn() {
        val content = read("column-timetable.pdf").valueOrNull!!
        val grid = tables.build(content.pages.first().runs)!!
        val group = timetables.build(grid, "CSE", ::sourceOf).group!!

        val starts = group.entries.mapNotNull { it.startTime.valueOrNull }.distinct()
        assertThat(starts).containsAtLeast(LocalTime.of(9, 0), LocalTime.of(14, 0), LocalTime.of(15, 0))
    }

    @Test
    fun rowOrientedTimetableIsReadOneEntryPerRow() {
        val content = read("row-timetable.pdf").valueOrNull!!
        val grid = tables.build(content.pages.first().runs)!!
        val result = timetables.build(grid, "MCA Semester 3", ::sourceOf)

        assertThat(result.orientation).isEqualTo(Orientation.RowOriented)
        val group = result.group ?: error("no schedule found: ${result.reason}")

        assertThat(group.entries).hasSize(6)
        val first = group.entries.first()
        assertThat(first.title.valueOrNull).isEqualTo("Software Engineering")
        assertThat(first.weekday.valueOrNull).isEqualTo(DayOfWeek.MONDAY)
        assertThat(first.startTime.valueOrNull).isEqualTo(LocalTime.of(9, 0))
    }

    // --- no fabrication, on real input ---

    @Test
    fun noCandidateIsBuiltFromAMissingField() {
        val content = read("column-timetable.pdf").valueOrNull!!
        val grid = tables.build(content.pages.first().runs)!!
        val group = timetables.build(grid, "CSE", ::sourceOf).group!!

        val term = TermBounds(LocalDate.of(2026, 8, 17), LocalDate.of(2026, 12, 5))
        group.entries.forEach { entry ->
            val result = CalendarEventCandidate.from(entry, ZoneId.of("Asia/Kolkata"), term)
            val hasMissingRequired = entry.title is Confident.Missing ||
                entry.startTime is Confident.Missing ||
                entry.endTime is Confident.Missing
            if (hasMissingRequired) {
                assertThat(result).isInstanceOf(CalendarEventCandidate.Result.Rejected::class.java)
            }
        }
    }
}
