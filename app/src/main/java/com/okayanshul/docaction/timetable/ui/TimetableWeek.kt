package com.okayanshul.docaction.timetable.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.okayanshul.docaction.core.database.TimetableSlotEntity
import com.okayanshul.docaction.timetable.SlotEdit
import com.okayanshul.docaction.timetable.TimetableViewModel
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * The weekly view and everything that can change it.
 *
 * Kept separate from [TimetableScreen] so that screen stays a pure function of its slots and
 * can be tested without a ViewModel — the same reason the import flow's screens take state
 * rather than fetching it.
 *
 * The snackbar is not decoration. Editing a slot writes to two places, the stored week and
 * the calendar row it created, and those can disagree: permission may have been withdrawn, or
 * the user may have deleted the event in their calendar app. Saying nothing would leave the
 * app showing one thing and their phone showing another.
 */
@Composable
fun TimetableWeek(
    viewModel: TimetableViewModel,
    label: String,
    slots: List<TimetableSlotEntity>,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
    today: DayOfWeek = LocalDate.now().dayOfWeek,
) {
    val editing by viewModel.editing.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val snackbars = remember { SnackbarHostState() }

    LaunchedEffect(notice) {
        notice?.let {
            snackbars.showSnackbar(it.message)
            viewModel.dismissNotice()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        TimetableScreen(
            label = label,
            slots = slots,
            onImport = onLeave,
            onBack = onLeave,
            onEditSlot = viewModel::beginEdit,
            onAddSlot = viewModel::beginCreate,
            onRename = viewModel::rename,
            today = today,
        )

        SnackbarHost(snackbars, modifier = Modifier.align(Alignment.BottomCenter))
    }

    val slot = (editing as? SlotEdit.Editing)?.slot
    if (editing != SlotEdit.None) {
        SlotSheet(
            slot = slot,
            onDismiss = viewModel::cancelEdit,
            onSave = { day, start, end, title, location ->
                viewModel.saveSlot(day, start, end, title, location, replacing = slot)
            },
            // An imported slot can be removed like any other: the document is not the
            // authority on what the user's week is, the user is.
            onDelete = slot?.let { { viewModel.deleteSlot(it) } },
            onDuplicate = slot?.let { existing ->
                { days -> viewModel.duplicate(existing, days) }
            },
            defaultDay = today,
        )
    }
}
