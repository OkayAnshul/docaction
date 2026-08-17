package com.okayanshul.docaction.history

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.okayanshul.docaction.core.database.ImportEntity
import com.okayanshul.docaction.core.designsystem.DocActionTheme
import com.okayanshul.docaction.history.ui.ConfirmUndoDialog
import com.okayanshul.docaction.history.ui.HistoryScreen
import com.okayanshul.docaction.imports.ImportViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * The screen whose whole reason for existing is reversibility.
 *
 * Undo was previously offered only in the moment after a write, which serves the user who
 * notices immediately and nobody else. The failure this engine is most likely to produce —
 * importing the wrong section of a workbook — is one people notice days later, which is
 * exactly when the old design had nothing to offer them.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h1400dp")
class HistoryScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val now = 1_700_000_000_000L

    private fun entry(
        id: String = "i1",
        name: String = "Semester Timetable.pdf",
        candidates: Int = 42,
        committed: Int = 42,
        state: String = ImportViewModel.STATE_COMMITTED,
        agoMillis: Long = TimeUnit.HOURS.toMillis(2),
    ) = ImportEntity(
        id = id,
        displayName = name,
        format = "Pdf",
        contentHash = "aaaa",
        startedAt = now - agoMillis,
        completedAt = now - agoMillis,
        state = state,
        candidateCount = candidates,
        committedCount = committed,
        failureReason = null,
    )

    private fun show(
        entries: List<ImportEntity>,
        onForget: (ImportEntity) -> Unit = {},
        onUndo: (ImportEntity) -> Unit = {},
    ) {
        compose.setContent {
            DocActionTheme {
                HistoryScreen(
                    entries = entries,
                    working = false,
                    onUndo = onUndo,
                    onForget = onForget,
                    onImport = {},
                    now = now,
                )
            }
        }
    }

    @Test
    fun `a row says what it did in terms the user cares about`() {
        show(listOf(entry()))

        compose.onNodeWithText("Semester Timetable.pdf").assertIsDisplayed()
        // Not a hash, not a format, not an id.
        compose.onNodeWithText("42 events · 2 hours ago").assertIsDisplayed()
    }

    @Test
    fun `undo is reachable long after the write`() {
        var asked: ImportEntity? = null
        show(listOf(entry(agoMillis = TimeUnit.DAYS.toMillis(3))), onUndo = { asked = it })

        compose.onNodeWithText("3 days ago", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Undo this import").performClick()
        assertThat(asked).isNotNull()
    }

    @Test
    fun `an import already taken back offers no undo`() {
        show(listOf(entry(state = ImportViewModel.STATE_REVERTED)))

        compose.onNodeWithText("Taken back · 2 hours ago").assertIsDisplayed()
        // A dead button teaches people the app is broken.
        compose.onNodeWithText("Undo this import").assertDoesNotExist()
    }

    @Test
    fun `an import that wrote nothing offers no undo either`() {
        show(listOf(entry(committed = 0)))

        compose.onNodeWithText("Nothing was added · 2 hours ago").assertIsDisplayed()
        compose.onNodeWithText("Undo this import").assertDoesNotExist()
    }

    @Test
    fun `a partial write says so rather than rounding up`() {
        show(listOf(entry(candidates = 42, committed = 38)))

        compose.onNodeWithText("38 of 42 added · 2 hours ago").assertIsDisplayed()
    }

    @Test
    fun `removing a row from the list is not removing the events`() {
        var forgotten: ImportEntity? = null
        show(listOf(entry()), onForget = { forgotten = it })

        // Two different intentions. Conflating them would delete a term's classes because
        // someone tidied a list.
        compose.onNodeWithText("Remove from list").performClick()
        assertThat(forgotten).isNotNull()
    }

    @Test
    fun `an empty history explains what will appear there`() {
        show(emptyList())

        compose.onNodeWithText("Nothing added yet").assertIsDisplayed()
        compose.onNodeWithText("Add a document").assertIsDisplayed()
    }

    // --- the confirmation ---

    @Test
    fun `undo asks by name and count, and says what it will not touch`() {
        var confirmed = false
        compose.setContent {
            DocActionTheme {
                ConfirmUndoDialog(
                    entry = entry(),
                    onConfirm = { confirmed = true },
                    onDismiss = {},
                )
            }
        }

        // Phrased as the events from this document, never as a date range — which is both what
        // undo actually does and what tells the user their own events are safe.
        compose.onNodeWithText(
            "This removes the 42 events added from \"Semester Timetable.pdf\". " +
                "Nothing else in your calendar is touched.",
        ).assertIsDisplayed()

        compose.onNodeWithText("Remove 42 events").performClick()
        assertThat(confirmed).isTrue()
    }

    @Test
    fun `declining the confirmation is phrased as keeping them`() {
        compose.setContent {
            DocActionTheme {
                ConfirmUndoDialog(entry = entry(), onConfirm = {}, onDismiss = {})
            }
        }

        // "Cancel" next to "Remove" is ambiguous about which thing is being cancelled.
        compose.onNodeWithText("Keep them").assertIsDisplayed()
    }
}
