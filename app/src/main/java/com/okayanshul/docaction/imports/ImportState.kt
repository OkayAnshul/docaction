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

    /** Everything found, nothing done. The last point at which backing out leaves no trace. */
    data class Reviewing(
        val review: ReviewSet,
        val selected: Set<CandidateId>,
        val showOnlyAttention: Boolean = false,
        /** The row whose edit sheet is open, if any. */
        val editing: CandidateId? = null,
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
            get() = editing?.let { id -> review.candidates.firstOrNull { it.id == id } }

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
        val duplicates: Int = 0,
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
            get() = target != null && chosen.isNotEmpty() && !needsPermission &&
                !awaitingTimetableDecision
    }

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
