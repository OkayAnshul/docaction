package com.okayanshul.docaction.imports

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.okayanshul.docaction.core.designsystem.DocActionTheme
import com.okayanshul.docaction.domain.FailureReason
import com.okayanshul.docaction.imports.ui.FailureScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Every failure has a way forward, and none of them leaks the machinery.
 *
 * The two properties are asserted across the whole [FailureReason] enum rather than on a
 * sample, because the failure that ships with a dead end or a stack trace will be the one
 * nobody thought to write a test for.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FailureScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `every failure offers at least one route forward`() {
        FailureReason.entries.forEach { reason ->
            assertThat(Copy.forFailure(reason).recoveries).isNotEmpty()
        }
    }

    @Test
    fun `no failure message names a parser, a stage, or an exception`() {
        val forbidden = listOf(
            "parser", "parse", "exception", "null", "error code", "stack",
            "PdfBox", "OCR", "IOException", "failed to", "invalid input",
        )

        FailureReason.entries.forEach { reason ->
            val failure = Copy.forFailure(reason)
            val text = "${failure.headline} ${failure.cause.orEmpty()}".lowercase()
            forbidden.forEach { word ->
                assertThat(text).doesNotContain(word.lowercase())
            }
        }
    }

    @Test
    fun `no failure blames the user`() {
        // "You" is not banned outright — "if you change your mind" is fine — but the
        // accusatory constructions are.
        val blaming = listOf("you should have", "you must", "invalid file", "you failed", "wrong file")

        FailureReason.entries.forEach { reason ->
            val failure = Copy.forFailure(reason)
            val text = "${failure.headline} ${failure.cause.orEmpty()}".lowercase()
            blaming.forEach { phrase -> assertThat(text).doesNotContain(phrase) }
        }
    }

    @Test
    fun `a scanned PDF is offered the route most likely to work first`() {
        // OCR runs automatically as tier 3, so by the time this failure exists it has
        // already been tried on the whole page. Narrowing to the part that matters is a
        // genuinely different attempt; "read it as an image" would be a lie shaped like help.
        val failure = Copy.forFailure(FailureReason.NoTextLayer)

        assertThat(failure.recoveries.first().action).isEqualTo(RecoveryAction.ShowUsWhere)
        assertThat(failure.recoveries.map { it.action }).doesNotContain(RecoveryAction.Dismiss)
    }

    @Test
    fun `a file too large to open is not offered a route through opening it`() {
        // The size check happens before the file is read, so pointing at one page of it
        // cannot help. An offer that looks like a fix and dead-ends is worse than none.
        val failure = Copy.forFailure(FailureReason.TooLarge)
        assertThat(failure.recoveries.map { it.action }).doesNotContain(RecoveryAction.ShowUsWhere)
        assertThat(failure.recoveries).isNotEmpty()
    }

    @Test
    fun `not finding a schedule leads with letting the user point at one`() {
        val failure = Copy.forFailure(FailureReason.NothingActionable)
        assertThat(failure.recoveries.first().action).isEqualTo(RecoveryAction.ShowUsWhere)
    }

    @Test
    fun `the first recovery is a real button that reports the action it names`() {
        var recovered: RecoveryAction? = null
        compose.setContent {
            DocActionTheme {
                FailureScreen(
                    reason = FailureReason.Encrypted,
                    documentName = "exam-schedule.pdf",
                    onRecover = { recovered = it },
                )
            }
        }

        compose.onNodeWithText("This document is password protected").assertIsDisplayed()
        compose.onNodeWithText("exam-schedule.pdf").assertIsDisplayed()

        compose.onNodeWithText("Choose another file").performClick()
        assertThat(recovered).isEqualTo(RecoveryAction.PickAnother)
    }

    @Test
    fun `a cancelled import says plainly that nothing changed`() {
        compose.setContent {
            DocActionTheme {
                FailureScreen(
                    reason = FailureReason.Cancelled,
                    documentName = "timetable.pdf",
                    onRecover = {},
                )
            }
        }

        compose.onNodeWithText("Nothing was changed.").assertIsDisplayed()
        compose.onNode(hasClickAction() and androidx.compose.ui.test.hasText("Done"))
            .assertIsDisplayed()
    }

    @Test
    fun `after a crop, finding nothing means the selection was wrong, not the document`() {
        val plain = Copy.forFailure(FailureReason.NothingActionable, afterCrop = false)
        val cropped = Copy.forFailure(FailureReason.NothingActionable, afterCrop = true)

        assertThat(cropped.headline).isNotEqualTo(plain.headline)
        assertThat(cropped.headline).isEqualTo("That selection was too narrow")
        // And it says what would make it work, rather than restating the failure.
        assertThat(cropped.cause).contains("more than one day")
        assertThat(cropped.recoveries.first().action).isEqualTo(RecoveryAction.ShowUsWhere)
    }

    @Test
    fun `a crop changes nothing about the other failures`() {
        FailureReason.entries
            .filter { it != FailureReason.NothingActionable }
            .forEach { reason ->
                assertThat(Copy.forFailure(reason, afterCrop = true))
                    .isEqualTo(Copy.forFailure(reason))
            }
    }
}
