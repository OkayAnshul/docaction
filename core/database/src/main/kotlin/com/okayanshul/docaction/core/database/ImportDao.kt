package com.okayanshul.docaction.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * The record of what this app has done to the user's calendar.
 *
 * The table has existed since version 1 and nothing ever wrote to it, which left undo
 * reachable only from the screen that appears immediately after a write. That is the wrong
 * place for it to live alone: the realistic case is someone realising three days later that
 * they imported the wrong section, and by then the only route back was deleting 42 events by
 * hand.
 *
 * Metadata only — a filename, counts, timestamps, and the id undo needs. Never document
 * content and never extracted text (FR-7.2).
 */
@Dao
interface ImportDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun record(import: ImportEntity)

    @Query("SELECT * FROM imports ORDER BY startedAt DESC LIMIT :limit")
    fun recent(limit: Int = 50): Flow<List<ImportEntity>>

    @Query("SELECT * FROM imports WHERE id = :importId")
    suspend fun byId(importId: String): ImportEntity?

    /**
     * Marks an import as taken back.
     *
     * The row stays. Deleting it would lose the fact that the import happened at all, and a
     * user looking for "the one I undid last week" would find nothing — which reads as the
     * app having forgotten rather than as the undo having worked.
     */
    @Query("UPDATE imports SET state = :state, committedCount = 0 WHERE id = :importId")
    suspend fun markState(importId: String, state: String)

    /**
     * Forgets one entry.
     *
     * Deliberately does **not** touch the calendar. Removing a history row and removing the
     * events it created are two different intentions, and conflating them would delete a
     * term's classes because someone tidied a list (FR-7.3).
     */
    @Query("DELETE FROM imports WHERE id = :importId")
    suspend fun forget(importId: String)
}
