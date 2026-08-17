package com.okayanshul.docaction.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Local mirror of what we wrote to the calendar.
 *
 * This is a **fast path, not the source of truth**. The authority is the provenance stamped
 * into the calendar rows themselves (ADR-006), because that survives an app data wipe, a
 * reinstall and a backup restore — all of which would otherwise leave undo unable to
 * identify its own events, and a time-range fallback would delete the user's.
 */
@Dao
interface CreatedEventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun record(events: List<CreatedEventEntity>)

    @Query("SELECT * FROM created_events WHERE importId = :importId AND revokedAt IS NULL")
    suspend fun forImport(importId: String): List<CreatedEventEntity>

    @Query("UPDATE created_events SET revokedAt = :at WHERE importId = :importId")
    suspend fun markRevoked(importId: String, at: Long)

    @Query("SELECT COUNT(*) FROM created_events WHERE importId = :importId AND revokedAt IS NULL")
    suspend fun countForImport(importId: String): Int

    /** Every import that still has live events — the History screen's list. */
    @Query("SELECT DISTINCT importId FROM created_events WHERE revokedAt IS NULL")
    suspend fun activeImports(): List<String>
}
