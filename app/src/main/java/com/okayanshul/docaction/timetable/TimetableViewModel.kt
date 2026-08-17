package com.okayanshul.docaction.timetable

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.okayanshul.docaction.core.database.TimetableSlotEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the user's saved week looks like right now. */
@OptIn(ExperimentalCoroutinesApi::class)
class TimetableViewModel(application: Application) : AndroidViewModel(application) {

    private val store = TimetableStore(application)

    private val _label = MutableStateFlow<String?>(null)
    val label: StateFlow<String?> = _label.asStateFlow()

    /**
     * The most recent timetable's slots, following the database.
     *
     * A flow rather than a one-off read so that finishing an import updates the weekly view
     * without anything having to remember to refresh it — which is the kind of coupling that
     * works until the day someone adds a second way to change a timetable.
     */
    val slots: StateFlow<List<TimetableSlotEntity>> = store.timetables()
        .flatMapLatest { timetables ->
            val current = timetables.firstOrNull()
            _label.value = current?.label
            current?.let { store.slots(it.id) } ?: emptyFlow()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** True when there is a week worth showing an entry point for. */
    val hasTimetable: StateFlow<Boolean> = MutableStateFlow(false).also { flow ->
        viewModelScope.launch { flow.value = store.mostRecent() != null }
    }.asStateFlow()
}
