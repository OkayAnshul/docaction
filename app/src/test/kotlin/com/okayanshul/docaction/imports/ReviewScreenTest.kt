package com.okayanshul.docaction.imports

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.okayanshul.docaction.core.designsystem.DocActionTheme
import com.okayanshul.docaction.domain.CandidateId
import com.okayanshul.docaction.domain.Unresolved
import com.okayanshul.docaction.imports.ui.ReviewScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The review screen's promises, asserted rather than assumed.
 *
 * Each of these reads as obviously true in a design document and quietly stops being true
 * after a refactor: that a flagged row starts unticked, that the screen refuses to continue
 * with nothing selected, that lines the engine could not read are admitted to out loud, and
 * that confidence is never expressed as a number.
 */
@RunWith(AndroidJUnit4::class)
// A tall window, so a row near the bottom of the list is genuinely on screen rather than
// merely present. Asserting "exists" instead would pass for content the user cannot reach.
@Config(sdk = [34], qualifiers = "w411dp-h1400dp")
class ReviewScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(
        state: ImportState.Reviewing,
        onEdit: (CandidateId) -> Unit = {},
        onContinue: () -> Unit = {},
    ) {
        compose.setContent {
            DocActionTheme {
                ReviewScreen(
                    state = state,
                    onToggle = {},
                    onEdit = onEdit,
                    onFilter = {},
                    onSelectAll = {},
                    onContinue = onContinue,
                    onRescue = {},
                    onCreate = {},
                    onBack = {},
                )
            }
        }
    }

    @Test
    fun `a flagged row starts unticked so a hurried tap cannot add it unreviewed`() {
        val ready = Fixtures.candidate("e1")
        val flagged = Fixtures.flagged("e2")
        // Exactly what the ViewModel does on entering review.
        val state = ImportState.Reviewing(
            review = Fixtures.review(listOf(ready, flagged)),
            selected = setOf(ready.id),
        )

        assertThat(state.chosen.map { it.id }).containsExactly(ready.id)

        show(state)
        compose.onNodeWithText("Continue with 1 event").assertIsDisplayed()
    }

    @Test
    fun `nothing selected means nothing to continue with`() {
        var continued = false
        show(
            ImportState.Reviewing(
                review = Fixtures.review(listOf(Fixtures.candidate("e1"))),
                selected = emptySet(),
            ),
            onContinue = { continued = true },
        )

        compose.onNodeWithText("Nothing selected").assertIsNotEnabled()
        assertThat(continued).isFalse()
    }

    @Test
    fun `lines that could not be read are admitted to, not silently dropped`() {
        show(
            ImportState.Reviewing(
                review = Fixtures.review(
                    candidates = listOf(Fixtures.candidate("e1")),
                    unresolved = listOf(Fixtures.unresolved("e9", Unresolved.Field.StartTime)),
                ),
                selected = emptySet(),
            ),
        )

        compose.onNodeWithText(
            "1 other line in this document couldn't be read as an event. " +
                "They were left out rather than guessed at.",
        ).assertIsDisplayed()
    }

    @Test
    fun `a flagged row carries its question and its fix`() {
        show(
            ImportState.Reviewing(
                review = Fixtures.review(
                    candidates = listOf(Fixtures.flagged("e2")),
                    unresolved = listOf(Fixtures.unresolved("e2", Unresolved.Field.Location)),
                ),
                selected = emptySet(),
            ),
        )

        compose.onNodeWithText("Where is this?", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Fix this").assertIsDisplayed()
    }

    @Test
    fun `no percentage appears anywhere on the screen`() {
        show(
            ImportState.Reviewing(
                review = Fixtures.review(listOf(Fixtures.candidate("e1"), Fixtures.flagged("e2"))),
                selected = emptySet(),
            ),
        )

        // A percentage would invite the user to calibrate against a scale they have no
        // basis for, and would imply a precision the reading does not have.
        compose.onAllNodes(containsPercentSign, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `tapping the fix opens the editor rather than changing anything`() {
        var edited: CandidateId? = null
        val flagged = Fixtures.flagged("e2")
        show(
            ImportState.Reviewing(Fixtures.review(listOf(flagged)), emptySet()),
            onEdit = { edited = it },
        )

        compose.onNodeWithText("Fix this").performClick()
        assertThat(edited).isEqualTo(flagged.id)
    }

    // --- reachable without sight, and readable at any text size ---

    @Test
    fun `a row announces itself as one sentence, and its fix stays separately reachable`() {
        show(
            ImportState.Reviewing(
                review = Fixtures.review(
                    candidates = listOf(Fixtures.flagged("e2")),
                    unresolved = listOf(Fixtures.unresolved("e2", Unresolved.Field.Location)),
                ),
                selected = emptySet(),
            ),
        )

        // Read as five unlabelled fragments, a 42-row list is exhausting, so the text column
        // merges into one description.
        compose.onNode(hasContentDescriptionContaining("Compiler Design")).assertExists()
        compose.onNode(hasContentDescriptionContaining("not selected")).assertExists()

        // But the merge must stop before the interactive children. Clearing semantics on the
        // whole row instead once made "Fix this" invisible to TalkBack while it stayed
        // perfectly visible on screen — the kind of bug a screenshot cannot show.
        compose.onNodeWithText("Fix this").assertHasClickAction()
    }

    @Test
    fun `the screen still works at the largest system text size`() {
        // Not a pixel assertion: the question is whether the controls are still there and
        // still operable when every string is twice as tall.
        var continued = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                DocActionTheme {
                    ReviewScreen(
                        state = ImportState.Reviewing(
                            review = Fixtures.review(listOf(Fixtures.candidate("e1"))),
                            selected = setOf(CandidateId("e1")),
                        ),
                        onToggle = {},
                        onEdit = {},
                        onFilter = {},
                        onSelectAll = {},
                        onContinue = { continued = true },
                        onRescue = {},
                        onCreate = {},
                        onBack = {},
                    )
                }
            }
        }

        compose.onNodeWithText("Continue with 1 event").assertHasClickAction().performClick()
        assertThat(continued).isTrue()
    }

    // --- what we filled in ourselves ---

    @Test
    fun `an assumed end time says so, and offers to change it`() {
        show(
            ImportState.Reviewing(
                review = Fixtures.review(listOf(Fixtures.assumedEnd("e1"))),
                selected = emptySet(),
            ),
        )

        // The row must say we supplied the value, not ask a question it appears to have
        // answered — "When does this end?" beside "10:30 AM – 11:30 AM" reads as a bug.
        compose.onNodeWithText("End time assumed (1 hour) · tap to change", useUnmergedTree = true)
            .assertIsDisplayed()
        compose.onNodeWithText("Change end time").assertIsDisplayed()
    }

    @Test
    fun `an all-day item shows no clock time`() {
        show(
            ImportState.Reviewing(
                review = Fixtures.review(listOf(Fixtures.allDay("e1"))),
                selected = emptySet(),
            ),
        )

        compose.onNodeWithText("Add a time").assertIsDisplayed()
        // "12:00 AM – 12:00 AM" is derivable from the same fields and would be nonsense.
        compose.onAllNodes(containsMidnightRange, useUnmergedTree = true).assertCountEquals(0)
    }
}

private val containsMidnightRange = SemanticsMatcher("text shows a midnight-to-midnight range") { node ->
    node.config.getOrNull(SemanticsProperties.Text)
        ?.any { it.text.contains("12:00 AM – 12:00 AM") } == true
}


private val containsPercentSign = SemanticsMatcher("text contains '%'") { node ->
    node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.contains('%') } == true
}

private fun hasContentDescriptionContaining(text: String) =
    SemanticsMatcher("content description contains '$text'") { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription)
            ?.any { it.contains(text) } == true
    }
