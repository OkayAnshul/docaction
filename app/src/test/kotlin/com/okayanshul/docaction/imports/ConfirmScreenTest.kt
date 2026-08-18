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
import com.okayanshul.docaction.domain.CandidateId
import com.okayanshul.docaction.domain.DuplicateMatch
import com.okayanshul.docaction.imports.ui.ConfirmScreen
import com.okayanshul.docaction.timetable.TimetableCollision
import com.okayanshul.docaction.timetable.TimetableResolution
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
        duplicates: List<DuplicateMatch> = emptyList(),
        needsPermission: Boolean = false,
        denied: Boolean = false,
        collision: TimetableCollision? = null,
        resolution: TimetableResolution? = null,
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
            timetableCollision = collision,
            timetableResolution = resolution,
        )
    }

    /**
     * [onWrite] stays last so `show(state) { written = true }` keeps binding to it. Adding a
     * parameter after it silently rebinds every trailing lambda in this file, and the tests
     * that assert a write did *not* happen would then pass for the wrong reason.
     */
    private fun show(
        state: ImportState.Confirming,
        onChooseTimetableResolution: (TimetableResolution) -> Unit = {},
        onChooseDuplicates: (ImportState.DuplicateChoice) -> Unit = {},
        onWrite: () -> Unit = {},
    ) {
        compose.setContent {
            DocActionTheme {
                ConfirmScreen(
                    state = state,
                    onChooseTarget = {},
                    onSetReminders = {},
                    onSetKeepTimetable = {},
                    onChooseTimetableResolution = onChooseTimetableResolution,
                    onChooseDuplicates = onChooseDuplicates,
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

    // --- events already in the calendar ---

    private fun duplicate(id: String, title: String) = DuplicateMatch(
        candidateId = CandidateId(id),
        existingTitle = title,
        existingStartMillis = 1_789_000_000_000L,
        createdByUs = false,
    )

    @Test
    fun `duplicates are named before the write, not discovered after it`() {
        val one = target("1", "Personal", "me@gmail.com")
        show(
            state(
                targets = listOf(one), chosenTarget = one,
                duplicates = listOf(duplicate("e1", "Data Structures")),
            ),
        )

        compose.onNodeWithText("One of these is already there.").assertIsDisplayed()
        // Named, not counted. Only the user can tell a re-import from a lecture that
        // legitimately falls at the same hour as last term's.
        compose.onNodeWithText("Data Structures").assertIsDisplayed()
    }

    @Test
    fun `skipping is the default and the button promises the smaller number`() {
        val one = target("1", "Personal", "me@gmail.com")
        val withDuplicate = state(
            targets = listOf(one), chosenTarget = one,
            duplicates = listOf(duplicate("e1", "Data Structures")),
        )

        // The one default in this flow, and it cannot lose anything: what it declines to
        // write is already in the calendar.
        assertThat(withDuplicate.duplicateChoice).isEqualTo(ImportState.DuplicateChoice.Skip)
        assertThat(withDuplicate.toWrite).hasSize(1)

        show(withDuplicate)
        compose.onNodeWithText("Add 1 event").assertIsDisplayed()
    }

    @Test
    fun `adding anyway writes all of them again`() {
        val one = target("1", "Personal", "me@gmail.com")
        val anyway = state(
            targets = listOf(one), chosenTarget = one,
            duplicates = listOf(duplicate("e1", "Data Structures")),
        ).copy(duplicateChoice = ImportState.DuplicateChoice.AddAnyway)

        assertThat(anyway.toWrite).hasSize(2)

        show(anyway)
        compose.onNodeWithText("Add 2 events").assertIsDisplayed()
    }

    @Test
    fun `an import that is entirely duplicates cannot be written`() {
        val one = target("1", "Personal", "me@gmail.com")
        val allPresent = state(
            targets = listOf(one), chosenTarget = one,
            duplicates = listOf(duplicate("e1", "a"), duplicate("e2", "b")),
        )

        // Writing nothing is not a successful import, and a button that says "Add 0 events"
        // is one the user is right to distrust.
        assertThat(allPresent.toWrite).isEmpty()
        assertThat(allPresent.canWrite).isFalse()

        show(allPresent)
        compose.onNodeWithText("Everything here is already in your calendar")
            .assertIsNotEnabled()
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

    // --- a stored timetable is never overwritten without being asked about ---

    private val existing = TimetableCollision(
        timetableId = "t1",
        label = "Section CS-1",
        slotCount = 5,
        sourceName = "old.pdf",
        updatedAt = 0,
    )

    @Test
    fun `a timetable at risk is named and counted, and blocks the write until answered`() {
        val one = target("1", "Personal", "me@gmail.com")
        val atRisk = state(targets = listOf(one), chosenTarget = one, collision = existing)

        // The whole point: an unanswered question cannot be resolved by pressing the button.
        assertThat(atRisk.awaitingTimetableDecision).isTrue()
        assertThat(atRisk.canWrite).isFalse()

        var written = false
        show(atRisk) { written = true }

        // Named and counted — "replace" is only informed if it says what it removes.
        compose.onNodeWithText(
            "You already have a timetable called \"Section CS-1\", with 5 classes in it. " +
                "What should happen to it?",
        ).assertIsDisplayed()
        compose.onNodeWithText(
            "Removes the 5 classes in \"Section CS-1\" and uses these instead. You can undo this.",
        ).assertIsDisplayed()

        compose.onNodeWithText("Choose what happens to your timetable")
            .assertIsNotEnabled()
            .performClick()
        assertThat(written).isFalse()
    }

    @Test
    fun `every option is offered, and none of them is pre-selected`() {
        val one = target("1", "Personal", "me@gmail.com")
        show(state(targets = listOf(one), chosenTarget = one, collision = existing))

        // No default. Defaulting is what destroyed timetables in the first place.
        listOf("Add to it", "Keep both, separately", "Replace it", "Don't keep this one")
            .forEach { compose.onNodeWithText(it).assertIsDisplayed() }
    }

    @Test
    fun `choosing an answer unblocks the write`() {
        val one = target("1", "Personal", "me@gmail.com")
        val answered = state(
            targets = listOf(one),
            chosenTarget = one,
            collision = existing,
            resolution = TimetableResolution.Merge,
        )

        assertThat(answered.awaitingTimetableDecision).isFalse()
        assertThat(answered.canWrite).isTrue()

        var written = false
        show(answered) { written = true }
        compose.onNodeWithText("Add 2 events").performClick()
        assertThat(written).isTrue()
    }

    @Test
    fun `the button names the destruction it is about to carry out`() {
        val one = target("1", "Personal", "me@gmail.com")
        show(
            state(
                targets = listOf(one),
                chosenTarget = one,
                collision = existing,
                resolution = TimetableResolution.Replace,
            ),
        )

        // The user reads the button they press. Disclosing this only in a dialog that appears
        // afterwards would be disclosing it after the decision was made.
        compose.onNodeWithText("Add 2 events and replace timetable").assertIsDisplayed()
    }

    @Test
    fun `nothing is asked when the import is not being kept as a timetable`() {
        val one = target("1", "Personal", "me@gmail.com")
        val notKept = state(targets = listOf(one), chosenTarget = one, collision = existing)
            .copy(keepAsTimetable = false)

        // Nothing will be written to the timetable at all, so there is nothing to decide.
        assertThat(notKept.awaitingTimetableDecision).isFalse()
        assertThat(notKept.canWrite).isTrue()

        show(notKept)
        compose.onNodeWithText("Replace it").assertDoesNotExist()
    }
}
