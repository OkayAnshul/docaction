package com.okayanshul.docaction.actions.reminder

import android.app.NotificationManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.okayanshul.docaction.core.database.Databases
import com.okayanshul.docaction.core.database.ReminderDao
import com.okayanshul.docaction.core.database.ScheduledReminderEntity
import com.okayanshul.docaction.domain.Confident
import com.okayanshul.docaction.domain.EntryId
import com.okayanshul.docaction.domain.ImportId
import com.okayanshul.docaction.domain.ReminderCandidate
import com.okayanshul.docaction.domain.ReminderKind
import com.okayanshul.docaction.domain.ReminderLadder
import com.okayanshul.docaction.domain.ScheduleEntry
import com.okayanshul.docaction.domain.SourceReference
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/**
 * The reminder engine on a real device.
 *
 * The properties tested here are the ones that cannot be checked on the JVM and that fail
 * silently in production if wrong: a notification that never posts, alarms that vanish on
 * reboot, and a cancellation that takes someone else's reminders with it.
 */
@RunWith(AndroidJUnit4::class)
class ReminderEngineTest {

    /**
     * Granted up front so the notification assertion is unconditional. Without this the
     * test can pass on API 33+ purely because the permission was missing — which is exactly
     * the silent success this suite exists to rule out.
     */
    @get:Rule
    val notificationPermission: TestRule =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            TestRule { base, _ -> base }
        }

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var handle: Databases.Handle
    private lateinit var dao: ReminderDao
    private lateinit var scheduler: ReminderScheduler

    private val zone: ZoneId = ZoneId.systemDefault()

    @Before
    fun setUp() {
        handle = Databases.inMemory(context)
        dao = handle.reminders
        scheduler = ReminderScheduler(context, dao)
        NotificationPublisher(context).ensureChannels()
    }

    @After
    fun tearDown() {
        handle.close()
        context.getSystemService(NotificationManager::class.java).cancelAll()
    }

    private fun reminder(
        id: String,
        importId: String,
        dueIn: Duration,
        title: String = "Data Structures",
    ) = ScheduledReminderEntity(
        id = id,
        importId = importId,
        entryId = "e1",
        customAppUri = "docaction://import/$importId/e1",
        title = title,
        detail = "C25-A107",
        kind = ReminderKind.Class.name,
        dueAtEpochMillis = Instant.now().plus(dueIn).toEpochMilli(),
        eventAtEpochMillis = Instant.now().plus(dueIn).plus(Duration.ofMinutes(5)).toEpochMilli(),
        allDay = false,
        armed = false,
        firedAt = null,
        inexact = false,
    )

    // --- the reminder actually reaches the user ---

    @Test
    fun postsANotification() = runBlocking {
        val row = reminder("r1", "import-a", Duration.ofMinutes(5))
        dao.upsert(listOf(row))

        val posted = NotificationPublisher(context).post(row)

        assertThat(posted).isTrue()
        val manager = context.getSystemService(NotificationManager::class.java)
        val shown = manager.activeNotifications.firstOrNull { it.id == row.id.hashCode() }
        assertThat(shown).isNotNull()
        // The lead time must be in the headline — "In 5 minutes" is the whole point.
        val title = shown!!.notification.extras.getString(android.app.Notification.EXTRA_TITLE)
        assertThat(title).contains("minutes")
    }

    // --- the rolling window ---

    @Test
    fun armsOnlyWhatFallsInsideTheWindow() = runBlocking {
        dao.upsert(
            listOf(
                reminder("soon", "import-a", Duration.ofHours(1)),
                reminder("alsoSoon", "import-a", Duration.ofHours(10)),
                // Well beyond the 48-hour window — a term's worth of classes lives here.
                reminder("later", "import-a", Duration.ofDays(20)),
            )
        )

        val report = scheduler.armWindow()

        assertThat(report.armed).isEqualTo(2)
        assertThat(dao.byId("later")!!.armed).isFalse()
    }

    @Test
    fun aReminderAlreadyFiredIsNotArmedAgain() = runBlocking {
        dao.upsert(listOf(reminder("r1", "import-a", Duration.ofHours(1))))
        dao.markFired("r1", Instant.now().toEpochMilli())

        assertThat(scheduler.armWindow().armed).isEqualTo(0)
    }

    // --- degradation, never silent loss ---

    @Test
    fun schedulingSucceedsEvenWhenExactAlarmsAreUnavailable() = runBlocking {
        dao.upsert(listOf(reminder("r1", "import-a", Duration.ofHours(2))))

        val report = scheduler.armWindow()

        // Whether or not this device grants exact alarms, the reminder is armed. If it had
        // to degrade, the report says so — the user is never left with a silent failure.
        assertThat(report.armed).isEqualTo(1)
        assertThat(dao.byId("r1")!!.armed).isTrue()
        if (report.degraded) assertThat(dao.byId("r1")!!.inexact).isTrue()
    }

    // --- cancellation is surgical ---

    @Test
    fun cancellingAnImportLeavesOtherImportsAlone() = runBlocking {
        dao.upsert(
            listOf(
                reminder("a1", "import-a", Duration.ofHours(1)),
                reminder("a2", "import-a", Duration.ofHours(2)),
                reminder("b1", "import-b", Duration.ofHours(1), title = "Control"),
            )
        )
        scheduler.armWindow()

        val removed = scheduler.cancelImport(ImportId("import-a"))

        assertThat(removed).isEqualTo(2)
        assertThat(dao.countForImport("import-a")).isEqualTo(0)
        // The control row from a different import must survive untouched.
        assertThat(dao.countForImport("import-b")).isEqualTo(1)
        assertThat(dao.byId("b1")).isNotNull()
    }

    // --- reboot survival: the failure with no symptom ---

    @Test
    fun pendingRemindersSurviveAReArm() = runBlocking {
        dao.upsert(listOf(reminder("r1", "import-a", Duration.ofHours(1))))
        scheduler.armWindow()

        // Simulate what BootReceiver does: drop what already elapsed, then re-arm.
        dao.deleteExpired(Instant.now().toEpochMilli())
        val report = scheduler.armWindow()

        assertThat(dao.byId("r1")).isNotNull()
        assertThat(report.armed).isEqualTo(1)
    }

    @Test
    fun remindersWhoseMomentPassedWhileOffAreNotFiredLate() = runBlocking {
        dao.upsert(listOf(reminder("stale", "import-a", Duration.ofHours(-3))))

        dao.deleteExpired(Instant.now().toEpochMilli())

        assertThat(dao.byId("stale")).isNull()
    }

    // --- planning from a candidate, end to end ---

    @Test
    fun planningADeadlineProducesLadderRungsAndNoMore() = runBlocking {
        val source = SourceReference.UserProvided("f", 0)
        val entry = ScheduleEntry(
            id = EntryId("e1"),
            title = Confident.High("Fee payment", source),
            date = Confident.High(LocalDate.now(zone).plusDays(2), source),
        )
        val candidate = (
            ReminderCandidate.from(entry, zone, ReminderKind.Deadline, ReminderLadder.Deadlines)
                as ReminderCandidate.Result.Accepted
            ).candidate

        val rows = ReminderPlanner().plan(ImportId("import-a"), candidate)
        dao.upsert(rows)

        assertThat(rows).isNotEmpty()
        // Every row belongs to this import, and carries the provenance undo relies on.
        assertThat(rows.all { it.customAppUri == "docaction://import/import-a/e1" }).isTrue()
        // Deterministic ids: re-planning overwrites rather than duplicating.
        dao.upsert(ReminderPlanner().plan(ImportId("import-a"), candidate))
        assertThat(dao.countForImport("import-a")).isEqualTo(rows.size)
    }

    @Test
    fun allDayRemindersAnchorAtAUsefulHourNotMidnight() {
        val source = SourceReference.UserProvided("f", 0)
        val entry = ScheduleEntry(
            id = EntryId("e1"),
            title = Confident.High("Premium due", source),
            date = Confident.High(LocalDate.of(2026, 9, 28), source),
        )

        val candidate = (
            ReminderCandidate.from(entry, zone, ReminderKind.Deadline, ReminderLadder.Deadlines)
                as ReminderCandidate.Result.Accepted
            ).candidate

        assertThat(candidate.allDay).isTrue()
        assertThat(candidate.dueAt.toLocalTime()).isNotEqualTo(LocalTime.MIDNIGHT)
    }
}
