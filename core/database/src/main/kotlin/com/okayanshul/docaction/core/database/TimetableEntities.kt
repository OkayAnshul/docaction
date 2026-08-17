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
@Entity(tableName = "timetables")
data class TimetableEntity(
    @PrimaryKey val id: String,
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
