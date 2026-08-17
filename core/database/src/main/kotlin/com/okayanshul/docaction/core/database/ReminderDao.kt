package com.okayanshul.docaction.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ReminderDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(reminders: List<ScheduledReminderEntity>)

    /**
     * The rungs the scheduler should arm next: not yet fired, inside the rolling window.
     *
     * Bounded by [limit] as well as by time — a pathological import must not be able to
     * arm an unbounded number of alarms in one pass.
     */
    @Query(
        """
        SELECT * FROM scheduled_reminders
        WHERE firedAt IS NULL
          AND dueAtEpochMillis >= :from
          AND dueAtEpochMillis < :to
        ORDER BY dueAtEpochMillis ASC
        LIMIT :limit
        """
    )
    suspend fun dueBetween(from: Long, to: Long, limit: Int): List<ScheduledReminderEntity>

    @Query("SELECT * FROM scheduled_reminders WHERE id = :id")
    suspend fun byId(id: String): ScheduledReminderEntity?

    @Query("UPDATE scheduled_reminders SET armed = :armed, inexact = :inexact WHERE id = :id")
    suspend fun markArmed(id: String, armed: Boolean, inexact: Boolean)

    @Query("UPDATE scheduled_reminders SET firedAt = :firedAt WHERE id = :id")
    suspend fun markFired(id: String, firedAt: Long)

    /**
     * Cancels one import's reminders and nothing else. The predicate is the provenance URI
     * prefix, never a time range — the same rule that keeps calendar undo from deleting a
     * user's own events.
     */
    @Query("DELETE FROM scheduled_reminders WHERE importId = :importId")
    suspend fun deleteForImport(importId: String): Int

    @Query("SELECT COUNT(*) FROM scheduled_reminders WHERE importId = :importId")
    suspend fun countForImport(importId: String): Int

    @Query("SELECT * FROM scheduled_reminders WHERE firedAt IS NULL ORDER BY dueAtEpochMillis ASC LIMIT :limit")
    suspend fun pending(limit: Int): List<ScheduledReminderEntity>

    /** Housekeeping: rungs whose moment has passed unfired are dead weight. */
    @Query("DELETE FROM scheduled_reminders WHERE dueAtEpochMillis < :before")
    suspend fun deleteExpired(before: Long): Int
}
