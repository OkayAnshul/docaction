package com.okayanshul.docaction.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persistence is **metadata only**. Filenames, counts, timestamps and the identifiers needed
 * to undo an import — never document content, never extracted personal text.
 *
 * See docs/06-data-model.md § Persistence model, and docs/12-privacy-security.md.
 */

@Entity(tableName = "imports")
data class ImportEntity(
    @PrimaryKey val id: String,
    /** A filename, not content. */
    val displayName: String,
    val format: String,
    /** SHA-256 of the file, for re-import detection. Local only; never an analytics id. */
    val contentHash: String,
    val startedAt: Long,
    val completedAt: Long?,
    val state: String,
    val candidateCount: Int,
    val committedCount: Int,
    val failureReason: String?,
)

@Entity(
    tableName = "created_events",
    indices = [Index("importId")],
)
data class CreatedEventEntity(
    @PrimaryKey val id: String,
    val importId: String,
    /** Calendar Provider `_ID` — the fast path. */
    val calendarEventId: Long,
    val calendarId: Long,
    /** `docaction://import/{importId}/{entryId}` — the source of truth for undo (ADR-006). */
    val customAppUri: String,
    val createdAt: Long,
    val revokedAt: Long?,
)

@Entity(tableName = "resolved_conventions")
data class ResolvedConventionEntity(
    @PrimaryKey val key: String,
    val value: String,
    val updatedAt: Long,
)

/**
 * One rung of one occurrence, armed or waiting.
 *
 * Rows are keyed by the same provenance URI the calendar uses, so cancelling an import
 * cancels its reminders with a single predicate — the property that makes undo surgical
 * rather than a time-range guess.
 *
 * [title] is stored because a notification has to display something. It is the one place
 * extracted text is persisted, and the table is excluded from backup for that reason.
 */
@Entity(
    tableName = "scheduled_reminders",
    indices = [Index("importId"), Index("dueAtEpochMillis"), Index("customAppUri")],
)
data class ScheduledReminderEntity(
    @PrimaryKey val id: String,
    val importId: String,
    val entryId: String,
    val customAppUri: String,
    val title: String,
    val detail: String?,
    val kind: String,
    /** When this rung fires. */
    val dueAtEpochMillis: Long,
    /** When the thing itself happens, so the notification can say "in 15 minutes". */
    val eventAtEpochMillis: Long,
    val allDay: Boolean,
    /** False until the scheduler has actually armed an alarm for it. */
    val armed: Boolean,
    /** Set once delivered, so a re-arm after reboot does not fire it twice. */
    val firedAt: Long?,
    /** True when the alarm had to be inexact because exact alarms were not permitted. */
    val inexact: Boolean,
)
