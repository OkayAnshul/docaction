package com.okayanshul.docaction.imports

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.okayanshul.docaction.core.designsystem.DocActionTheme
import com.okayanshul.docaction.domain.CalendarEventCandidate
import com.okayanshul.docaction.domain.CandidateStatus
import com.okayanshul.docaction.domain.SourceReference
import com.okayanshul.docaction.domain.TermBounds
import com.okayanshul.docaction.imports.ui.EditSheet
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * The one sheet that both corrects an event and writes a new one.
 *
 * The tests worth having are the ones that catch the two halves quietly diverging: a new
 * event that skips a rule the engine enforces, or an edited one that loses the provenance
 * that makes the review screen worth trusting.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h1400dp")
class EditSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private val term = TermBounds(LocalDate.of(2026, 8, 17), LocalDate.of(2026, 12, 5))
    private val today = LocalDate.of(2026, 9, 14)

    private fun show(
        candidate: CalendarEventCandidate?,
        onShowSource: (() -> Unit)? = null,
        onDelete: (() -> Unit)? = null,
        onSave: (CalendarEventCandidate) -> Unit = {},
    ) {
        compose.setContent {
            DocActionTheme {
                EditSheet(
                    candidate = candidate,
                    zone = Fixtures.zone,
                    term = term,
                    onDismiss = {},
                    onSave = onSave,
                    onShowSource = onShowSource,
                    onDelete = onDelete,
                    today = today,
                )
            }
        }
    }

    // --- writing a new event ---

    @Test
    fun `a new event cannot be added until it has a name`() {
        show(candidate = null)

        compose.onNodeWithText("New event").assertIsDisplayed()
        // Blank is the starting state, not a mistake, so it is disabled but not scolded.
        compose.onNodeWithText("Add").assertIsNotEnabled()
        compose.onNodeWithText("An event needs a name.").assertDoesNotExist()

        compose.onNodeWithText("Name").performTextInput("Dentist")
        compose.onNodeWithText("Add").assertIsEnabled()
    }

    @Test
    fun `a new event is saved ready, traceable to the user, and nothing is assumed`() {
        var saved: CalendarEventCandidate? = null
        show(candidate = null) { saved = it }

        compose.onNodeWithText("Name").performTextInput("Dentist")
        compose.onNodeWithText("Add").performClick()

        val event = requireNotNull(saved)
        assertThat(event.title).isEqualTo("Dentist")
        assertThat(event.status).isEqualTo(CandidateStatus.Ready)
        assertThat(event.assumptions).isEmpty()
        // The default 09:00–10:00 came from the form the user was looking at, so it is theirs.
        assertThat(event.sources.filterIsInstance<SourceReference.UserProvided>()).isNotEmpty()
        assertThat(event.start.toLocalDate()).isEqualTo(today)
    }

    @Test
    fun `a new event offers no source, because there is no document behind it`() {
        show(candidate = null)

        // Offering "where did this come from?" for something the user typed would be absurd,
        // and the sheet would have nothing to show.
        compose.onNodeWithText("Where did this come from?").assertDoesNotExist()
    }

    @Test
    fun `a new event can be made to repeat weekly, bounded by the term`() {
        var saved: CalendarEventCandidate? = null
        show(candidate = null) { saved = it }

        compose.onNodeWithText("Name").performTextInput("Data Structures")
        compose.onNodeWithText("Just this once.").performClick()
        compose.onNodeWithText("Add").performClick()

        val event = requireNotNull(saved)
        assertThat(event.recurrence).isNotNull()
        // Never unbounded — the same rule the importer follows.
        assertThat(event.recurrence!!.until).isEqualTo(term.end)
    }

    @Test
    fun `a new event can be all day`() {
        var saved: CalendarEventCandidate? = null
        show(candidate = null) { saved = it }

        compose.onNodeWithText("Name").performTextInput("Fee payment")
        compose.onNodeWithText("Make it all day").performClick()
        compose.onNodeWithText("Add").performClick()

        assertThat(requireNotNull(saved).isAllDay).isTrue()
    }

    // --- correcting an existing one ---

    @Test
    fun `an edited event keeps its identity and gains a user-provided source`() {
        val original = Fixtures.candidate("e1", title = "Data Strucures")
        var saved: CalendarEventCandidate? = null
        show(candidate = original) { saved = it }

        compose.onNodeWithText("Edit this event").assertIsDisplayed()
        compose.onNodeWithText("Name").performTextInput("x")
        compose.onNodeWithText("Save").performClick()

        val event = requireNotNull(saved)
        // Same row, corrected — not a new event that happens to look similar. Undo and
        // duplicate detection both depend on this.
        assertThat(event.id).isEqualTo(original.id)
        assertThat(event.sources.filterIsInstance<SourceReference.UserProvided>().map { it.field })
            .contains("title")
        // And the document region it was read from is still there to point at.
        assertThat(event.sources.filterIsInstance<SourceReference.PdfSpan>()).isNotEmpty()
    }

    @Test
    fun `an extracted event still offers its source`() {
        var asked = false
        show(candidate = Fixtures.candidate("e1"), onShowSource = { asked = true })

        compose.onNodeWithText("Where did this come from?").performClick()
        assertThat(asked).isTrue()
    }

    @Test
    fun `removal is offered only where it is meant to be`() {
        // An extracted row is deselected, never deleted — the document still says it.
        show(candidate = Fixtures.candidate("e1"))
        compose.onNodeWithText("Remove this event").assertDoesNotExist()
    }

    @Test
    fun `a hand-written row can be removed outright`() {
        var removed = false
        show(candidate = Fixtures.candidate("e1"), onDelete = { removed = true })

        compose.onNodeWithText("Remove this event").performClick()
        assertThat(removed).isTrue()
    }
}
