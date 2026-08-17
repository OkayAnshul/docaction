package com.okayanshul.docaction.actions.calendar

import android.content.ContentUris
import android.provider.CalendarContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.okayanshul.docaction.core.database.Databases
import com.okayanshul.docaction.domain.BoundingBox
import com.okayanshul.docaction.domain.CalendarEventCandidate
import com.okayanshul.docaction.domain.Confident
import com.okayanshul.docaction.domain.EntryId
import com.okayanshul.docaction.domain.ImportId
import com.okayanshul.docaction.domain.NotificationOwner
import com.okayanshul.docaction.domain.Outcome
import com.okayanshul.docaction.domain.ScheduleEntry
import com.okayanshul.docaction.domain.SourceReference
import com.okayanshul.docaction.domain.TermBounds
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Does the calendar add a reminder of its own to our events?
 *
 * The open question behind the whole notification design. DocAction schedules its own
 * ladder — several nudges, the last one repeating until the class starts — which is more
 * than a calendar app does. If the provider or the calendar app then attaches its own
 * default reminder to the same event, the user gets two notifications for one class, ours
 * and theirs, and the obvious conclusion is that our app is broken.
 *
 * This measures it against the real provider rather than reasoning about it.
 */
@RunWith(AndroidJUnit4::class)
class DefaultReminderTest {

    /**
     * Without this the suite reads no calendars, finds nothing writable, and *skips* — a
     * green build that proved nothing. It is a rule rather than a manifest permission
     * because instrumented tests are granted at runtime.
     */
    @get:Rule
    val calendarPermission: GrantPermissionRule = GrantPermissionRule.grant(
        android.Manifest.permission.READ_CALENDAR,
        android.Manifest.permission.WRITE_CALENDAR,
    )

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")
    private val at = SourceReference.PdfSpan(0, BoundingBox(0f, 0f, 1f, 1f))
    private val term = TermBounds(LocalDate.of(2026, 8, 17), LocalDate.of(2026, 12, 5))

    private lateinit var handle: Databases.Handle
    private lateinit var target: CalendarTarget
    private val written = mutableListOf<ImportId>()

    @Before
    fun setUp() {
        handle = Databases.inMemory(context)
        // Created here rather than assumed: relying on another test class to have made one
        // makes this suite's result depend on execution order, and a skip that reads as a
        // pass is worse than a failure.
        val writable = LocalCalendar.ensure(context)
        assumeTrue("could not create a writable calendar", writable.isNotEmpty())
        target = writable.first()
    }

    @After
    fun tearDown() = runBlocking {
        written.forEach { executor(NotificationOwner.DocAction).revert(it) }
        handle.close()
    }

    private fun executor(owner: NotificationOwner) = CalendarEventExecutor(
        context = context,
        createdEvents = handle.createdEvents,
        notificationOwner = owner,
    )

    private fun candidate(title: String) = ScheduleEntry(
        id = EntryId(UUID.randomUUID().toString()),
        title = Confident.High(title, at),
        weekday = Confident.High(DayOfWeek.TUESDAY, at),
        startTime = Confident.High(LocalTime.of(9, 0), at),
        endTime = Confident.High(LocalTime.of(10, 0), at),
    ).let { (CalendarEventCandidate.from(it, zone, term) as CalendarEventCandidate.Result.Accepted).candidate }

    private suspend fun write(owner: NotificationOwner, title: String): Long {
        val importId = ImportId(UUID.randomUUID().toString())
        written += importId
        val report = executor(owner).execute(importId, listOf(candidate(title)), target) { _, _ -> }
        assertThat(report).isInstanceOf(Outcome.Success::class.java)

        var id = -1L
        context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(CalendarContract.Events._ID),
            "${CalendarContract.Events.CUSTOM_APP_URI} LIKE ?",
            arrayOf("docaction://import/${importId.value}/%"),
            null,
        )?.use { if (it.moveToFirst()) id = it.getLong(0) }
        assertThat(id).isNotEqualTo(-1L)
        return id
    }

    private fun remindersOn(eventId: Long): List<Int> {
        val minutes = mutableListOf<Int>()
        context.contentResolver.query(
            CalendarContract.Reminders.CONTENT_URI,
            arrayOf(CalendarContract.Reminders.MINUTES),
            "${CalendarContract.Reminders.EVENT_ID} = ?",
            arrayOf(eventId.toString()),
            null,
        )?.use { while (it.moveToNext()) minutes += it.getInt(0) }
        return minutes
    }

    private fun hasAlarm(eventId: Long): Int {
        var flag = -1
        context.contentResolver.query(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
            arrayOf(CalendarContract.Events.HAS_ALARM),
            null, null, null,
        )?.use { if (it.moveToFirst()) flag = it.getInt(0) }
        return flag
    }

    @Test
    fun whenDocActionOwnsNotificationsTheProviderAddsNoneOfItsOwn() = runBlocking {
        val eventId = write(NotificationOwner.DocAction, "DocAction owns this")

        // The finding this test exists for: an event written with HAS_ALARM = 0 and no
        // Reminders row stays that way. The provider does not attach a default, so our
        // ladder is the only thing that will notify.
        assertThat(remindersOn(eventId)).isEmpty()
        assertThat(hasAlarm(eventId)).isEqualTo(0)
    }

    @Test
    fun whenTheCalendarOwnsNotificationsExactlyOneReminderIsWritten() = runBlocking {
        val eventId = write(NotificationOwner.CalendarApp, "Calendar owns this")

        // One, not two: the provider does not double up on an explicit reminder either.
        assertThat(remindersOn(eventId)).hasSize(1)
        assertThat(hasAlarm(eventId)).isEqualTo(1)
    }

    @Test
    fun theTwoOwnersAreMutuallyExclusive() = runBlocking {
        // The property that protects the user from two notifications for one class: whoever
        // owns notifications, exactly one of the two mechanisms is armed. Asserted across
        // both settings rather than trusting the flag, because the duplicate only appears on
        // a real device weeks later, at 8:55 in the morning, twice.
        val ours = write(NotificationOwner.DocAction, "Ours")
        val theirs = write(NotificationOwner.CalendarApp, "Theirs")

        assertThat(remindersOn(ours)).isEmpty()
        assertThat(remindersOn(theirs)).hasSize(1)
    }

    /*
     * A default reminder configured on the *calendar account* is not testable from here and
     * does not need to be. There is no app-writable column for it: the calendar app applies
     * its default when a user creates an event in that app's own UI, and an event inserted
     * through the provider — which is what we do — never passes through that path. The two
     * tests above show the provider attaches nothing of its own, which is the part that was
     * genuinely unknown.
     */
}
