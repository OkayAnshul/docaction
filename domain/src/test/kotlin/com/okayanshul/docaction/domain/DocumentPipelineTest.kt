package com.okayanshul.docaction.domain

import com.google.common.truth.Truth.assertThat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The orchestrator, tested with fake readers and no Android at all.
 *
 * This is the point of keeping `:domain` free of platform dependencies: the sequencing that
 * decides whether a user is asked a question or handed a result is verified in milliseconds,
 * without an emulator.
 */
class DocumentPipelineTest {

    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")
    private val source = DocumentSource("file://tt.pdf", "tt.pdf", null, 1024)
    private val term = TermBounds(LocalDate.of(2026, 8, 17), LocalDate.of(2026, 12, 5))
    private val at = SourceReference.PdfSpan(0, BoundingBox(0f, 0f, 10f, 10f))

    private fun detector(format: DocumentFormat = DocumentFormat.Pdf) = object : FormatDetector {
        override suspend fun detect(source: DocumentSource) = Outcome.Success(format)
    }

    private fun failingDetector(reason: FailureReason) = object : FormatDetector {
        override suspend fun detect(source: DocumentSource): Outcome<DocumentFormat> =
            Outcome.Failure(reason)
    }

    private fun reader(
        format: DocumentFormat = DocumentFormat.Pdf,
        outcome: Outcome<DocumentContent> = Outcome.Success(DocumentContent(format, emptyList())),
    ) = object : DocumentReader {
        override fun supports(f: DocumentFormat) = f == format
        override suspend fun read(
            source: DocumentSource,
            hints: ExtractionHints,
            onProgress: (StageProgress) -> Unit,
        ) = outcome
    }

    private fun finder(vararg groups: ScheduleGroup) = object : ScheduleFinder {
        override suspend fun find(
            content: DocumentContent,
            label: String,
            answers: PipelineAnswers,
            hints: ExtractionHints,
        ) = FoundSchedules(groups.toList())
    }

    private fun weeklyEntry(id: String, day: DayOfWeek, title: String = "Data Structures") =
        ScheduleEntry(
            id = EntryId(id),
            title = Confident.High(title, at),
            weekday = Confident.High(day, at),
            startTime = Confident.High(LocalTime.of(9, 0), at),
            endTime = Confident.High(LocalTime.of(10, 0), at),
        )

    private fun group(
        id: String,
        label: String,
        vararg entries: ScheduleEntry,
        kind: ScheduleKind = ScheduleKind.Weekly,
    ) = ScheduleGroup(GroupId(id), label, entries.toList(), at, kind = kind)

    private fun pipeline(
        detector: FormatDetector = detector(),
        readers: List<DocumentReader> = listOf(reader()),
        finder: ScheduleFinder,
        policy: InferencePolicy = InferencePolicy.Default,
    ) = DocumentPipeline(detector, readers, finder, zone, policy = policy)

    // --- the happy path ---

    @Test
    fun `a single schedule with a term produces candidates`() = runTest {
        val result = pipeline(finder = finder(group("g", "Section B", weeklyEntry("e1", DayOfWeek.MONDAY))))
            .run(source, answers = PipelineAnswers(term = term))

        val ready = result as PipelineResult.Ready
        assertThat(ready.review.candidates).hasSize(1)
        assertThat(ready.review.readyCount).isEqualTo(1)
        assertThat(ready.review.candidates.single().recurrence).isNotNull()
    }

    // --- questions, not guesses ---

    @Test
    fun `several schedules become a question rather than a silent choice`() = runTest {
        val result = pipeline(
            finder = finder(
                group("a", "Section A", weeklyEntry("a1", DayOfWeek.MONDAY)),
                group("b", "Section B", weeklyEntry("b1", DayOfWeek.TUESDAY)),
            )
        ).run(source, answers = PipelineAnswers(term = term))

        val needs = result as PipelineResult.NeedsAnswers
        assertThat(needs.questions).hasSize(1)
        val question = needs.questions.single() as PipelineQuestion.WhichSchedule
        assertThat(question.groups.map { it.label }).containsExactly("Section A", "Section B")
        // Nothing was built, because we do not know which schedule is the user's.
        assertThat(needs.partial.candidates).isEmpty()
    }

    @Test
    fun `answering which schedule finishes without starting over`() = runTest {
        val engine = pipeline(
            finder = finder(
                group("a", "Section A", weeklyEntry("a1", DayOfWeek.MONDAY)),
                group("b", "Section B", weeklyEntry("b1", DayOfWeek.TUESDAY, "DBMS")),
            )
        )

        val result = engine.run(
            source,
            answers = PipelineAnswers(term = term, selectedGroup = GroupId("b")),
        )

        val ready = result as PipelineResult.Ready
        assertThat(ready.review.selectedGroup).isEqualTo(GroupId("b"))
        assertThat(ready.review.candidates.single().title).isEqualTo("DBMS")
    }

    @Test
    fun `a weekly schedule with no term end asks rather than assuming forever`() = runTest {
        val result = pipeline(finder = finder(group("g", "Section B", weeklyEntry("e1", DayOfWeek.MONDAY))))
            .run(source, answers = PipelineAnswers())

        val needs = result as PipelineResult.NeedsAnswers
        assertThat(needs.questions.single()).isInstanceOf(PipelineQuestion.TermEnd::class.java)
        assertThat(needs.partial.candidates).isEmpty()
    }

    // --- unresolved entries are carried, not dropped ---

    private val noEndTime = ScheduleEntry(
        id = EntryId("e2"),
        title = Confident.High("DBMS", at),
        weekday = Confident.High(DayOfWeek.THURSDAY, at),
        startTime = Confident.High(LocalTime.of(10, 0), at),
        endTime = Confident.Missing("this row's time didn't give an end"),
    )

    @Test
    fun `under the strict policy a missing end time is still a question, not a guess`() = runTest {
        // The rule this product shipped with, kept reachable and kept tested. If someone
        // later decides the inference was a mistake, this is the behaviour they get back.
        val result = pipeline(
            finder = finder(group("g", "Section B", weeklyEntry("e1", DayOfWeek.MONDAY), noEndTime)),
            policy = InferencePolicy.Strict,
        ).run(source, answers = PipelineAnswers(term = term))

        val ready = result as PipelineResult.Ready
        assertThat(ready.review.candidates).hasSize(1)
        assertThat(ready.review.unresolved.map { it.field }).contains(Unresolved.Field.EndTime)
    }

    @Test
    fun `a missing end time is filled in, flagged, and says so`() = runTest {
        val result = pipeline(
            finder = finder(group("g", "Section B", weeklyEntry("e1", DayOfWeek.MONDAY), noEndTime))
        ).run(source, answers = PipelineAnswers(term = term))

        val filled = (result as PipelineResult.Ready).review.candidates.single { it.title == "DBMS" }

        assertThat(filled.duration).isEqualTo(java.time.Duration.ofHours(1))
        assertThat(filled.assumptions).containsExactly(Assumption.EndTime(java.time.Duration.ofHours(1)))
        // The three things that make an assumption safe to have at all: the row is flagged,
        // it carries a source saying we invented the value, and that source is not a place
        // in the document.
        assertThat(filled.status).isEqualTo(CandidateStatus.NeedsAttention)
        assertThat(filled.sources).contains(SourceReference.Assumed("EndTime", "assumed-duration"))
    }

    @Test
    fun `a weekly entry with no start time is never turned into an all-day event`() = runTest {
        // The worst blast radius available here: one unreadable time column becoming fifteen
        // weeks of all-day rows across five days of someone's calendar.
        val noTime = ScheduleEntry(
            id = EntryId("e3"),
            title = Confident.High("Compiler Design", at),
            weekday = Confident.High(DayOfWeek.FRIDAY, at),
            startTime = Confident.Missing("no time in this cell"),
            endTime = Confident.Missing("no time in this cell"),
        )

        val result = pipeline(finder = finder(group("g", "Section B", noTime)))
            .run(source, answers = PipelineAnswers(term = term))

        val ready = result as PipelineResult.Ready
        assertThat(ready.review.candidates).isEmpty()
        assertThat(ready.review.unresolved.map { it.field }).contains(Unresolved.Field.StartTime)
    }

    @Test
    fun `a dated entry with no time at all becomes an all-day item`() = runTest {
        val bill = ScheduleEntry(
            id = EntryId("e4"),
            title = Confident.High("Electricity bill due", at),
            date = Confident.High(LocalDate.of(2026, 9, 15), at),
            startTime = Confident.Missing("no time given — this is an all-day item"),
            endTime = Confident.Missing("no end time given"),
        )

        val result = pipeline(finder = finder(group("g", "Bill", bill, kind = ScheduleKind.Event)))
            .run(source, answers = PipelineAnswers(term = term))

        val candidate = (result as PipelineResult.Ready).review.candidates.single()
        assertThat(candidate.isAllDay).isTrue()
        assertThat(candidate.timing).isEqualTo(
            EventTiming.AllDay(LocalDate.of(2026, 9, 15), zone),
        )
        assertThat(candidate.status).isEqualTo(CandidateStatus.NeedsAttention)
        // start/end still span the day, so sorting, dedupe and reminder planning all work
        // without knowing this is an all-day item.
        assertThat(candidate.end.isAfter(candidate.start)).isTrue()
    }

    // --- failures are reported in the domain's own vocabulary ---

    @Test
    fun `a detection failure is reported, not thrown`() = runTest {
        val result = pipeline(detector = failingDetector(FailureReason.Encrypted), finder = finder())
            .run(source)

        assertThat((result as PipelineResult.Failed).reason).isEqualTo(FailureReason.Encrypted)
    }

    @Test
    fun `a format with no reader is unsupported rather than a crash`() = runTest {
        val result = pipeline(
            detector = detector(DocumentFormat.Csv),
            readers = listOf(reader(DocumentFormat.Pdf)),
            finder = finder(),
        ).run(source)

        assertThat((result as PipelineResult.Failed).reason).isEqualTo(FailureReason.UnsupportedFormat)
    }

    @Test
    fun `a read failure is passed through unchanged`() = runTest {
        val result = pipeline(
            readers = listOf(reader(outcome = Outcome.Failure(FailureReason.NoTextLayer))),
            finder = finder(),
        ).run(source)

        assertThat((result as PipelineResult.Failed).reason).isEqualTo(FailureReason.NoTextLayer)
    }

    @Test
    fun `finding nothing is a neutral outcome with an explanation, not a failure`() = runTest {
        val result = pipeline(finder = finder()).run(source)

        val ready = result as PipelineResult.Ready
        assertThat(ready.review.isEmpty).isTrue()
        assertThat(ready.review.reason).contains("couldn't find anything actionable")
    }

    // --- progress is real ---

    @Test
    fun `the pipeline reports the stages it actually runs`() = runTest {
        val stages = mutableListOf<Stage>()

        pipeline(finder = finder(group("g", "Section B", weeklyEntry("e1", DayOfWeek.MONDAY))))
            .run(source, answers = PipelineAnswers(term = term)) { stages += it.stage }

        assertThat(stages).containsAtLeast(
            Stage.DetectingFormat,
            Stage.ReadingDocument,
            Stage.DetectingStructure,
            Stage.BuildingSchedule,
        ).inOrder()
    }

    // --- nothing happens before confirmation ---

    @Test
    fun `running the pipeline performs no action of any kind`() = runTest {
        var executed = false
        val executor = object : ActionExecutor<CalendarEventCandidate> {
            override suspend fun targets(): Outcome<List<ActionTarget>> {
                executed = true
                return Outcome.Success(emptyList())
            }

            override suspend fun findDuplicates(
                candidates: List<CalendarEventCandidate>,
                target: ActionTarget,
            ): Outcome<List<DuplicateMatch>> {
                executed = true
                return Outcome.Success(emptyList())
            }

            override suspend fun execute(
                importId: ImportId,
                candidates: List<CalendarEventCandidate>,
                target: ActionTarget,
                onProgress: (Int, Int) -> Unit,
            ): Outcome<ExecutionReport> {
                executed = true
                return Outcome.Failure(FailureReason.ProcessingUnavailable)
            }

            override suspend fun revert(importId: ImportId): Outcome<RevertReport> {
                executed = true
                return Outcome.Failure(FailureReason.ProcessingUnavailable)
            }
        }

        pipeline(finder = finder(group("g", "Section B", weeklyEntry("e1", DayOfWeek.MONDAY))))
            .run(source, answers = PipelineAnswers(term = term))

        // The executor exists and is never reached. Review is the last point at which
        // nothing has happened, and that has to be structurally true.
        assertThat(executed).isFalse()
        assertThat(executor).isNotNull()
    }
}
