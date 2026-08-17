package com.okayanshul.docaction.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.okayanshul.docaction.core.settings.ReminderPreferences
import com.okayanshul.docaction.core.settings.ReminderSettings
import com.okayanshul.docaction.history.HistoryViewModel
import com.okayanshul.docaction.history.ui.ConfirmUndoDialog
import com.okayanshul.docaction.history.ui.HistoryScreen
import com.okayanshul.docaction.imports.ImportState
import com.okayanshul.docaction.imports.ImportViewModel
import com.okayanshul.docaction.imports.ui.ImportFlow
import com.okayanshul.docaction.timetable.TimetableViewModel
import com.okayanshul.docaction.settings.ui.PrivacyScreen
import com.okayanshul.docaction.settings.ui.SettingsScreen
import com.okayanshul.docaction.timetable.ui.TimetableWeek
import kotlinx.coroutines.launch

/** The three places the app has. */
enum class Destination(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Outlined.Home),
    Week("My week", Icons.Outlined.DateRange),
    History("History", Icons.Outlined.History),
}

/**
 * The app shell.
 *
 * **The import flow is not a tab.** It runs full-screen above the nav bar, and that is a
 * safety property rather than a layout preference: the flow is a linear machine that ends in
 * a calendar write, and letting someone tab away mid-confirm — then back into a Confirm whose
 * write has already happened — is exactly the double-import trap the flow's own comments warn
 * about. The bar returns as soon as the import is over.
 *
 * Three destinations, all of which do something. Settings and Privacy are reached from Home
 * rather than given equal billing, because they are not why anyone opened the app.
 */
@Composable
fun DocActionApp(
    imports: ImportViewModel,
    timetables: TimetableViewModel,
    history: HistoryViewModel,
    modifier: Modifier = Modifier,
) {
    val state by imports.state.collectAsStateWithLifecycle()
    var destination by rememberSaveable { mutableStateOf(Destination.Home) }
    // Settings and Privacy sit above the tabs rather than beside them: they are not why
    // anyone opened the app, and a nav bar advertising them as equals says otherwise.
    var overlay by rememberSaveable { mutableStateOf(Overlay.None) }

    // Anything past Idle is a task in progress, and a task in progress owns the screen.
    val inFlow = state != ImportState.Idle || overlay != Overlay.None

    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            if (!inFlow) {
                NavigationBar {
                    Destination.entries.forEach { target ->
                        NavigationBarItem(
                            selected = destination == target,
                            onClick = { destination = target },
                            icon = { Icon(target.icon, contentDescription = null) },
                            label = { Text(target.label) },
                        )
                    }
                }
            }
        },
    ) { insets ->
        Box(modifier = Modifier.padding(insets)) {
            when {
                overlay == Overlay.Settings -> SettingsTab(
                    onOpenPrivacy = { overlay = Overlay.Privacy },
                    onBack = { overlay = Overlay.None },
                )

                overlay == Overlay.Privacy ->
                    PrivacyScreen(onBack = { overlay = Overlay.Settings })

                state != ImportState.Idle -> ImportFlow(viewModel = imports)

                destination == Destination.Home -> ImportFlow(
                    viewModel = imports,
                    onOpenSettings = { overlay = Overlay.Settings },
                )

                destination == Destination.Week -> WeekTab(timetables) {
                    destination = Destination.Home
                }

                else -> HistoryTab(history) { destination = Destination.Home }
            }
        }
    }

    when {
        overlay == Overlay.Privacy -> BackHandler { overlay = Overlay.Settings }
        overlay == Overlay.Settings -> BackHandler { overlay = Overlay.None }
        // Back from a tab returns Home rather than leaving the app, which is what the platform
        // expects of a bottom bar. Home's own back is left alone so it still exits.
        state == ImportState.Idle && destination != Destination.Home ->
            BackHandler { destination = Destination.Home }
    }
}

/** Screens reached from Home rather than from the bar. */
private enum class Overlay { None, Settings, Privacy }

@Composable
private fun SettingsTab(onOpenPrivacy: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { ReminderSettings(context) }
    val preferences by settings.preferences.collectAsStateWithLifecycle(ReminderPreferences())
    val scope = rememberCoroutineScope()

    SettingsScreen(
        preferences = preferences,
        exactAlarmsUnavailable = preferences.exactAlarmsUnavailable,
        onToggleOffset = { minutes ->
            scope.launch {
                val next = preferences.offsetsMinutes.toMutableList()
                if (minutes in next) next -= minutes else next += minutes
                settings.setOffsets(next)
            }
        },
        onSetRepeatUntilStart = { scope.launch { settings.setRepeatUntilStart(it) } },
        onOpenPrivacy = onOpenPrivacy,
        onBack = onBack,
    )
}

@Composable
private fun WeekTab(viewModel: TimetableViewModel, onLeave: () -> Unit) {
    val slots by viewModel.slots.collectAsStateWithLifecycle()
    val label by viewModel.label.collectAsStateWithLifecycle()

    TimetableWeek(
        viewModel = viewModel,
        label = label ?: "My week",
        slots = slots,
        onLeave = onLeave,
    )
}

@Composable
private fun HistoryTab(viewModel: HistoryViewModel, onImport: () -> Unit) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val confirming by viewModel.confirming.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val working by viewModel.working.collectAsStateWithLifecycle()
    val snackbars = remember { SnackbarHostState() }

    LaunchedEffect(notice) {
        notice?.let {
            snackbars.showSnackbar(it.message)
            viewModel.dismissNotice()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        HistoryScreen(
            entries = entries,
            working = working,
            onUndo = viewModel::askToUndo,
            onForget = viewModel::forget,
            onImport = onImport,
        )
        SnackbarHost(
            snackbars,
            modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter),
        )
    }

    confirming?.let { request ->
        ConfirmUndoDialog(
            entry = request.import,
            onConfirm = { viewModel.undo(request.import) },
            onDismiss = viewModel::cancelUndo,
        )
    }
}
