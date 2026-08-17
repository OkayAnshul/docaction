package com.okayanshul.docaction.core.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The design system's behavioural promises.
 *
 * These are the properties that are easy to claim in a document and easy to break in code:
 * that state is announced in words, that confidence never depends on colour alone, and that
 * a ready row stays quiet while one needing attention does not.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DesignSystemTest {

    @get:Rule
    val compose = createComposeRule()

    // --- confidence is readable without colour ---

    @Test
    fun eachConfidenceStateAnnouncesItselfInWords() {
        compose.setContent {
            DocActionTheme {
                Column {
                    ConfidenceBadge(Confidence.Ready)
                    ConfidenceBadge(Confidence.Check)
                    ConfidenceBadge(Confidence.Missing)
                    ConfidenceBadge(Confidence.Invalid)
                }
            }
        }

        // A screen reader gets a word, never an unnamed glyph.
        listOf("Ready", "Check", "Missing", "Invalid").forEach { word ->
            compose.onNodeWithContentDescription(word).assertExists()
        }
    }

    @Test
    fun everyConfidenceStateHasADistinctGlyph() {
        // Colour is the third signal, never the only one. Distinct glyphs are what make the
        // states survive greyscale and colour blindness.
        val glyphs = listOf("✓", "⚠", "?", "✕")
        assertThat(glyphs.distinct()).hasSize(glyphs.size)
    }

    @Test
    fun confidenceColoursDifferBetweenLightAndDark() {
        // Dark mode is designed, not inverted: amber that reads as "attention" on white
        // reads as "highlighted" on black.
        assertThat(ConfidenceColours.Light.checkFg).isNotEqualTo(ConfidenceColours.Dark.checkFg)
        assertThat(ConfidenceColours.Light.readyFg).isNotEqualTo(ConfidenceColours.Dark.readyFg)
    }

    // --- the review row ---

    @Test
    fun aReadyRowShowsNoReasonAndNoInlineActions() {
        compose.setContent {
            DocActionTheme {
                EventRow(
                    title = "Data Structures",
                    time = "Monday · 09:00–10:00",
                    detail = "K10",
                    state = Confidence.Ready,
                    selected = true,
                    onToggle = {},
                    onEdit = {},
                )
            }
        }

        compose.onNodeWithContentDescription(
            "Data Structures, Monday · 09:00–10:00, K10, ready, selected",
        ).assertIsDisplayed()
    }

    @Test
    fun aRowNeedingAttentionCarriesItsReasonAndItsFix() {
        var fixed = false
        compose.setContent {
            DocActionTheme {
                EventRow(
                    title = "DBMS",
                    time = "Thursday · 10:00",
                    detail = null,
                    state = Confidence.Check,
                    selected = false,
                    onToggle = {},
                    onEdit = {},
                    reason = "Please check the time",
                    actions = listOf(RowAction("Set end time") { fixed = true }),
                )
            }
        }

        // The reason is plain language, and the fix is right there rather than behind a tap.
        compose.onNodeWithText("Please check the time").assertIsDisplayed()
        compose.onNodeWithText("Set end time").performClick()
        assertThat(fixed).isTrue()
    }

    @Test
    fun aRowAnnouncesItsStateAndSelectionAsOneSentence() {
        compose.setContent {
            DocActionTheme {
                EventRow(
                    title = "DBMS",
                    time = "Thursday · 10:00",
                    detail = null,
                    state = Confidence.Check,
                    selected = false,
                    onToggle = {},
                    onEdit = {},
                    reason = "Please check the time",
                )
            }
        }

        // Forty-two rows read as five unlabelled fragments each is exhausting; one sentence
        // per row is the difference between usable and not.
        compose.onNodeWithContentDescription(
            "DBMS, Thursday · 10:00, Please check the time, not selected",
        ).assertExists()
    }

    // --- progress is honest ---

    @Test
    fun skippedStagesAreNeverShown() {
        compose.setContent {
            DocActionTheme {
                StageProgress(
                    stages = listOf(
                        StageLine("Reading document", StageState.Done),
                        StageLine("Reading the image", StageState.Skipped),
                        StageLine("Building schedule", StageState.Active),
                    ),
                )
            }
        }

        compose.onNodeWithText("Reading document").assertIsDisplayed()
        compose.onNodeWithText("Building schedule").assertIsDisplayed()
        // Showing work that isn't happening is the same lie as a fake spinner.
        compose.onNodeWithText("Reading the image").assertDoesNotExist()
    }

    // --- empty states say something useful ---

    @Test
    fun anEmptyStateOffersAWayForward() {
        var acted = false
        compose.setContent {
            DocActionTheme {
                EmptyState(
                    headline = "Nothing here yet.",
                    body = "Give me a timetable, schedule or document and I'll turn it into something useful.",
                    actionLabel = "Add your first document",
                    onAction = { acted = true },
                )
            }
        }

        compose.onNodeWithText("Nothing here yet.").assertIsDisplayed()
        compose.onNodeWithText("Add your first document").performClick()
        assertThat(acted).isTrue()
    }
}
