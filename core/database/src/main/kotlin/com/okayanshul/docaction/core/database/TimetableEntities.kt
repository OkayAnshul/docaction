package com.okayanshul.docaction.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A timetable the user chose to keep.
 *
 * Kept separately from the calendar rather than derived from it, because the two answer
 * different questions. The calendar knows what is happening on Thursday; this knows what the
 * user's week *is* — which is what a weekly view shows, and what a re-import has to diff
 * against when the department sends out a revision.
 */
@Entity(tableName = "timetables", indices = [Index("sourceIdentity")])
data class TimetableEntity(
    @PrimaryKey val id: String,
    /**
     * What the user calls this. **Purely a display name** — it is never an identity.
     *
     * It used to be one, and that was a data-loss bug: institutions reuse filenames
     * ("timetable.pdf"), so importing an unrelated schedule that happened to produce the same
     * label silently replaced the previous one. The user may rename this freely without
     * forking or merging anything.
     */
    val label: String,
    val termStartEpochDay: Long,
    val termEndEpochDay: Long,
    val zoneId: String,
    val sourceName: String?,
    /**
     * A digest of the document this came from.
     *
     * Not for integrity — for recognition. When a revised timetable arrives, matching the
     * label with a *different* hash is what lets the app say "this looks like an update to
     * your timetable" rather than silently creating a second one.
     */
    val sourceHash: String?,
    /**
     * The stable identity: this document's content and the schedule chosen within it.
     *
     * Re-importing the *same* document updates in place, silently and safely, because this
     * matches. Anything else — a revision, or an unrelated timetable that shares a name — does
     * not match, and the user is asked rather than overwritten. Null on rows migrated from
     * before this column existed, which is treated as "unknown", never as "matches".
     */
    val sourceIdentity: String?,
    /** Links these slots to the calendar rows written for them, so undo stays surgical. */
    val importId: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * One recurring slot: a person, a class, a shift.
 *
 * [startMinute] and [endMinute] are minutes from midnight, not instants. A weekly slot has
 * no date — "Monday at nine" is the whole of what the document said — and storing a fake one
 * would be the same class of invention this engine refuses everywhere else. The date only
 * appears when a slot becomes a calendar event, and the term supplies it.
 */
@Entity(
    tableName = "timetable_slots",
    indices = [Index("timetableId"), Index("weekday")],
)
data class TimetableSlotEntity(
    @PrimaryKey val id: String,
    val timetableId: String,
    val entryId: String,
    /** `DayOfWeek.value`: Monday is 1. */
    val weekday: Int,
    val startMinute: Int,
    val endMinute: Int,
    val title: String,
    val location: String?,
    /** The calendar row this slot wrote, for a surgical update on re-sync. */
    val customAppUri: String?,
    /**
     * True when we supplied the end time rather than reading it.
     *
     * Survives into storage on purpose. An assumption that evaporates at the storage
     * boundary is an assumption the user can no longer see, and the weekly view has the same
     * duty as the review screen to say which parts came from us.
     */
    val endAssumed: Boolean,
)

/**
 * What a timetable looked like immediately before something destructive happened to it.
 *
 * Taken in the same transaction as the change, so there is no window in which the old version
 * is gone and the new one has not landed. A replace is offered only with an explicit
 * confirmation that states how many slots it removes — this is what makes that promise
 * recoverable rather than merely well-worded.
 *
 * One snapshot per timetable: the most recent change is the one a user wants back, and
 * keeping a full history of a schedule nobody asked us to version would be storing personal
 * data past its purpose (FR-7.2).
 */
@Entity(tableName = "timetable_snapshots")
data class TimetableSnapshotEntity(
    @PrimaryKey val timetableId: String,
    /** The import that caused the change, so undoing an import can undo this alongside it. */
    val importId: String?,
    val capturedAt: Long,
    val label: String,
    val termStartEpochDay: Long,
    val termEndEpochDay: Long,
    val zoneId: String,
    val sourceName: String?,
    val sourceHash: String?,
    val sourceIdentity: String?,
)

/** A slot as it was when its timetable was snapshotted. Same shape as [TimetableSlotEntity]. */
@Entity(tableName = "timetable_slot_snapshots", indices = [Index("timetableId")])
data class TimetableSlotSnapshotEntity(
    @PrimaryKey val id: String,
    val timetableId: String,
    val entryId: String,
    val weekday: Int,
    val startMinute: Int,
    val endMinute: Int,
    val title: String,
    val location: String?,
    val customAppUri: String?,
    val endAssumed: Boolean,
)
