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
     * Replaces a timetable wholesale, keeping what it replaced.
     *
     * One transaction, because a half-replaced timetable is worse than either version: the
     * weekly view would show Monday from the new one and Thursday from the old, and nothing
     * on screen would say so. The snapshot is taken inside that same transaction, so there is
     * no instant at which the old slots are gone and no record of them exists.
     *
     * This method does not decide *whether* replacing is right — by the time it runs the user
     * has been told how many slots it removes and has said yes. Callers must go through
     * `TimetableStore`, which is where that decision is made.
     */
    @Transaction
    suspend fun replace(timetable: TimetableEntity, slots: List<TimetableSlotEntity>) {
        snapshot(timetable.id, timetable.importId)
        deleteSlots(timetable.id)
        upsert(timetable)
        upsertSlots(slots)
    }

    /** Adds to a timetable without removing anything already in it. */
    @Transaction
    suspend fun merge(timetable: TimetableEntity, slots: List<TimetableSlotEntity>) {
        snapshot(timetable.id, timetable.importId)
        upsert(timetable)
        upsertSlots(slots)
    }

    /** Writes a timetable that has no predecessor. Nothing to snapshot, nothing to remove. */
    @Transaction
    suspend fun create(timetable: TimetableEntity, slots: List<TimetableSlotEntity>) {
        upsert(timetable)
        upsertSlots(slots)
    }

    // --- snapshots ---

    /**
     * Copies a timetable and its slots aside, replacing any previous snapshot for it.
     *
     * A no-op when the timetable does not exist yet, so callers do not have to special-case a
     * first import.
     */
    @Transaction
    suspend fun snapshot(timetableId: String, importId: String?) {
        val existing = byId(timetableId) ?: return
        clearSnapshot(timetableId)
        upsertSnapshot(
            TimetableSnapshotEntity(
                timetableId = existing.id,
                importId = importId,
                capturedAt = System.currentTimeMillis(),
                label = existing.label,
                termStartEpochDay = existing.termStartEpochDay,
                termEndEpochDay = existing.termEndEpochDay,
                zoneId = existing.zoneId,
                sourceName = existing.sourceName,
                sourceHash = existing.sourceHash,
                sourceIdentity = existing.sourceIdentity,
            )
        )
        upsertSlotSnapshots(
            slotsNow(timetableId).map {
                TimetableSlotSnapshotEntity(
                    id = it.id,
                    timetableId = it.timetableId,
                    entryId = it.entryId,
                    weekday = it.weekday,
                    startMinute = it.startMinute,
                    endMinute = it.endMinute,
                    title = it.title,
                    location = it.location,
                    customAppUri = it.customAppUri,
                    endAssumed = it.endAssumed,
                )
            }
        )
    }

    /**
     * Puts a timetable back the way the snapshot found it.
     *
     * @return true when something was restored. False means there was no snapshot, and the
     *   caller must say so rather than report a successful undo — the same honesty rule the
     *   calendar undo follows when events have already been deleted elsewhere.
     */
    @Transaction
    suspend fun restore(timetableId: String): Boolean {
        val snapshot = snapshotFor(timetableId) ?: return false
        val slots = slotSnapshotsFor(timetableId)

        deleteSlots(timetableId)
        upsert(
            TimetableEntity(
                id = snapshot.timetableId,
                label = snapshot.label,
                termStartEpochDay = snapshot.termStartEpochDay,
                termEndEpochDay = snapshot.termEndEpochDay,
                zoneId = snapshot.zoneId,
                sourceName = snapshot.sourceName,
                sourceHash = snapshot.sourceHash,
                sourceIdentity = snapshot.sourceIdentity,
                importId = snapshot.importId,
                createdAt = snapshot.capturedAt,
                updatedAt = System.currentTimeMillis(),
            )
        )
        upsertSlots(
            slots.map {
                TimetableSlotEntity(
                    id = it.id,
                    timetableId = it.timetableId,
                    entryId = it.entryId,
                    weekday = it.weekday,
                    startMinute = it.startMinute,
                    endMinute = it.endMinute,
                    title = it.title,
                    location = it.location,
                    customAppUri = it.customAppUri,
                    endAssumed = it.endAssumed,
                )
            }
        )
        clearSnapshot(timetableId)
        return true
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSnapshot(snapshot: TimetableSnapshotEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSlotSnapshots(slots: List<TimetableSlotSnapshotEntity>)

    @Query("SELECT * FROM timetable_snapshots WHERE timetableId = :timetableId")
    suspend fun snapshotFor(timetableId: String): TimetableSnapshotEntity?

    @Query("SELECT * FROM timetable_slot_snapshots WHERE timetableId = :timetableId")
    suspend fun slotSnapshotsFor(timetableId: String): List<TimetableSlotSnapshotEntity>

    @Transaction
    suspend fun clearSnapshot(timetableId: String) {
        deleteSlotSnapshots(timetableId)
        deleteSnapshot(timetableId)
    }

    @Query("DELETE FROM timetable_snapshots WHERE timetableId = :timetableId")
    suspend fun deleteSnapshot(timetableId: String)

    @Query("DELETE FROM timetable_slot_snapshots WHERE timetableId = :timetableId")
    suspend fun deleteSlotSnapshots(timetableId: String)

    // --- reads ---

    @Query("SELECT * FROM timetables ORDER BY updatedAt DESC")
    fun all(): Flow<List<TimetableEntity>>

    @Query("SELECT * FROM timetables ORDER BY updatedAt DESC LIMIT 1")
    suspend fun mostRecent(): TimetableEntity?

    @Query("SELECT * FROM timetables WHERE id = :timetableId")
    suspend fun byId(timetableId: String): TimetableEntity?

    @Query("SELECT * FROM timetable_slots WHERE timetableId = :timetableId ORDER BY weekday, startMinute")
    fun slots(timetableId: String): Flow<List<TimetableSlotEntity>>

    @Query("SELECT * FROM timetable_slots WHERE timetableId = :timetableId ORDER BY weekday, startMinute")
    suspend fun slotsNow(timetableId: String): List<TimetableSlotEntity>

    @Query("SELECT COUNT(*) FROM timetable_slots WHERE timetableId = :timetableId")
    suspend fun slotCount(timetableId: String): Int

    @Query("DELETE FROM timetable_slots WHERE timetableId = :timetableId")
    suspend fun deleteSlots(timetableId: String): Int

    @Query("DELETE FROM timetable_slots WHERE id = :slotId")
    suspend fun deleteSlot(slotId: String): Int

    @Query("DELETE FROM timetables WHERE id = :timetableId")
    suspend fun delete(timetableId: String): Int

    /**
     * The same document and the same schedule within it — a re-import, safe to update in place.
     *
     * `sourceIdentity IS NOT NULL` matters: rows written before the column existed carry null,
     * and null must read as "I don't know what document this came from", never as a match. A
     * null-matches-null identity would reintroduce exactly the silent overwrite this replaced.
     */
    @Query(
        "SELECT * FROM timetables WHERE sourceIdentity = :sourceIdentity " +
            "AND sourceIdentity IS NOT NULL LIMIT 1"
    )
    suspend fun bySourceIdentity(sourceIdentity: String): TimetableEntity?

    /**
     * Same display name. **Not an identity** — only a reason to ask the user.
     *
     * See [TimetableEntity.label].
     */
    @Query("SELECT * FROM timetables WHERE label = :label LIMIT 1")
    suspend fun byLabel(label: String): TimetableEntity?
}
