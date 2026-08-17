package com.okayanshul.docaction.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.okayanshul.docaction.actions.calendar.CalendarEventExecutor
import com.okayanshul.docaction.actions.reminder.ReminderScheduler
import com.okayanshul.docaction.core.database.Databases
import com.okayanshul.docaction.core.database.ImportEntity
import com.okayanshul.docaction.domain.ImportId
import com.okayanshul.docaction.domain.Outcome
import com.okayanshul.docaction.imports.ImportViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** An import the user is being asked to confirm they want to take back. */
data class UndoRequest(val import: ImportEntity)

/** The outcome of an undo, in the words it will be shown in. */
data class HistoryNotice(val message: String)

/**
 * What this app has done to the user's calendar, and how to take it back.
 *
 * Undo used to live only on the screen that appears immediately after a write, which is the
 * wrong place for it to live alone. The realistic case is someone realising three days later
 * that they imported the wrong section — and the section-choice failure is one this engine
 * genuinely has — by which point the only route back was deleting 42 events by hand.
 */
class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val imports = Databases.imports(application)

    private val executor by lazy {
        CalendarEventExecutor(
            context = getApplication(),
            createdEvents = Databases.createdEvents(getApplication()),
        )
    }

    val entries: StateFlow<List<ImportEntity>> = imports.recent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _confirming = MutableStateFlow<UndoRequest?>(null)
    val confirming: StateFlow<UndoRequest?> = _confirming.asStateFlow()

    private val _notice = MutableStateFlow<HistoryNotice?>(null)
    val notice: StateFlow<HistoryNotice?> = _notice.asStateFlow()

    private val _working = MutableStateFlow(false)
    val working: StateFlow<Boolean> = _working.asStateFlow()

    /** Undo is destructive, so it is asked about by name and count before it happens. */
    fun askToUndo(import: ImportEntity) { _confirming.value = UndoRequest(import) }

    fun cancelUndo() { _confirming.value = null }

    fun dismissNotice() { _notice.value = null }

    /**
     * Removes exactly the events one import created.
     *
     * The report is passed through as it comes back rather than rounded up: if some events
     * were already deleted elsewhere, the user is told "38 removed, 4 were already gone"
     * instead of a claim about 42 that is not true.
     */
    fun undo(import: ImportEntity) {
        _confirming.value = null
        _working.value = true

        viewModelScope.launch {
            val id = ImportId(import.id)
            val result = runCatching {
                val reverted = executor.revert(id)
                ReminderScheduler(getApplication(), Databases.reminders(getApplication()))
                    .cancelImport(id)
                reverted
            }.getOrElse { Outcome.Failure(com.okayanshul.docaction.domain.FailureReason.ProcessingUnavailable) }

            _notice.value = when (result) {
                is Outcome.Success -> {
                    imports.markState(import.id, ImportViewModel.STATE_REVERTED)
                    HistoryNotice(describe(result.value.removed, result.value.alreadyGone))
                }

                else -> HistoryNotice("We couldn't remove these. Nothing was changed.")
            }
            _working.value = false
        }
    }

    /**
     * Forgets a history entry without touching the calendar.
     *
     * Two different intentions, kept apart. Tidying a list must never delete a term's classes
     * (FR-7.3), so this says plainly that the events stay.
     */
    fun forget(import: ImportEntity) {
        viewModelScope.launch {
            imports.forget(import.id)
            _notice.value = HistoryNotice("Removed from this list. The events are still in your calendar.")
        }
    }

    private fun describe(removed: Int, alreadyGone: Int): String = when {
        removed == 0 && alreadyGone == 0 -> "There was nothing left to remove."
        alreadyGone == 0 && removed == 1 -> "1 event removed."
        alreadyGone == 0 -> "$removed events removed."
        // Never claims to have removed what someone else already did.
        removed == 0 -> "These were already gone from your calendar."
        else -> "$removed removed. $alreadyGone had already gone."
    }
}
