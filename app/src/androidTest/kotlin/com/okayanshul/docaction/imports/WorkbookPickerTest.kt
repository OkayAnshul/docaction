package com.okayanshul.docaction.imports

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.okayanshul.docaction.document.spreadsheet.XlsxScheduleSource
import com.okayanshul.docaction.domain.DocumentPipeline
import com.okayanshul.docaction.domain.DocumentSource
import com.okayanshul.docaction.domain.PipelineAnswers
import com.okayanshul.docaction.domain.PipelineQuestion
import com.okayanshul.docaction.domain.PipelineResult
import com.okayanshul.docaction.domain.TermBounds
import com.okayanshul.docaction.extraction.EngineScheduleFinder
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Choosing one section out of a workbook that contains hundreds.
 *
 * This is the real institutional export the product was started from: 335 section blocks in
 * one file, of which the user wants exactly one. It has a two-pass shape — list the sections
 * cheaply, then build only the chosen one — and the second pass was **built but never
 * wired**. The pipeline re-read the same placeholder list, found every section empty, and
 * told the user "we couldn't find a schedule in this" about their own timetable.
 *
 * Nothing caught it because every test exercised either the picker or the extractor, never
 * the hand-off between them. This test is the hand-off.
 */
@RunWith(AndroidJUnit4::class)
class WorkbookPickerTest {

    private val assets = InstrumentationRegistry.getInstrumentation().context.assets
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var file: File

    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")
    private val term = TermBounds(LocalDate.of(2026, 8, 17), LocalDate.of(2026, 12, 5))

    @Before
    fun setUp() {
        val directory = File(context.cacheDir, "workbook-picker").apply {
            deleteRecursively()
            mkdirs()
        }
        file = File(directory, "kiit-student.xlsx").also { target ->
            assets.open("webcorpus/kiit-student.xlsx").use { input ->
                target.outputStream().use(input::copyTo)
            }
        }
    }

    private fun source() = DocumentSource(
        file.absolutePath, file.name,
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", file.length(),
    )

    private fun pipeline() = DocumentPipeline(
        detector = com.okayanshul.docaction.document.pdf.SignatureFormatDetector { file },
        readers = emptyList(),
        schedules = EngineScheduleFinder(),
        zone = zone,
        scheduleSources = listOf(XlsxScheduleSource({ file })),
    )

    @Test
    fun theFirstPassListsEverySectionWithARealSize() = runBlocking {
        val result = pipeline().run(source(), answers = PipelineAnswers(term = term))

        val question = (result as PipelineResult.NeedsAnswers).questions.first()
        val groups = (question as PipelineQuestion.WhichSchedule).groups

        assertThat(groups.size).isGreaterThan(100)
        // Every one of them showed "0 entries" in the picker, which reads as "these are all
        // empty" and is simply false.
        assertThat(groups.count { it.size > 0 }).isEqualTo(groups.size)
    }

    @Test
    fun pickingASectionBuildsThatSectionInFull() = runBlocking {
        val first = pipeline().run(source(), answers = PipelineAnswers(term = term))
        val groups = ((first as PipelineResult.NeedsAnswers).questions.first()
            as PipelineQuestion.WhichSchedule).groups

        val cs1 = groups.first { it.label.contains("CS1") }
        val second = pipeline().run(
            source(),
            answers = PipelineAnswers(term = term, selectedGroup = cs1.id),
        )

        val review = (second as PipelineResult.Ready).review
        assertThat(review.candidates).isNotEmpty()
        // Weekly classes, bounded by the term the user chose — not a bag of loose entries.
        assertThat(review.candidates.all { it.recurrence != null }).isTrue()
        assertThat(review.candidates.map { it.start.dayOfWeek }.distinct().size).isAtLeast(2)
    }

    @Test
    fun theSectionThatComesBackIsTheOneThatWasAskedFor() = runBlocking {
        val first = pipeline().run(source(), answers = PipelineAnswers(term = term))
        val groups = ((first as PipelineResult.NeedsAnswers).questions.first()
            as PipelineQuestion.WhichSchedule).groups

        // Two different sections must produce two different timetables. Returning whichever
        // block happened to be first would look like success and be wrong for 334 users out
        // of 335.
        val a = groups.first { it.label.contains("CS1") }
        val b = groups.first { it.label.contains("CS2") }

        val fromA = (pipeline().run(source(), answers = PipelineAnswers(term = term, selectedGroup = a.id))
            as PipelineResult.Ready).review
        val fromB = (pipeline().run(source(), answers = PipelineAnswers(term = term, selectedGroup = b.id))
            as PipelineResult.Ready).review

        assertThat(fromA.group!!.label).isEqualTo(a.label)
        assertThat(fromB.group!!.label).isEqualTo(b.label)
        assertThat(fromA.candidates.map { "${it.title}@${it.start}" })
            .isNotEqualTo(fromB.candidates.map { "${it.title}@${it.start}" })
    }

    @Test
    fun everyClassLandsOnAWeekdayWithARealTime() = runBlocking {
        val first = pipeline().run(source(), answers = PipelineAnswers(term = term))
        val groups = ((first as PipelineResult.NeedsAnswers).questions.first()
            as PipelineQuestion.WhichSchedule).groups
        val cs1 = groups.first { it.label.contains("CS1") }

        val review = (pipeline().run(
            source(),
            answers = PipelineAnswers(term = term, selectedGroup = cs1.id),
        ) as PipelineResult.Ready).review

        review.candidates.forEach { candidate ->
            assertThat(candidate.start.dayOfWeek).isNotEqualTo(DayOfWeek.SUNDAY)
            assertThat(candidate.end).isGreaterThan(candidate.start)
            assertThat(candidate.title).isNotEmpty()
        }
    }
}
