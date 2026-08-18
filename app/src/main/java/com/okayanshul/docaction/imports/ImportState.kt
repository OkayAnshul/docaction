package com.okayanshul.docaction.imports

import com.okayanshul.docaction.core.designsystem.StageLine
import com.okayanshul.docaction.core.settings.ReminderPreferences
import com.okayanshul.docaction.domain.ActionTarget
import com.okayanshul.docaction.domain.CalendarEventCandidate
import com.okayanshul.docaction.domain.CandidateId
import com.okayanshul.docaction.domain.CandidateStatus
import com.okayanshul.docaction.domain.FailureReason
import com.okayanshul.docaction.domain.ImportId
import com.okayanshul.docaction.domain.PipelineQuestion
import com.okayanshul.docaction.domain.ReviewSet

/**
 * Where the user is in an import.
 *
 * A single sealed state rather than a pile of booleans, so impossible combinations —
 * "writing" and "asking a question" at once — simply cannot be represented.
 */
sealed interface ImportState {

    data object Idle : ImportState

    data class Processing(
        val documentName: String,
        val stages: List<StageLine>,
        val detail: String?,
        val determinate: Float?,
    ) : ImportState

    /** The pipeline needs something only the user knows. Asked once, applied document-wide. */
    data class Asking(
        val documentName: String,
        val question: PipelineQuestion,
    ) : ImportState

    /**
     * What the editor is open on.
     *
     * [New] exists because [CandidateId] cannot express "an event that does not exist yet" —
     * and an event being typed genuinely has no identity until it is saved. Making that a
     * state rather than a nullable id is what lets one sheet serve both jobs without either
     * one pretending to be the other.
     */
    sealed interface Draft {
        data class Existing(val id: CandidateId) : Draft
        data object New : Draft
    }

    /** Everything found, nothing done. The last point at which backing out leaves no trace. */
    data class Reviewing(
        val review: ReviewSet,
        val selected: Set<CandidateId>,
        val showOnlyAttention: Boolean = false,
        /** The row whose edit sheet is open, or a blank event being written. */
        val editing: Draft? = null,
        /** The row whose "where did this come from?" is open, if any. */
        val viewingSource: CandidateId? = null,
        /** Null while the document is being re-read; the sheet shows progress until then. */
        val evidence: com.okayanshul.docaction.imports.source.SourceEvidence? = null,
    ) : ImportState {
        val chosen: List<CalendarEventCandidate>
            get() = review.candidates.filter { it.id in selected }

        val visible: List<CalendarEventCandidate>
            get() = if (showOnlyAttention) {
                review.candidates.filter { it.status == CandidateStatus.NeedsAttention }
            } else {
                review.candidates
            }

        val attention: Int get() = review.candidates.count { it.status == CandidateStatus.NeedsAttention }

        val editingCandidate: CalendarEventCandidate?
            get() = (editing as? Draft.Existing)
                ?.let { draft -> review.candidates.firstOrNull { it.id == draft.id } }

        val isCreating: Boolean get() = editing is Draft.New

        /**
         * True when this review holds nothing but hand-typed events.
         *
         * Changes what several screens should say: there is no document to name, no source to
         * point at, and "we found 3 events" would be a strange way to describe three the user
         * typed themselves.
         */
        val isManual: Boolean
            get() = review.format == com.okayanshul.docaction.domain.DocumentFormat.Manual

        val sourceCandidate: CalendarEventCandidate?
            get() = viewingSource?.let { id -> review.candidates.firstOrNull { it.id == id } }
    }

    /** The final consent step: which calendar, how many events, and whether to remind. */
    data class Confirming(
        val review: ReviewSet,
        val chosen: List<CalendarEventCandidate>,
        val targets: List<ActionTarget>,
        val target: ActionTarget?,
        val reminders: ReminderPreferences,
        /**
         * Events already in the chosen calendar that these would repeat.
         *
         * The list, not a count. "3 of these are already there" is a warning nobody can act
         * on; naming them is what lets someone tell a genuine clash from a coincidence — a
         * weekly lecture legitimately appears at the same hour as last term's.
         */
        val duplicates: List<com.okayanshul.docaction.domain.DuplicateMatch> = emptyList(),
        /** What to do about [duplicates]. Skipping is the default; see [DuplicateChoice]. */
        val duplicateChoice: DuplicateChoice = DuplicateChoice.Skip,
        val remindersEnabled: Boolean = true,
        val needsPermission: Boolean = false,
        /** The user said no. Distinct from "not asked yet" — it changes what we may say. */
        val denied: Boolean = false,
        /** Offered only for a weekly schedule; see TimetableStore. */
        val keepAsTimetable: Boolean = true,
        /**
         * A timetable already stored that keeping this one would land on top of.
         *
         * Surfaced here, before the write, rather than discovered afterwards — this is the
         * screen whose whole job is stating what is about to happen, and a schedule the user
         * would lose belongs in that statement.
         */
        val timetableCollision: com.okayanshul.docaction.timetable.TimetableCollision? = null,
        /** The user's answer to [timetableCollision]. Null until they give one. */
        val timetableResolution: com.okayanshul.docaction.timetable.TimetableResolution? = null,
    ) : ImportState {
        val recurring: Int get() = chosen.count { it.recurrence != null }

        /**
         * What will actually be written, once duplicates are accounted for.
         *
         * The count on the button comes from here rather than from [chosen], so the number
         * the user agrees to is the number that appears in their calendar.
         */
        val toWrite: List<CalendarEventCandidate>
            get() = when (duplicateChoice) {
                DuplicateChoice.Skip -> {
                    val skip = duplicates.map { it.candidateId }.toSet()
                    chosen.filterNot { it.id in skip }
                }

                DuplicateChoice.AddAnyway -> chosen
            }

        val skipped: Int get() = chosen.size - toWrite.size

        /** A timetable is the thing that repeats. Nothing else is worth a weekly view. */
        val canKeepAsTimetable: Boolean get() = recurring > 0

        /**
         * True while a stored timetable's fate is undecided.
         *
         * Blocks the write. Not because writing the calendar would be unsafe — it is the same
         * either way — but because letting someone press "Add 42 events" with an unanswered
         * question on screen would resolve it by default, and defaulting is exactly what
         * destroyed timetables before.
         */
        val awaitingTimetableDecision: Boolean
            get() = keepAsTimetable && canKeepAsTimetable &&
                timetableCollision != null && timetableResolution == null

        val canWrite: Boolean
            get() = target != null && toWrite.isNotEmpty() && !needsPermission &&
                !awaitingTimetableDecision
    }

    /**
     * What to do about events that are already in the calendar.
     *
     * Skipping is the default, and it is the one place in this flow with one. "It added 60
     * duplicate events and I deleted them one by one" is the most common complaint across the
     * entire calendar-import category, so the safe answer is the assumed one — and unlike most
     * defaults this one is visible, reversible in a tap, and cannot lose anything: the events
     * it declines to write are already there.
     */
    enum class DuplicateChoice { Skip, AddAnyway }

    /**
     * "Show us the part you need."
     *
     * Reached only after the engine has said it could not find a schedule, or found the
     * wrong one. It is a way to answer that, not a step in the normal flow — a product that
     * routinely asks the user to draw a box around their own timetable has failed at its
     * actual job.
     */
    data class Rescuing(
        val documentName: String,
        val pageCount: Int,
        val page: Int,
        /** Null while the page renders. */
        val image: android.graphics.Bitmap?,
        val crop: com.okayanshul.docaction.domain.BoundingBox? = null,
    ) : ImportState {
        val isPaged: Boolean get() = pageCount > 1
    }

    data class Writing(val written: Int, val total: Int) : ImportState

    data class Finished(
        val importId: ImportId,
        val written: Int,
        val failed: Int,
        val calendarLabel: String,
        val remindersOn: Boolean,
    ) : ImportState

    /** Something went wrong, said in the user's language with a way forward. */
    data class Failed(
        val reason: FailureReason,
        val documentName: String,
        /** Changes what "we found nothing" means, and therefore what to say about it. */
        val afterCrop: Boolean = false,
        /** The schedule the user picked, when the emptiness is that one section's. */
        val emptySchedule: String? = null,
    ) : ImportState
}
