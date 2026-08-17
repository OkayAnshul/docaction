package com.okayanshul.docaction.imports

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.okayanshul.docaction.domain.BoundingBox
import com.okayanshul.docaction.domain.CalendarEventCandidate
import com.okayanshul.docaction.domain.Confident
import com.okayanshul.docaction.domain.DocumentFormat
import com.okayanshul.docaction.domain.EntryId
import com.okayanshul.docaction.domain.ScheduleEntry
import com.okayanshul.docaction.domain.SourceReference
import com.okayanshul.docaction.domain.TermBounds
import com.okayanshul.docaction.imports.source.SourceEvidence
import com.okayanshul.docaction.imports.source.SourceLocator
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "Where did this come from?" against real documents.
 *
 * The assertions people would actually care about: that a page comes back with something
 * drawn on it, that the labels are in the user's vocabulary ("Page 3", "cell C12") rather
 * than ours, and that every way of failing produces a sentence instead of an exception —
 * this feature exists to build trust, and a crash inside it costs more than the feature is
 * worth.
 */
@RunWith(AndroidJUnit4::class)
class SourceLocatorTest {

    // The corpus ships in the test APK; the cache to unpack it into belongs to the app.
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")
    private val term = TermBounds(LocalDate.of(2026, 8, 17), LocalDate.of(2026, 12, 5))
    private lateinit var directory: File

    @Before
    fun setUp() {
        directory = File(context.cacheDir, "source-locator-test").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    private fun asset(name: String, from: String): File =
        File(directory, name).also { target ->
            assets.open("$from/$name").use { input ->
                target.outputStream().use(input::copyTo)
            }
        }

    /**
     * A candidate whose references are spread across its fields, the way the engine
     * produces them — `sources` is derived from the fields, not supplied alongside them.
     */
    private fun candidate(vararg sources: SourceReference): CalendarEventCandidate {
        fun at(index: Int) = sources[index % sources.size]
        val entry = ScheduleEntry(
            id = EntryId("e1"),
            title = Confident.High("Data Structures", at(0)),
            weekday = Confident.High(DayOfWeek.MONDAY, at(1)),
            startTime = Confident.High(LocalTime.of(9, 0), at(2)),
            endTime = Confident.High(LocalTime.of(10, 0), at(3)),
        )
        return (CalendarEventCandidate.from(entry, zone, term)
            as CalendarEventCandidate.Result.Accepted).candidate
    }

    private fun locator(file: File?, format: DocumentFormat) = SourceLocator(
        file = file,
        format = format,
        openStream = { file!!.inputStream() },
    )

    @Test
    fun aPdfComesBackAsAPageWithTheSpanOutlined() = runBlocking {
        val file = asset("dtu-central-tt.pdf", "webcorpus")
        val evidence = locator(file, DocumentFormat.Pdf).locate(
            candidate(SourceReference.PdfSpan(0, BoundingBox(0.1f, 0.2f, 0.9f, 0.25f))),
            widthPx = 600,
        )

        val page = evidence as SourceEvidence.Page
        // One-based: "page 0" is not a thing outside a program.
        assertThat(page.label).isEqualTo("Page 1")
        assertThat(page.image.width).isGreaterThan(0)
        assertThat(page.highlight).isNotNull()
        // The label never leaks coordinates.
        assertThat(page.label).doesNotContain("0.")
        page.image.recycle()
    }

    @Test
    fun aScannedPageIsLocatedByItsPageNumberToo() = runBlocking {
        val file = asset("dtu-central-tt.pdf", "webcorpus")
        val evidence = locator(file, DocumentFormat.Pdf).locate(
            // What an OCR'd PDF page produces: an image region that still knows its page.
            candidate(SourceReference.ImageRegion(BoundingBox(0.1f, 0.5f, 0.9f, 0.6f), page = 0)),
            widthPx = 600,
        )

        val page = evidence as SourceEvidence.Page
        assertThat(page.label).isEqualTo("Page 1")
        page.image.recycle()
    }

    @Test
    fun aWorkbookShowsItsOwnGridAroundTheCell() = runBlocking {
        val file = asset("cp-class-mon-fri.xlsx", "webcorpus")
        val workbook = com.okayanshul.docaction.document.spreadsheet.XlsxReader().read(file)
        val sheet = workbook.sheets.first().name

        val evidence = locator(file, DocumentFormat.Xlsx).locate(
            candidate(SourceReference.SheetCell(sheet, row = 11, column = 2)),
            widthPx = 600,
        )

        val cells = evidence as SourceEvidence.Cells
        // A1 notation, because that is what the user sees in their own spreadsheet.
        assertThat(cells.label).isEqualTo("Sheet $sheet · cell C12")
        assertThat(cells.columnLabels).contains("C")
        assertThat(cells.rows.count { it.isFocus }).isEqualTo(1)
        assertThat(cells.rows.first { it.isFocus }.label).isEqualTo("12")
        assertThat(cells.focusColumn).isIn(cells.columnLabels.indices)
    }

    @Test
    fun aCellOutsideTheSheetSaysSoRatherThanShowingTheWrongOne() = runBlocking {
        val file = asset("cp-class-mon-fri.xlsx", "webcorpus")
        val sheet = com.okayanshul.docaction.document.spreadsheet.XlsxReader()
            .read(file).sheets.first().name

        // A workbook edited since the import can no longer contain the cell we read.
        val evidence = locator(file, DocumentFormat.Xlsx).locate(
            candidate(SourceReference.SheetCell(sheet, row = 0, column = 500)),
            widthPx = 600,
        )

        assertThat((evidence as SourceEvidence.Unavailable).reason)
            .isEqualTo("That cell is no longer in this sheet.")
    }

    @Test
    fun aCorrectedValueSaysTheUserSetIt() = runBlocking {
        val evidence = locator(asset("dtu-central-tt.pdf", "webcorpus"), DocumentFormat.Pdf).locate(
            candidate(SourceReference.UserProvided("start time", 1_700_000_000_000)),
            widthPx = 600,
        )

        val told = evidence as SourceEvidence.Told
        assertThat(told.label).isEqualTo("You set this")
        assertThat(told.detail).contains("start time")
        assertThat(told.detail).contains("no longer what the document says")
    }

    @Test
    fun aDocumentThatIsGoneSaysSoInsteadOfCrashing() = runBlocking {
        val missing = File(directory, "deleted.pdf")
        val evidence = locator(missing, DocumentFormat.Pdf).locate(
            candidate(SourceReference.PdfSpan(0, BoundingBox(0f, 0f, 1f, 1f))),
            widthPx = 600,
        )

        assertThat(evidence).isInstanceOf(SourceEvidence.Unavailable::class.java)
        assertThat((evidence as SourceEvidence.Unavailable).reason)
            .isEqualTo("This document isn't available any more.")
    }

    @Test
    fun anUnreadableDocumentSaysSoInsteadOfCrashing() = runBlocking {
        val rubbish = File(directory, "rubbish.pdf").apply { writeBytes(ByteArray(64) { 7 }) }
        val evidence = locator(rubbish, DocumentFormat.Pdf).locate(
            candidate(SourceReference.PdfSpan(0, BoundingBox(0f, 0f, 1f, 1f))),
            widthPx = 600,
        )

        assertThat(evidence).isInstanceOf(SourceEvidence.Unavailable::class.java)
    }

    @Test
    fun aPageWeNeverRecordedIsAdmittedToRatherThanGuessedAt() = runBlocking {
        val file = asset("cp-class-mon-fri.xlsx", "webcorpus")
        val evidence = locator(file, DocumentFormat.Xlsx).locate(
            candidate(SourceReference.PdfSpan(0, BoundingBox(0f, 0f, 1f, 1f))),
            widthPx = 600,
        )

        assertThat((evidence as SourceEvidence.Unavailable).reason)
            .isEqualTo("We didn't record which cell this came from.")
    }
}
