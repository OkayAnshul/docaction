package com.okayanshul.docaction.actions.reminder

import com.okayanshul.docaction.core.database.ScheduledReminderEntity
import com.okayanshul.docaction.domain.CalendarEventCandidate
import com.okayanshul.docaction.domain.ImportId
import com.okayanshul.docaction.domain.ReminderCandidate
import com.okayanshul.docaction.domain.ReminderKind
import com.okayanshul.docaction.domain.ReminderLadder
import java.time.Duration
import java.time.Instant

/**
 * Turns candidates into the individual reminder rows the scheduler arms.
 *
 * Deliberately window-bounded and pure: given a horizon it produces the rows falling inside
 * it and nothing more, so a fifteen-week timetable never becomes fifteen weeks of rows.
 * Re-running it later produces the next slice, and rows are keyed deterministically so a
 * re-run overwrites rather than duplicates.
 */
class ReminderPlanner(private val horizon: Duration = Duration.ofDays(7)) {

    /** A one-off reminder — a deadline, an appointment, a booking. */
    fun plan(
        importId: ImportId,
        candidate: ReminderCandidate,
        from: Instant = Instant.now(),
    ): List<ScheduledReminderEntity> {
        val until = from.plus(horizon)
        val eventAt = candidate.dueAt.toInstant()

        return candidate.ladder.dueWithin(eventAt, from, until).map { due ->
            row(
                importId = importId,
                entryId = candidate.entryId.value,
                title = candidate.title,
                detail = null,
                kind = candidate.kind,
                due = due,
                eventAt = eventAt,
                allDay = candidate.allDay,
            )
        }
    }

    /**
     * A recurring class. Occurrences are expanded across the horizon only — the whole point
     * of the rolling window — and each occurrence contributes its own ladder rungs.
     */
    fun plan(
        importId: ImportId,
        candidate: CalendarEventCandidate,
        ladder: ReminderLadder,
        from: Instant = Instant.now(),
    ): List<ScheduledReminderEntity> {
        val until = from.plus(horizon)
        val recurrence = candidate.recurrence

        val occurrences: List<Instant> = if (recurrence == null) {
            listOf(candidate.start.toInstant()).filter { it.isAfter(from) }
        } else {
            recurrence.occurrencesBetween(
                from = from,
                // Reach far enough ahead that a rung a day before an occurrence just
                // outside the horizon is still planned.
                to = until.plus(ladder.offsets.maxOrNull() ?: Duration.ZERO),
                at = candidate.start.toLocalTime(),
                zone = candidate.start.zone,
            ).map { it.toInstant() }
        }

        return occurrences.flatMap { eventAt ->
            ladder.dueWithin(eventAt, from, until).map { due ->
                row(
                    importId = importId,
                    entryId = candidate.entryId.value,
                    title = candidate.title,
                    detail = candidate.location,
                    kind = ReminderKind.Class,
                    due = due,
                    eventAt = eventAt,
                    allDay = candidate.isAllDay,
                )
            }
        }
    }

    private fun row(
        importId: ImportId,
        entryId: String,
        title: String,
        detail: String?,
        kind: ReminderKind,
        due: Instant,
        eventAt: Instant,
        allDay: Boolean,
    ) = ScheduledReminderEntity(
        // Deterministic: re-planning the same rung overwrites it instead of duplicating.
        id = "${importId.value}/$entryId/${eventAt.toEpochMilli()}/${due.toEpochMilli()}",
        importId = importId.value,
        entryId = entryId,
        customAppUri = "docaction://import/${importId.value}/$entryId",
        title = title,
        detail = detail,
        kind = kind.name,
        dueAtEpochMillis = due.toEpochMilli(),
        eventAtEpochMillis = eventAt.toEpochMilli(),
        allDay = allDay,
        armed = false,
        firedAt = null,
        inexact = false,
    )
}
