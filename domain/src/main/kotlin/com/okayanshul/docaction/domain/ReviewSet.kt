package com.okayanshul.docaction.domain

/**
 * Everything the review screen needs, and the last point at which nothing has happened yet.
 *
 * The pipeline stops here and hands this to the UI. No calendar has been touched, no
 * reminder scheduled, nothing persisted beyond the in-progress record — so a user backing
 * out at this point leaves no trace. That is what makes "review before anything changes"
 * true rather than a slogan.
 */
data class ReviewSet(
    val source: DocumentSource,
    val format: DocumentFormat,
    /**
     * Every schedule found in the document. More than one means the user must choose —
     * a workbook with 335 stacked sections is a real case, and importing all of them
     * would be absurd.
     */
    val groups: List<ScheduleGroup>,
    /** The group currently selected, and the one [candidates] were built from. */
    val selectedGroup: GroupId?,
    val candidates: List<CalendarEventCandidate>,
    /**
     * Entries that could not become candidates, each with the specific question that
     * blocks them. These are the "2 items need your attention" — questions, not weak
     * guesses.
     */
    val unresolved: List<Unresolved>,
    /** Non-fatal notes worth surfacing, e.g. "page 4 was read from the image". */
    val issues: List<Issue> = emptyList(),
    /** Set when the whole document was read but nothing actionable was found. */
    val reason: String? = null,
) {
    val readyCount: Int get() = candidates.count { it.status == CandidateStatus.Ready }
    val attentionCount: Int get() = candidates.count { it.status == CandidateStatus.NeedsAttention }
    val isEmpty: Boolean get() = candidates.isEmpty() && unresolved.isEmpty()
    val needsChoice: Boolean get() = groups.size > 1 && selectedGroup == null

    val group: ScheduleGroup? get() = groups.firstOrNull { it.id == selectedGroup }
}

/**
 * What the pipeline needs answered before it can finish.
 *
 * Asked once, before review, and applied document-wide — a date-order question is one
 * question, not forty-two. See docs/09-confidence.md § Human in the loop.
 */
sealed interface PipelineQuestion {
    /** `03/04/2026` is 3 April or 4 March, and nothing in the document settles it. */
    data class DateOrder(
        val example: String,
        val dayFirst: java.time.LocalDate,
        val monthFirst: java.time.LocalDate,
    ) : PipelineQuestion

    /** A weekly schedule with no end date. Never assumed, never unbounded. */
    data class TermEnd(val scheduleLabel: String) : PipelineQuestion

    /** The document contains several schedules and only the user knows which is theirs. */
    data class WhichSchedule(val groups: List<ScheduleGroup>) : PipelineQuestion

    /**
     * Many rows had the same gap and we filled it the same way. Asked once, applied to all.
     *
     * Unlike the others this one is asked *after* candidates exist, because it is about what
     * they turned out to contain rather than about how to read the document. See
     * [AssumptionReview] for why the alternative — flagging 175 rows individually — makes the
     * review screen unreadable.
     */
    data class Assumed(val question: AssumedQuestion) : PipelineQuestion
}

/** The answers a user gives, fed back so the pipeline can finish without starting over. */
data class PipelineAnswers(
    val dateOrder: DateOrder? = null,
    val term: TermBounds? = null,
    val selectedGroup: GroupId? = null,
    val assumedYear: Int? = null,
)

/**
 * What the pipeline produces.
 *
 * [NeedsAnswers] is not a failure — it is the "guided when uncertain" half of the product
 * principle, and it carries whatever was already worked out so answering a question never
 * costs the user their other progress.
 */
sealed interface PipelineResult {
    data class Ready(val review: ReviewSet) : PipelineResult

    data class NeedsAnswers(
        val questions: List<PipelineQuestion>,
        val partial: ReviewSet,
    ) : PipelineResult

    data class Failed(val reason: FailureReason, val source: DocumentSource) : PipelineResult
}
