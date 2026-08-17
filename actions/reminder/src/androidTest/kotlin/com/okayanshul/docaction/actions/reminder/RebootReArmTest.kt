package com.okayanshul.docaction.actions.reminder

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.okayanshul.docaction.core.database.Databases
import com.okayanshul.docaction.core.database.ScheduledReminderEntity
import com.okayanshul.docaction.domain.ImportId
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The two halves of "reminders survive a reboot", split so a shell script can put a real
 * reboot between them.
 *
 * Alarms do not survive a restart — the system drops every one — so the feature depends
 * entirely on `BootReceiver` running and re-arming the pending window. That is untestable
 * from inside a single instrumented run, and it is exactly the kind of thing that appears
 * to work until someone's phone restarts overnight and their 8am class goes unannounced.
 *
 * These write to the **production** database on purpose. An in-memory one would not be there
 * after the reboot, which is the whole point.
 *
 * Run as: `plant`, then `adb reboot`, then `expectReArmed`.
 *
 * **Result, on API 36 (2026-08-12).** It works. `am_proc_start … for broadcast
 * {…BootReceiver}` appears about forty seconds after boot, and `dumpsys alarm` shows the
 * alarm back with `origWhen` unchanged (1786626117452, the same wall-clock moment) and
 * `whenElapsed` rebased from 520645103 to 107979488 against the new boot clock. That pair is
 * what a correct re-arm looks like: same instant, recomputed against a clock that restarted.
 *
 * Worth knowing for anyone repeating this: check `dumpsys alarm` **after** the receiver has
 * actually run. Grepping immediately post-boot shows nothing and looks exactly like failure.
 */
@RunWith(AndroidJUnit4::class)
@ManualCheck
class RebootReArmTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dao = Databases.reminders(context)

    private val importId = ImportId("reboot-check")

    @Test
    fun plant() = runBlocking {
        dao.deleteForImport(importId.value)
        NotificationPublisher(context).ensureChannels()

        // Inside the scheduler's 48-hour window, and far enough ahead that the test itself
        // cannot race the alarm.
        val due = Instant.now().plus(Duration.ofHours(30))
        dao.upsert(
            listOf(
                ScheduledReminderEntity(
                    id = "reboot-check-1",
                    importId = importId.value,
                    entryId = "entry-1",
                    customAppUri = "docaction://import/${importId.value}/entry-1",
                    title = "Data Structures",
                    detail = "Room K10",
                    kind = "Class",
                    dueAtEpochMillis = due.toEpochMilli(),
                    eventAtEpochMillis = due.plus(Duration.ofMinutes(15)).toEpochMilli(),
                    allDay = false,
                    armed = false,
                    firedAt = null,
                    inexact = false,
                ),
            ),
        )

        val report = ReminderScheduler(context, dao).armWindow()
        assertThat(report.armed).isAtLeast(1)
        assertThat(dao.byId("reboot-check-1")!!.armed).isTrue()
    }

    /**
     * After a real reboot: the row is still pending and has been armed again.
     *
     * `armed` is reset by nothing here — the check that matters is that [BootReceiver] ran
     * and the alarm exists again, which the shell script confirms from `dumpsys alarm`. This
     * asserts the half that lives in the database: the reminder was not consumed, dropped,
     * or marked delivered by the restart.
     */
    @Test
    fun expectReArmed() = runBlocking {
        val row = dao.byId("reboot-check-1")
        assertThat(row).isNotNull()
        assertThat(row!!.firedAt).isNull()
        assertThat(row.armed).isTrue()
        assertThat(row.dueAtEpochMillis).isGreaterThan(System.currentTimeMillis())
    }

    @Test
    fun cleanUp() = runBlocking {
        dao.deleteForImport(importId.value)
        assertThat(dao.countForImport(importId.value)).isEqualTo(0)
    }
}
