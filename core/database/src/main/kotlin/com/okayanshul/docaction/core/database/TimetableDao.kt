package com.okayanshul.docaction.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface TimetableDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(timetable: TimetableEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSlots(slots: List<TimetableSlotEntity>)

    /**
     * Replaces a timetable wholesale.
     *
     * One transaction, because a half-replaced timetable is worse than either version: the
     * weekly view would show Monday from the new one and Thursday from the old, and nothing
     * on screen would say so.
     */
    @Transaction
    suspend fun replace(timetable: TimetableEntity, slots: List<TimetableSlotEntity>) {
        deleteSlots(timetable.id)
        upsert(timetable)
        upsertSlots(slots)
    }

    @Query("SELECT * FROM timetables ORDER BY updatedAt DESC")
    fun all(): Flow<List<TimetableEntity>>

    @Query("SELECT * FROM timetables ORDER BY updatedAt DESC LIMIT 1")
    suspend fun mostRecent(): TimetableEntity?

    @Query("SELECT * FROM timetable_slots WHERE timetableId = :timetableId ORDER BY weekday, startMinute")
    fun slots(timetableId: String): Flow<List<TimetableSlotEntity>>

    @Query("SELECT * FROM timetable_slots WHERE timetableId = :timetableId ORDER BY weekday, startMinute")
    suspend fun slotsNow(timetableId: String): List<TimetableSlotEntity>

    @Query("DELETE FROM timetable_slots WHERE timetableId = :timetableId")
    suspend fun deleteSlots(timetableId: String): Int

    @Query("DELETE FROM timetables WHERE id = :timetableId")
    suspend fun delete(timetableId: String): Int

    /** Recognises a revision: same name, different document. */
    @Query("SELECT * FROM timetables WHERE label = :label LIMIT 1")
    suspend fun byLabel(label: String): TimetableEntity?
}
