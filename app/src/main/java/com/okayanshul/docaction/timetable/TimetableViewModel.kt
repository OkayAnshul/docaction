package com.okayanshul.docaction.timetable

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.okayanshul.docaction.actions.calendar.CalendarEventExecutor
import com.okayanshul.docaction.actions.calendar.CalendarTarget
import com.okayanshul.docaction.actions.calendar.CalendarTargets
import com.okayanshul.docaction.core.database.Databases
import com.okayanshul.docaction.core.database.TimetableEntity
import com.okayanshul.docaction.core.database.TimetableSlotEntity
import com.okayanshul.docaction.domain.CalendarEventCandidate
import com.okayanshul.docaction.domain.ManualEvent
import com.okayanshul.docaction.domain.Outcome
import com.okayanshul.docaction.domain.TermBounds
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A slot being added or corrected, and what happened to the calendar when it was saved. */
sealed interface SlotEdit {
    data object None : SlotEdit
    data class Editing(val slot: TimetableSlotEntity) : SlotEdit
    data object Creating : SlotEdit
}

/**
 * Something the user should know about, said once and dismissed.
 *
 * Exists because a slot edit can half-succeed: the stored week changes and the calendar does
 * not, because permission was withdrawn or the event was deleted elsewhere. Saying nothing
 * would leave the app showing one thing and the phone showing another.
 */
data class TimetableNotice(val message: String)

/**
 * The user's saved week, and the ability to change it.
 *
 * Editing writes to two places — the stored timetable and the calendar rows it created — and
 * they can disagree. The calendar is the one the user actually looks at, so a failure to
 * update it is surfaced rather than swallowed; the stored slot is still changed, because
 * refusing the edit would leave someone unable to fix their own timetable because of a
 * permission they can grant later.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimetableViewModel(application: Application) : AndroidViewModel(application) {

    private val store = TimetableStore(application)
    private val zone: ZoneId = ZoneId.systemDefault()

    private val executor by lazy {
        CalendarEventExecutor(
            context = getApplication(),
            createdEvents = Databases.createdEvents(getApplication()),
        )
    }

    private val _current = MutableStateFlow<TimetableEntity?>(null)
    val current: StateFlow<TimetableEntity?> = _current.asStateFlow()

    val label: StateFlow<String?> = MutableStateFlow<String?>(null).also { flow ->
        viewModelScope.launch { current.collect { flow.value = it?.label } }
    }.asStateFlow()

    private val _editing = MutableStateFlow<SlotEdit>(SlotEdit.None)
    val editing: StateFlow<SlotEdit> = _editing.asStateFlow()

    private val _notice = MutableStateFlow<TimetableNotice?>(null)
    val notice: StateFlow<TimetableNotice?> = _notice.asStateFlow()

    /** Every timetable the user holds, most recently touched first. */
    val timetables: StateFlow<List<TimetableEntity>> = store.timetables()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The selected timetable's slots, following the database.
     *
     * A flow rather than a one-off read so that finishing an import updates the weekly view
     * without anything having to remember to refresh it — which is the kind of coupling that
     * works until the day someone adds a second way to change a timetable.
     */
    val slots: StateFlow<List<TimetableSlotEntity>> = store.timetables()
        .flatMapLatest { all ->
            val chosen = all.firstOrNull { it.id == _current.value?.id } ?: all.firstOrNull()
            _current.value = chosen
            chosen?.let { store.slots(it.id) } ?: emptyFlow()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** True when there is a week worth showing an entry point for. */
    val hasTimetable: StateFlow<Boolean> = store.timetables()
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun show(timetableId: String) {
        viewModelScope.launch { _current.value = store.byId(timetableId) }
    }

    // --- editing ---

    fun beginEdit(slot: TimetableSlotEntity) { _editing.value = SlotEdit.Editing(slot) }

    fun beginCreate() { _editing.value = SlotEdit.Creating }

    fun cancelEdit() { _editing.value = SlotEdit.None }

    fun dismissNotice() { _notice.value = null }

    fun rename(label: String) {
        val id = _current.value?.id ?: return
        viewModelScope.launch {
            store.rename(id, label)
            _current.value = store.byId(id)
        }
    }

    /**
     * Saves a slot and pushes the change to the calendar row it owns.
     *
     * A brand-new slot has no calendar row yet. It is stored, and the user is told plainly
     * that it is not in their calendar — inventing an import id to write one would create an
     * event that undo could never find, and a calendar entry nothing can remove is worse than
     * one that was never created.
     */
    fun saveSlot(
        weekday: DayOfWeek,
        start: LocalTime,
        end: LocalTime,
        title: String,
        location: String?,
        replacing: TimetableSlotEntity?,
    ) {
        val timetable = _current.value ?: return
        val clean = title.trim().ifBlank { return }
        if (start == end) return

        viewModelScope.launch {
            val entryId = replacing?.entryId ?: "manual-${UUID.randomUUID()}"
            val slot = TimetableSlotEntity(
                id = replacing?.id ?: "${timetable.id}-$entryId",
                timetableId = timetable.id,
                entryId = entryId,
                weekday = weekday.value,
                startMinute = start.hour * 60 + start.minute,
                endMinute = end.hour * 60 + end.minute,
                title = clean,
                location = location?.trim()?.ifBlank { null },
                customAppUri = replacing?.customAppUri,
                // The user set this time themselves, so nothing about it is assumed any more.
                endAssumed = false,
            )

            store.putSlot(slot)
            _editing.value = SlotEdit.None
            pushToCalendar(slot, timetable)
        }
    }

    /** Removes a slot from the week, and the calendar row it created along with it. */
    fun deleteSlot(slot: TimetableSlotEntity) {
        viewModelScope.launch {
            store.deleteSlot(slot)
            _editing.value = SlotEdit.None

            val uri = slot.customAppUri ?: return@launch
            when (executor.deleteByProvenance(uri)) {
                is Outcome.Failure ->
                    _notice.value = TimetableNotice(
                        "Removed from your timetable. We couldn't remove it from your calendar.",
                    )

                else -> Unit
            }
        }
    }

    /** Copies a slot onto other weekdays — the Tuesday-and-Thursday lab, entered once. */
    fun duplicate(slot: TimetableSlotEntity, toWeekdays: Set<DayOfWeek>) {
        val timetable = _current.value ?: return
        viewModelScope.launch {
            val copies = store.duplicateSlot(slot, toWeekdays.map { it.value }.toSet())
            _editing.value = SlotEdit.None
            if (copies.isNotEmpty()) {
                _notice.value = TimetableNotice(
                    "Added to ${copies.size} more ${if (copies.size == 1) "day" else "days"}. " +
                        "They're in your timetable, not your calendar yet.",
                )
            }
        }
    }

    /**
     * Rewrites the calendar row this slot created, if it has one.
     *
     * Silent on success, explicit on every kind of failure — including the one that is not an
     * error at all, a slot that was never in the calendar to begin with. The user needs to
     * know which of the two places they just changed.
     */
    private suspend fun pushToCalendar(slot: TimetableSlotEntity, timetable: TimetableEntity) {
        val uri = slot.customAppUri
        if (uri == null) {
            _notice.value = TimetableNotice(
                "Saved to your timetable. It isn't in your calendar — import or re-add it there.",
            )
            return
        }

        val candidate = candidateFor(slot, timetable) ?: return
        val calendarId = (currentTarget() as? CalendarTarget)?.calendarId
        if (calendarId == null) {
            _notice.value = TimetableNotice(
                "Saved to your timetable. We couldn't reach your calendar to update it.",
            )
            return
        }

        when (val result = executor.updateByProvenance(uri, candidate, calendarId)) {
            is Outcome.Failure ->
                _notice.value = TimetableNotice(
                    "Saved to your timetable. We couldn't update your calendar.",
                )

            is Outcome.Success ->
                if (result.value == 0) {
                    // Not our failure and not a problem — but saying nothing would leave the
                    // user believing their calendar had changed.
                    _notice.value = TimetableNotice(
                        "Saved. This one is no longer in your calendar, so nothing changed there.",
                    )
                }

            else -> Unit
        }
    }

    private suspend fun currentTarget() =
        (executor.targets() as? Outcome.Success)?.value?.firstOrNull()

    /**
     * Rebuilds the event a slot stands for, through the same choke point as everything else.
     *
     * Null when the slot cannot be an event — which should not happen for a stored slot, and
     * is treated as "leave the calendar alone" rather than as something to force through.
     */
    private fun candidateFor(
        slot: TimetableSlotEntity,
        timetable: TimetableEntity,
    ): CalendarEventCandidate? {
        val term = TermBounds(
            LocalDate.ofEpochDay(timetable.termStartEpochDay),
            LocalDate.ofEpochDay(timetable.termEndEpochDay),
        )
        val entry = ManualEvent.weekly(
            title = slot.title,
            weekday = DayOfWeek.of(slot.weekday),
            start = LocalTime.of(slot.startMinute / 60, slot.startMinute % 60),
            end = LocalTime.of(slot.endMinute / 60, slot.endMinute % 60),
            location = slot.location,
        )
        return (
            ManualEvent.candidate(entry, ZoneId.of(timetable.zoneId), term)
                as? CalendarEventCandidate.Result.Accepted
            )?.candidate
    }
}
