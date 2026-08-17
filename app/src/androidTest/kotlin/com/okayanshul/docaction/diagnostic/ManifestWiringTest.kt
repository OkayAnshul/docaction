package com.okayanshul.docaction.diagnostic

import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Asserts the *merged* manifest actually wires up what the engine depends on.
 *
 * The reminder logic is unit-tested, but logic is only half of it: a `BroadcastReceiver`
 * that is never registered for `BOOT_COMPLETED` behaves perfectly in tests and then silently
 * stops rearming alarms after the user restarts their phone. There is no error and no
 * symptom until a class is missed, which makes it precisely the kind of failure worth
 * asserting against the packaged app rather than against the source.
 */
@RunWith(AndroidJUnit4::class)
class ManifestWiringTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val packages: PackageManager = context.packageManager

    private fun receiversFor(action: String) =
        packages.queryBroadcastReceivers(Intent(action).setPackage(context.packageName), 0)
            .map { it.activityInfo.name }

    @Test
    fun bootCompletedIsWiredToTheReminderRearm() {
        assertThat(receiversFor(Intent.ACTION_BOOT_COMPLETED))
            .contains("com.okayanshul.docaction.actions.reminder.BootReceiver")
    }

    @Test
    fun timezoneChangeIsWiredToTheReminderRearm() {
        // A phone crossing a timezone must re-anchor its alarms, or every class fires at
        // the wrong local time for the rest of the trip.
        assertThat(receiversFor(Intent.ACTION_TIMEZONE_CHANGED))
            .contains("com.okayanshul.docaction.actions.reminder.BootReceiver")
    }

    @Test
    fun packageReplacementIsWiredToTheReminderRearm() {
        // App updates clear pending alarms. Without this, every update silently disables
        // reminders until the next one happens to be scheduled.
        assertThat(receiversFor(Intent.ACTION_MY_PACKAGE_REPLACED))
            .contains("com.okayanshul.docaction.actions.reminder.BootReceiver")
    }

    private fun handlesShareOf(mimeType: String, action: String = Intent.ACTION_SEND) =
        packages.queryIntentActivities(
            Intent(action).setType(mimeType).setPackage(context.packageName),
            0,
        ).isNotEmpty()

    @Test
    fun everyFormatTheEngineReadsIsOfferedInTheShareSheet() {
        // These drifted apart once already: CSV was declared for SEND_MULTIPLE but not SEND,
        // so sharing three spreadsheets offered DocAction and sharing one did not. The filters
        // are invisible until a user reports that the app "doesn't show up", which is why they
        // are asserted rather than read.
        listOf(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "text/csv",
            "image/jpeg",
            "image/png",
        ).forEach { type ->
            assertThat(handlesShareOf(type)).isTrue()
            assertThat(handlesShareOf(type, Intent.ACTION_SEND_MULTIPLE)).isTrue()
        }
    }

    @Test
    fun sharedTextReachesTheApp() {
        // The "class rep posts the schedule as a message" case. MainActivity has always read
        // EXTRA_TEXT and the engine has goldens for it, but with no text/plain filter the
        // whole path was unreachable from the share sheet.
        assertThat(handlesShareOf("text/plain")).isTrue()
    }

    @Test
    fun formatsTheEngineCannotReadAreNotOffered() {
        // Appearing in the share sheet for a .docx and then refusing it is worse than never
        // appearing: the user has already chosen us by then.
        listOf(
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/zip",
            "video/mp4",
            "audio/mpeg",
        ).forEach { type ->
            assertThat(handlesShareOf(type)).isFalse()
        }
    }

    @Test
    fun theAppCannotReachTheNetwork() {
        // The product's central privacy claim is structural, not a policy: with no INTERNET
        // permission a document cannot be uploaded even by a mistake in our own code.
        val declared = packages
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            .orEmpty()
            .toList()

        assertThat(declared).doesNotContain(android.Manifest.permission.INTERNET)
        assertThat(declared).doesNotContain(android.Manifest.permission.ACCESS_NETWORK_STATE)
    }

    @Test
    fun theRequiredPermissionsAreDeclaredAndTheRiskyOnesAreNot() {
        val declared = packages
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            .orEmpty()
            .toList()

        assertThat(declared).containsAtLeast(
            android.Manifest.permission.POST_NOTIFICATIONS,
            android.Manifest.permission.SCHEDULE_EXACT_ALARM,
            android.Manifest.permission.RECEIVE_BOOT_COMPLETED,
        )

        // Both are restricted to calling and alarm apps and are reviewed case-by-case.
        // A document app requesting either risks the whole release, not just the feature,
        // so their absence is asserted rather than assumed.
        assertThat(declared).doesNotContain("android.permission.USE_EXACT_ALARM")
        assertThat(declared).doesNotContain("android.permission.USE_FULL_SCREEN_INTENT")

        // Storage is never needed: SAF and the photo picker cover every input.
        assertThat(declared).doesNotContain("android.permission.READ_EXTERNAL_STORAGE")
        assertThat(declared).doesNotContain("android.permission.MANAGE_EXTERNAL_STORAGE")
    }
}
