package com.okayanshul.docaction.imports.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.okayanshul.docaction.core.designsystem.ReadableColumn
import com.okayanshul.docaction.imports.ImportState
import com.okayanshul.docaction.imports.ImportViewModel
import com.okayanshul.docaction.imports.RecoveryAction

/**
 * The whole flow, hosted by one composable.
 *
 * There is no navigation graph here, and that is deliberate. The import is a strictly linear
 * machine already modelled by [ImportState]; adding routes would mean maintaining the same
 * sequence twice and would give the system back button a stack it must not have — "back"
 * from the done screen must never re-enter a confirm step whose calendar write has already
 * happened. With state as the single source of truth, every transition is a state change and
 * back is defined explicitly, per state, below.
 */
@Composable
fun ImportFlow(
    viewModel: ImportViewModel,
    /** Null until there is a saved week; the entry point does not appear before then. */
    onOpenTimetable: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val interrupted by viewModel.interrupted.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::start) }

    // The Photo Picker needs no storage permission at all, on any supported version.
    val pickPhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(viewModel::start) }

    // The photo's destination has to exist before the camera app is launched, and has to
    // survive this composable being recomposed while another app is in front.
    var pendingCapture by remember { mutableStateOf<Uri?>(null) }
    val takePhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { taken ->
        pendingCapture?.let { if (taken) viewModel.start(it) }
        pendingCapture = null
    }

    val requestCalendar = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted -> viewModel.permissionResolved(granted.values.all { it }) }

    val openFiles = { pickFile.launch(SUPPORTED_MIME_TYPES) }
    val openCamera = {
        val target = viewModel.captureTarget()
        pendingCapture = target
        takePhoto.launch(target)
    }
    val openPhotos = {
        pickPhoto.launch(
            androidx.activity.result.PickVisualMediaRequest(
                ActivityResultContracts.PickVisualMedia.ImageOnly,
            ),
        )
    }

    // Back means "undo the last step", never "leave a half-finished write behind".
    when (state) {
        is ImportState.Processing -> BackHandler { viewModel.cancel() }
        is ImportState.Asking -> BackHandler { viewModel.cancel() }
        is ImportState.Reviewing -> BackHandler {
            val reviewing = state as ImportState.Reviewing
            when {
                reviewing.viewingSource != null -> viewModel.hideSource()
                reviewing.editing != null -> viewModel.beginEdit(null)
                else -> viewModel.cancel()
            }
        }
        is ImportState.Rescuing -> BackHandler { viewModel.cancel() }
        is ImportState.Confirming -> BackHandler { viewModel.backToReview() }
        // Writing has no back: a half-cancelled batch is a calendar nobody asked for.
        is ImportState.Writing -> BackHandler {}
        is ImportState.Finished -> BackHandler { viewModel.dismiss() }
        is ImportState.Failed -> BackHandler { viewModel.dismiss() }
        ImportState.Idle -> Unit
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = modifier.fillMaxSize()) {
        ReadableColumn {
        AnimatedContent(
            targetState = state,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "import",
        ) { current ->
            when (current) {
                ImportState.Idle -> HomeScreen(
                    onPickFile = openFiles,
                    onPickPhoto = openPhotos,
                    onTakePhoto = openCamera,
                    interrupted = interrupted,
                    onResume = viewModel::resume,
                    onDiscardInterrupted = viewModel::discardInterrupted,
                    onOpenTimetable = onOpenTimetable,
                )

                is ImportState.Processing -> ProcessingScreen(
                    documentName = current.documentName,
                    stages = current.stages,
                    detail = current.detail,
                    determinate = current.determinate,
                    onCancel = viewModel::cancel,
                )

                is ImportState.Asking -> QuestionScreen(
                    question = current.question,
                    onPickGroup = viewModel::answer,
                    onPickTerm = viewModel::answer,
                    onPickOrder = viewModel::answer,
                    onCancel = viewModel::cancel,
                )

                is ImportState.Reviewing -> {
                    ReviewScreen(
                        state = current,
                        onToggle = viewModel::toggle,
                        onEdit = { viewModel.beginEdit(it) },
                        onFilter = viewModel::filterAttention,
                        onSelectAll = viewModel::selectAll,
                        onContinue = viewModel::toConfirm,
                        onRescue = viewModel::beginRescue,
                        onBack = viewModel::cancel,
                    )
                    current.editingCandidate?.let { editing ->
                        EditSheet(
                            candidate = editing,
                            onDismiss = { viewModel.beginEdit(null) },
                            onSave = { corrected ->
                                viewModel.applyEdit(editing.id) { corrected }
                            },
                            onShowSource = { viewModel.showSource(editing.id) },
                        )
                    }
                    if (current.viewingSource != null) {
                        SourceSheet(
                            evidence = current.evidence,
                            onDismiss = viewModel::hideSource,
                        )
                    }
                }

                is ImportState.Confirming -> ConfirmScreen(
                    state = current,
                    onChooseTarget = viewModel::chooseTarget,
                    onSetReminders = viewModel::setReminders,
                    onSetKeepTimetable = viewModel::setKeepTimetable,
                    onChooseTimetableResolution = viewModel::setTimetableResolution,
                    onRequestPermission = { requestCalendar.launch(CALENDAR_PERMISSIONS) },
                    onOpenSettings = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null),
                            ),
                        )
                    },
                    onWrite = viewModel::write,
                    onBack = viewModel::backToReview,
                )

                is ImportState.Rescuing -> RescueScreen(
                    state = current,
                    onPage = viewModel::rescuePage,
                    onCrop = viewModel::setCrop,
                    onApply = viewModel::applyRescue,
                    onCancel = viewModel::cancel,
                )

                is ImportState.Writing -> WritingScreen(current.written, current.total)

                is ImportState.Finished -> DoneScreen(
                    state = current,
                    onUndo = viewModel::undo,
                    onOpenCalendar = { openCalendar(context) },
                    onDone = viewModel::dismiss,
                )

                is ImportState.Failed -> FailureScreen(
                    reason = current.reason,
                    documentName = current.documentName,
                    afterCrop = current.afterCrop,
                    emptySchedule = current.emptySchedule,
                    onRecover = { action ->
                        when (action) {
                            RecoveryAction.ChooseAnotherSchedule -> viewModel.chooseAnotherSchedule()

                            RecoveryAction.ShowUsWhere -> viewModel.beginRescue()

                            RecoveryAction.PickAnother -> {
                                viewModel.dismiss()
                                openFiles()
                            }

                            RecoveryAction.PickPhoto -> {
                                viewModel.dismiss()
                                openPhotos()
                            }

                            RecoveryAction.Dismiss -> viewModel.dismiss()
                        }
                    },
                )
            }
        }
        }
    }
}

/**
 * Hands off to whichever calendar app the device uses, at today's date.
 *
 * Wrapped because a device with no calendar app is unusual but real, and crashing on the
 * success screen would be a memorably poor ending.
 */
private fun openCalendar(context: android.content.Context) {
    val uri = CalendarContract.CONTENT_URI.buildUpon()
        .appendPath("time")
        .appendPath(System.currentTimeMillis().toString())
        .build()
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
}

/**
 * Only what the engine can actually read.
 *
 * Advertising formats we cannot handle turns the file picker into a trap: the user browses,
 * chooses, waits, and is told no. Better to grey them out before they start.
 */
private val SUPPORTED_MIME_TYPES = arrayOf(
    "application/pdf",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    // Several, because providers disagree about what a CSV is called. The format is decided
    // by reading the file, never by the name — this list only governs what the picker greys
    // out, and being too narrow here hides files we can actually read.
    "text/csv",
    "text/comma-separated-values",
    "application/csv",
    "image/*",
)

private val CALENDAR_PERMISSIONS = arrayOf(
    Manifest.permission.READ_CALENDAR,
    Manifest.permission.WRITE_CALENDAR,
)
