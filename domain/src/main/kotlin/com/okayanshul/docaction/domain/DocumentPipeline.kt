package com.okayanshul.docaction.domain

import java.time.ZoneId

/**
 * Turns extracted content into schedules. Implemented in `:extraction`, which knows the
 * grid and prose engines; the pipeline itself must stay ignorant of them so `:domain`
 * keeps no dependency on extraction internals.
 */
/**
 * What a finder produced, and what it could not decide without help.
 *
 * The second half is the part that was missing. Extraction has always *detected* an
 * ambiguous date — `05/10/2026` is 5 October or 10 May and nothing in a train ticket settles
 * it — and then dropped the entry, so the document produced nothing and the user was never
 * told why. "Guided when uncertain" needs a channel for the guidance to travel down.
 */
data class FoundSchedules(
    val groups: List<ScheduleGroup>,
    val questions: List<PipelineQuestion> = emptyList(),
)

interface ScheduleFinder {
    /**
     * @param content positioned text from any reader — the pipeline does not care whether
     *   it came from a PDF text layer or from OCR.
     * @param hints what the user told us to look at. A region they drew themselves is an
     *   assertion that this *is* their schedule, and the engine is entitled to be less
     *   suspicious of what it finds inside one.
     * @return every schedule found. More than one means the user must choose.
     */
    suspend fun find(
        content: DocumentContent,
        label: String,
        answers: PipelineAnswers,
        hints: ExtractionHints = ExtractionHints(),
    ): FoundSchedules
}

/**
 * A format whose structure is **given rather than inferred**, and which therefore skips
 * positioned text entirely.
 *
 * Spreadsheets are the case: a cell's row and column are exact, so running them through
 * geometric table detection throws away information and then tries to recover it. Doing so
 * once cost three period columns on a real timetable
 * (ADR-011), which is why this is a separate port rather than a reader that fakes
 * coordinates.
 */
interface ScheduleSource {
    fun supports(format: DocumentFormat): Boolean

    /**
     * @param answers what the user has already told us. [PipelineAnswers.selectedGroup] in
     *   particular: a source that lists hundreds of schedules cheaply on the first pass
     *   needs to know which one to build in full on the second, and without it the pipeline
     *   re-reads the same placeholders and finds them empty — which is exactly what a real
     *   335-section workbook did.
     *
     * Returns [FoundSchedules] rather than a bare list so this port can *ask*, exactly as
     * [ScheduleFinder] can. A spreadsheet holding `05/10/2026` faces the same ambiguity a PDF
     * does, and before this the two paths disagreed about what to do with it: the finder
     * raised a question and the source silently produced nothing.
     */
    suspend fun read(
        source: DocumentSource,
        hints: ExtractionHints,
        answers: PipelineAnswers,
        onProgress: (StageProgress) -> Unit,
    ): Outcome<FoundSchedules>
}

/**
 * The production path from "the user gave us a file" to "here is what we found".
 *
 * This **coordinates**; it does not extract. Every stage below is an existing, tested
 * component, and the sequence is the one already proven across the 41-document corpus by
 * `CorpusDiagnostic` — promoted here rather than reinvented.
 *
 * ```
 * detect → read → find schedules → build candidates → ReviewSet
 * ```
 *
 * Nothing outside the app changes anywhere in this class. The first side effect in the
 * whole product happens after the user confirms, in the action executors.
 */
class DocumentPipeline(
    private val detector: FormatDetector,
    private val readers: List<DocumentReader>,
    private val schedules: ScheduleFinder,
    private val zone: ZoneId,
    /** Formats that produce schedules directly, without positioned text. See [ScheduleSource]. */
    private val scheduleSources: List<ScheduleSource> = emptyList(),
    /**
     * How much the pipeline is allowed to fill in. Constructor parameters rather than
     * constants so the strict behaviour stays reachable and provable — the corpus gate runs
     * both modes, and "we could turn this off and the old rule still holds" is the only
     * thing that makes the relaxation reviewable.
     */
    private val policy: InferencePolicy = InferencePolicy.Default,
) {

    suspend fun run(
        source: DocumentSource,
        hints: ExtractionHints = ExtractionHints(),
        answers: PipelineAnswers = PipelineAnswers(),
        onProgress: (StageProgress) -> Unit = {},
    ): PipelineResult {
        onProgress(StageProgress(Stage.DetectingFormat))

        val format = when (val detected = detector.detect(source)) {
            is Outcome.Success -> detected.value
            is Outcome.Partial -> detected.value
            is Outcome.Failure -> return PipelineResult.Failed(detected.reason, source)
        }

        onProgress(StageProgress(Stage.ReadingDocument))

        // A format whose grid is exact bypasses positioned text entirely (ADR-011).
        val direct = scheduleSources.firstOrNull { it.supports(format) }
        val issues: List<Issue>
        val groups: List<ScheduleGroup>

        if (direct != null) {
            val found = when (val read = direct.read(source, hints, answers, onProgress)) {
                is Outcome.Success -> read.value
                is Outcome.Partial -> read.value
                is Outcome.Failure -> return PipelineResult.Failed(read.reason, source)
            }
            groups = found.groups
            issues = emptyList()

            // Same rule as the finder path below, and deliberately the same words: a question
            // earns attention only when answering it unblocks something.
            if (groups.isEmpty() && found.questions.isNotEmpty()) {
                return PipelineResult.NeedsAnswers(
                    questions = found.questions,
                    partial = ReviewSet(
                        source = source,
                        format = format,
                        groups = emptyList(),
                        selectedGroup = null,
                        candidates = emptyList(),
                        unresolved = emptyList(),
                        issues = issues,
                    ),
                )
            }
        } else {
            val reader = readers.firstOrNull { it.supports(format) }
                ?: return PipelineResult.Failed(FailureReason.UnsupportedFormat, source)

            val content = when (val read = reader.read(source, hints, onProgress)) {
                is Outcome.Success -> read.value
                is Outcome.Partial -> read.value
                is Outcome.Failure -> return PipelineResult.Failed(read.reason, source)
            }
            issues = content.issues

            onProgress(StageProgress(Stage.DetectingStructure))
            val found = schedules.find(content, source.displayName, answers, hints)
            groups = found.groups

            // A question only earns the user's attention if answering it unblocks something.
            // Asked when the document yielded nothing: then the ambiguity *is* why.
            if (groups.isEmpty() && found.questions.isNotEmpty()) {
                return PipelineResult.NeedsAnswers(
                    questions = found.questions,
                    partial = ReviewSet(
                        source = source,
                        format = format,
                        groups = emptyList(),
                        selectedGroup = null,
                        candidates = emptyList(),
                        unresolved = emptyList(),
                        issues = issues,
                    ),
                )
            }
        }

        if (groups.isEmpty()) {
            return PipelineResult.Ready(
                ReviewSet(
                    source = source,
                    format = format,
                    groups = emptyList(),
                    selectedGroup = null,
                    candidates = emptyList(),
                    unresolved = emptyList(),
                    issues = issues,
                    reason = "We couldn't find anything actionable in this document.",
                )
            )
        }

        // More than one schedule is a question, not a guess. Importing all of them, or
        // picking one silently, are both wrong.
        val selected = answers.selectedGroup?.let { id -> groups.firstOrNull { it.id == id } }

        // Only offer choices that can actually lead somewhere. A question whose answers all
        // dead-end costs the user attention and returns nothing for it, which is worse than
        // never asking: they have been made to participate in their own disappointment.
        val offerable = groups.filter { it.entries.isNotEmpty() || (it.estimatedSize ?: 0) > 0 }

        if (selected == null && offerable.size > 1) {
            return PipelineResult.NeedsAnswers(
                questions = listOf(PipelineQuestion.WhichSchedule(offerable)),
                partial = ReviewSet(
                    source = source,
                    format = format,
                    groups = offerable,
                    selectedGroup = null,
                    candidates = emptyList(),
                    unresolved = emptyList(),
                    issues = issues,
                ),
            )
        }

        val group = selected
            ?: offerable.singleOrNull()
            ?: offerable.firstOrNull()
            ?: return PipelineResult.Ready(
                ReviewSet(
                    source = source,
                    format = format,
                    groups = emptyList(),
                    selectedGroup = null,
                    candidates = emptyList(),
                    unresolved = emptyList(),
                    issues = issues,
                    reason = "We couldn't find anything actionable in this document.",
                )
            )

        onProgress(StageProgress(Stage.BuildingSchedule))
        val built = buildCandidates(group, answers)

        // A weekly schedule with no end date cannot be bounded, and an unbounded recurrence
        // is never written. Ask rather than assume "forever".
        if (built.needsTerm && answers.term == null) {
            return PipelineResult.NeedsAnswers(
                questions = listOf(PipelineQuestion.TermEnd(group.label)),
                partial = ReviewSet(
                    source = source,
                    format = format,
                    groups = groups,
                    selectedGroup = group.id,
                    candidates = built.candidates,
                    unresolved = built.unresolved,
                    issues = issues,
                ),
            )
        }

        return PipelineResult.Ready(
            ReviewSet(
                source = source,
                format = format,
                groups = groups,
                selectedGroup = group.id,
                candidates = built.candidates,
                unresolved = built.unresolved,
                issues = issues,
                reason = if (built.candidates.isEmpty() && built.unresolved.isEmpty()) {
                    "We couldn't find anything actionable in this document."
                } else {
                    null
                },
            )
        )
    }

    private data class Built(
        val candidates: List<CalendarEventCandidate>,
        val unresolved: List<Unresolved>,
        val needsTerm: Boolean,
    )

    /**
     * Every entry goes through [CalendarEventCandidate.from] — the single choke point that
     * refuses to build an action from a `Missing` field. Rejections become questions for
     * the review screen rather than being dropped or guessed at.
     */
    private fun buildCandidates(group: ScheduleGroup, answers: PipelineAnswers): Built {
        // An assumed duration applies to anything with a start and no end — a class whose
        // time column lists only starts has the same problem as an interview letter, and the
        // row is flagged either way. The all-day path is the one that must never apply to a
        // recurring entry, and `from` guards that directly rather than trusting the group's
        // classification to be right.
        // Applied everywhere now. It was restricted to declared table structures for a
        // while, because prose titles were unreliable enough that filling their gaps
        // produced rows like "INR /2" and "Su 7142128" — and flagging wreckage does not save
        // it, it just teaches people to ignore the flag. The prose reader now holds titles
        // to a standard (a name, not a sentence, not a figure), so the restriction has done
        // its job and the corpus is the check on whether it stays done.
        val duration = policy.assumedDuration
        val allDay = policy.allowAllDay
        val candidates = mutableListOf<CalendarEventCandidate>()
        val unresolved = mutableListOf<Unresolved>()
        var needsTerm = false

        group.entries.forEach { entry ->
            val result = CalendarEventCandidate.from(
                entry = entry,
                zone = zone,
                term = answers.term,
                reminderKind = group.reminderKind,
                assumedDuration = duration,
                allowAllDay = allDay,
            )
            when (result) {
                is CalendarEventCandidate.Result.Accepted -> {
                    candidates += result.candidate
                    unresolved += result.unresolved
                }

                is CalendarEventCandidate.Result.Rejected -> {
                    if (result.unresolved.field == Unresolved.Field.Recurrence && answers.term == null) {
                        needsTerm = true
                    }
                    unresolved += result.unresolved
                }
            }
        }

        return Built(candidates, unresolved.distinctBy { it.entryId to it.field }, needsTerm)
    }
}

/**
 * How much the engine may fill in when a document is silent.
 *
 * Kept as data rather than as constants because the reversal it represents deserves to be
 * switchable: [Strict] is the rule this product shipped with — invent nothing, ever — and it
 * is still exercised by the tests that were written for it. [Default] is what users get, and
 * every gap it fills arrives as an [Assumption] on a flagged row.
 */
data class InferencePolicy(
    /** Given to a one-off event that states a start and no end. Null rejects instead. */
    val assumedDuration: java.time.Duration?,
    /** Whether a date with no clock time becomes an all-day item rather than a rejection. */
    val allowAllDay: Boolean,
) {
    companion object {
        /**
         * One hour, and all-day items allowed.
         *
         * An hour because it is the length a person would pencil in for an appointment they
         * knew nothing else about, and because being wrong by an hour on a visible, editable
         * row is a far smaller harm than silently dropping the appointment — which is what
         * the previous rule did to 26 of 42 real documents.
         */
        val Default = InferencePolicy(assumedDuration = java.time.Duration.ofHours(1), allowAllDay = true)

        /**
         * Invent nothing, anywhere. The rule this product shipped with.
         *
         * Kept reachable and kept tested — `DocumentPipelineTest` and
         * `CalendarEventCandidateTest` both still pin it — so that if the inference turns
         * out to be a mistake, restoring the old behaviour is one argument rather than an
         * archaeology exercise.
         */
        val Strict = InferencePolicy(assumedDuration = null, allowAllDay = false)
    }
}
