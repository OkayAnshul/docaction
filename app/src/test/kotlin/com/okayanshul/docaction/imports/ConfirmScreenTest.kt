package com.okayanshul.docaction.imports

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.okayanshul.docaction.actions.calendar.CalendarTarget
import com.okayanshul.docaction.core.designsystem.DocActionTheme
import com.okayanshul.docaction.core.settings.ReminderPreferences
import com.okayanshul.docaction.domain.ActionTarget
import com.okayanshul.docaction.imports.ui.ConfirmScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The consent step, where the app is one tap away from changing something it does not own.
 *
 * The invariant worth a test is the destination: with more than one calendar on the device,
 * nothing may be pre-picked and the write button may not be reachable. Writing forty-two
 * events into a work account somebody forgot was signed in is a real harm, entirely
 * avoidable, and exactly the kind of convenience a refactor adds back "to save a tap".
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h1400dp")
class ConfirmScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun target(id: String, label: String, account: String) =
        CalendarTarget(id, label, account, colour = null, isPrimary = false)

    private fun state(
        targets: List<ActionTarget>,
        chosenTarget: ActionTarget?,
        duplicates: Int = 0,
        needsPermission: Boolean = false,
        denied: Boolean = false,
    ): ImportState.Confirming {
        val candidates = listOf(Fixtures.candidate("e1"), Fixtures.candidate("e2", title = "Networks"))
        return ImportState.Confirming(
            review = Fixtures.review(candidates),
            chosen = candidates,
            targets = targets,
            target = chosenTarget,
            reminders = ReminderPreferences(),
            duplicates = duplicates,
            needsPermission = needsPermission,
            denied = denied,
        )
    }

    private fun show(state: ImportState.Confirming, onWrite: () -> Unit = {}) {
        compose.setContent {
            DocActionTheme {
                ConfirmScreen(
                    state = state,
                    onChooseTarget = {},
                    onSetReminders = {},
                    onSetKeepTimetable = {},
                    onRequestPermission = {},
                    onOpenSettings = {},
                    onWrite = onWrite,
                    onBack = {},
                )
            }
        }
    }

    @Test
    fun `with several calendars nothing is pre-picked and nothing can be written`() {
        val several = state(
            targets = listOf(
                target("1", "Personal", "me@gmail.com"),
                target("2", "Work", "me@company.com"),
            ),
            chosenTarget = null,
        )

        assertThat(several.canWrite).isFalse()

        var written = false
        show(several) { written = true }

        compose.onNodeWithText("Personal").assertIsDisplayed()
        compose.onNodeWithText("Work").assertIsDisplayed()
        compose.onNodeWithText("Add 2 events").assertIsNotEnabled().performClick()
        assertThat(written).isFalse()
    }

    @Test
    fun `a single calendar may be pre-picked, and is still shown`() {
        val one = target("1", "Personal", "me@gmail.com")
        val single = state(targets = listOf(one), chosenTarget = one)

        assertThat(single.canWrite).isTrue()

        var written = false
        show(single) { written = true }

        compose.onNodeWithText("Personal").assertIsDisplayed()
        compose.onNodeWithText("Add 2 events").performClick()
        assertThat(written).isTrue()
    }

    @Test
    fun `duplicates are named before the write, not discovered after it`() {
        val one = target("1", "Personal", "me@gmail.com")
        show(state(targets = listOf(one), chosenTarget = one, duplicates = 2))

        compose.onNodeWithText(
            "2 of these are already in this calendar. Adding them again will create duplicates.",
        ).assertIsDisplayed()
    }

    @Test
    fun `a denied permission is a dead end with dignity, not a nag`() {
        val denied = state(
            targets = emptyList(),
            chosenTarget = null,
            needsPermission = true,
            denied = true,
        )

        assertThat(denied.canWrite).isFalse()
        show(denied)

        compose.onNodeWithText("No problem. Nothing was changed.").assertIsDisplayed()
        compose.onNodeWithText("Open app settings").assertIsDisplayed()
        // The ask is not repeated after a refusal.
        compose.onNodeWithText("Allow calendar access").assertIsNotEnabled()
    }

    @Test
    fun `a repeating schedule says it is added once, not hundreds of times`() {
        val one = target("1", "Personal", "me@gmail.com")
        show(state(targets = listOf(one), chosenTarget = one))

        compose.onNodeWithText(
            "All of them repeat weekly. Each is added as one repeating event, not as " +
                "hundreds of copies.",
        ).assertIsDisplayed()
    }
}
