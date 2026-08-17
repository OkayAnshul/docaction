package com.okayanshul.docaction

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.okayanshul.docaction.core.designsystem.DocActionTheme
import com.okayanshul.docaction.imports.ImportViewModel
import com.okayanshul.docaction.imports.ui.ImportFlow
import com.okayanshul.docaction.timetable.TimetableViewModel
import com.okayanshul.docaction.timetable.ui.TimetableScreen

/**
 * The single activity, and the app's two front doors.
 *
 * The share sheet is the headline flow: a student who receives a timetable in WhatsApp or
 * email should never have to save it, open DocAction, and go looking for it. Sharing lands
 * straight in the same import that the in-app picker starts.
 *
 * A shared document arrives as a one-shot URI grant that dies with this activity's task, so
 * the first thing the import does is copy it — see `DocumentStaging`.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: ImportViewModel by viewModels()
    private val timetableViewModel: TimetableViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Only on a cold start. A configuration change must not re-import the same document.
        if (savedInstanceState == null) receive(intent)

        setContent {
            DocActionTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { insets ->
                    // Two destinations, held here rather than in a navigation graph. The
                    // reason ImportFlow gives for avoiding one still holds and now matters
                    // more: back from Done must never re-enter a Confirm whose calendar
                    // write has already happened, and a back stack would offer exactly that.
                    var showingTimetable by rememberSaveable { mutableStateOf(false) }
                    val slots by timetableViewModel.slots.collectAsStateWithLifecycle()
                    val label by timetableViewModel.label.collectAsStateWithLifecycle()

                    if (showingTimetable) {
                        BackHandler { showingTimetable = false }
                        TimetableScreen(
                            label = label ?: "My Timetable",
                            slots = slots,
                            onImport = { showingTimetable = false },
                            onBack = { showingTimetable = false },
                            modifier = Modifier.padding(insets),
                        )
                    } else {
                        ImportFlow(
                            viewModel = viewModel,
                            onOpenTimetable = { showingTimetable = true }.takeIf { slots.isNotEmpty() },
                            modifier = Modifier.padding(insets),
                        )
                    }
                }
            }
        }
    }

    /** A second share while the app is already open replaces the current import. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receive(intent)
    }

    /**
     * A share can carry a file or it can carry text.
     *
     * The text case is the one people reach for without thinking: a class rep posts the
     * week's schedule as a message, and there is no file to save anywhere. Taking the
     * document first means a share carrying both — some apps send a preview string alongside
     * an attachment — imports the attachment rather than its own summary of it.
     */
    private fun receive(intent: Intent?) {
        sharedDocument(intent)?.let { viewModel.start(it); return }
        sharedText(intent)?.let(viewModel::startText)
    }

    private fun sharedText(intent: Intent?): String? =
        intent?.takeIf { it.action == Intent.ACTION_SEND }
            ?.getStringExtra(Intent.EXTRA_TEXT)
            ?.takeIf { it.isNotBlank() }

    /**
     * `ACTION_SEND_MULTIPLE` takes the first document rather than refusing.
     *
     * One import at a time is a real constraint — review and confirmation are per document —
     * but a user who shared three files and gets an error has been punished for sharing the
     * way their app made easiest.
     */
    private fun sharedDocument(intent: Intent?): Uri? = when (intent?.action) {
        Intent.ACTION_SEND ->
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)

        Intent.ACTION_SEND_MULTIPLE ->
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                ?.firstOrNull()

        Intent.ACTION_VIEW -> intent.data

        else -> null
    }
}
