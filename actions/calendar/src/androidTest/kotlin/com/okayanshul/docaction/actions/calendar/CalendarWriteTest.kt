package com.okayanshul.docaction.actions.calendar

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.google.common.truth.Truth.assertThat
import com.okayanshul.docaction.core.database.Databases
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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The calendar write, against the **real** Calendar Provider.
 *
 * Mocked calendar tests prove nothing here: every bug worth catching lives in the provider's
 * behaviour — batch limits, the recurring-event `DTEND` rejection, what a delete predicate
 * actually matches. The single most important assertion in this file is that undo leaves a
 * control event, created by hand, completely untouched.
 */
@RunWith(AndroidJUnit4::class)
class CalendarWriteTest {

    @get:Rule
    val calendarPermission: GrantPermissionRule = GrantPermissionRule.grant(
        android.Manifest.permission.READ_CALENDAR,
        android.Manifest.permission.WRITE_CALENDAR,
    )

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val zone: ZoneId = ZoneId.systemDefault()
    private val term = TermBounds(LocalDate.of(2026, 8, 17), LocalDate.of(2026, 12, 5))
    private val at = SourceReference.PdfSpan(0, com.okayanshul.docaction.domain.BoundingBox(0f, 0f, 1f, 1f))

    private lateinit var handle: Databases.Handle
    private lateinit var executor: CalendarEventExecutor
    private lateinit var target: CalendarTarget
    private var controlEventId: Long = -1
    private val imports = mutableListOf<ImportId>()

    private companion object {
        const val TEST_ACCOUNT = "docaction.test.local"
    }

    @Before
    fun setUp() {
        handle = Databases.inMemory(context)
        executor = CalendarEventExecutor(context, handle.createdEvents)

        // A bare emulator has no account and therefore no calendar, which would skip every
        // test in this file and report a green build that proved nothing. Create a local
        // calendar so these assertions actually run.
        val writable = CalendarTargets(context).writable().ifEmpty {
            createLocalCalendar()
            CalendarTargets(context).writable()
        }
        assumeTrue("could not obtain a writable calendar", writable.isNotEmpty())
        target = writable.first()

        controlEventId = insertControlEvent()
    }

    /**
     * A local calendar, inserted as a sync adapter.
     *
     * `ACCOUNT_TYPE_LOCAL` is the device-only account type: no sync, no network, and it
     * behaves like any other writable calendar as far as the provider is concerned — which
     * is exactly what makes it a faithful test target.
     */
    private fun createLocalCalendar() {
        val uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, TEST_ACCOUNT)
            .appendQueryParameter(
                CalendarContract.Calendars.ACCOUNT_TYPE,
                CalendarContract.ACCOUNT_TYPE_LOCAL,
            )
            .build()

        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, TEST_ACCOUNT)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME, "DocAction Test")
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "DocAction Test")
            put(CalendarContract.Calendars.CALENDAR_COLOR, 0x1B3A5C)
            put(
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                CalendarContract.Calendars.CAL_ACCESS_OWNER,
            )
            put(CalendarContract.Calendars.OWNER_ACCOUNT, TEST_ACCOUNT)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, zone.id)
        }

        runCatching { context.contentResolver.insert(uri, values) }
    }

    @After
    fun tearDown() = runBlocking {
        imports.forEach { executor.revert(it) }
        if (controlEventId > 0) {
            context.contentResolver.delete(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, controlEventId),
                null,
                null,
            )
        }
        handle.close()
    }

    /** An event the user "created themselves". Undo must never touch it. */
    private fun insertControlEvent(): Long {
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, target.calendarId)
            put(CalendarContract.Events.TITLE, "Control event — not ours")
            put(CalendarContract.Events.DTSTART, System.currentTimeMillis() + 3_600_000)
            put(CalendarContract.Events.DTEND, System.currentTimeMillis() + 7_200_000)
            put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
        }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
        return uri?.let { ContentUris.parseId(it) } ?: -1
    }

    private fun candidate(id: String, day: DayOfWeek, title: String, recurring: Boolean = true) =
        (
            CalendarEventCandidate.from(
                ScheduleEntry(
                    id = EntryId(id),
                    title = Confident.High(title, at),
                    weekday = if (recurring) Confident.High(day, at) else Confident.Missing("n/a"),
                    date = if (recurring) Confident.Missing("n/a") else Confident.High(LocalDate.of(2026, 9, 18), at),
                    startTime = Confident.High(LocalTime.of(9, 0), at),
                    endTime = Confident.High(LocalTime.of(10, 0), at),
                    location = Confident.High("K10", at),
                ),
                zone,
                term,
            ) as CalendarEventCandidate.Result.Accepted
            ).candidate

    private fun newImport(): ImportId = ImportId("test-" + System.nanoTime()).also { imports += it }

    private fun ourEventCount(importId: ImportId): Int =
        context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(CalendarContract.Events._ID),
            "${CalendarContract.Events.CUSTOM_APP_PACKAGE} = ? AND " +
                "${CalendarContract.Events.CUSTOM_APP_URI} LIKE ? AND " +
                "${CalendarContract.Events.DELETED} = 0",
            arrayOf(CalendarEventExecutor.PACKAGE, "docaction://import/${importId.value}/%"),
            null,
        )?.use { it.count } ?: 0

    private fun controlStillExists(): Boolean =
        context.contentResolver.query(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, controlEventId),
            arrayOf(CalendarContract.Events._ID, CalendarContract.Events.DELETED),
            null,
            null,
            null,
        )?.use { it.moveToFirst() && it.getInt(1) == 0 } ?: false

    // --- writing ---

    @Test
    fun writesEventsAndReportsOnlyWhatTheCalendarAccepted() = runBlocking<Unit> {
        val importId = newImport()
        val candidates = listOf(
            candidate("e1", DayOfWeek.MONDAY, "Data Structures"),
            candidate("e2", DayOfWeek.TUESDAY, "Operating Systems"),
        )

        val report = (executor.execute(importId, candidates, target) { _, _ -> }
            as Outcome.Success).value

        assertThat(report.written).hasSize(2)
        assertThat(report.isComplete).isTrue()
        // The count is confirmed by reading the calendar back, not by what we attempted.
        assertThat(ourEventCount(importId)).isEqualTo(2)
    }

    @Test
    fun aRecurringEventIsWrittenOnceWithAnRruleAndADuration() = runBlocking<Unit> {
        val importId = newImport()

        executor.execute(importId, listOf(candidate("e1", DayOfWeek.MONDAY, "DSA")), target) { _, _ -> }

        context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(
                CalendarContract.Events.RRULE,
                CalendarContract.Events.DURATION,
                CalendarContract.Events.DTEND,
                CalendarContract.Events.EVENT_TIMEZONE,
            ),
            "${CalendarContract.Events.CUSTOM_APP_URI} LIKE ?",
            arrayOf("docaction://import/${importId.value}/%"),
            null,
        )!!.use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            // One row, not fifteen — a term of classes is one repeating thing.
            assertThat(cursor.count).isEqualTo(1)
            assertThat(cursor.getString(0)).contains("FREQ=WEEKLY")
            assertThat(cursor.getString(0)).contains("UNTIL=")
            assertThat(cursor.getString(1)).isNotNull()
            // DTEND on a recurring event is rejected by the provider; it must be absent.
            assertThat(cursor.isNull(2)).isTrue()
            assertThat(cursor.getString(3)).isEqualTo(zone.id)
        }
    }

    @Test
    fun aBatchLargerThanOneChunkIsWrittenCompletely() = runBlocking<Unit> {
        val importId = newImport()
        // Beyond the 150-op chunk, which is where an unchunked applyBatch would blow the
        // ~1 MB Binder buffer and half-write the calendar.
        val many = (1..170).map { candidate("e$it", DayOfWeek.entries[it % 5], "Class $it") }

        val report = (executor.execute(importId, many, target) { _, _ -> } as Outcome.Success).value

        assertThat(report.written).hasSize(170)
        assertThat(ourEventCount(importId)).isEqualTo(170)
    }

    @Test
    fun everyWrittenEventCarriesItsProvenance() = runBlocking<Unit> {
        val importId = newImport()
        executor.execute(importId, listOf(candidate("e1", DayOfWeek.MONDAY, "DSA")), target) { _, _ -> }

        context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(
                CalendarContract.Events.CUSTOM_APP_PACKAGE,
                CalendarContract.Events.CUSTOM_APP_URI,
                CalendarContract.Events.UID_2445,
            ),
            "${CalendarContract.Events.CUSTOM_APP_URI} LIKE ?",
            arrayOf("docaction://import/${importId.value}/%"),
            null,
        )!!.use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo(CalendarEventExecutor.PACKAGE)
            assertThat(cursor.getString(1)).isEqualTo("docaction://import/${importId.value}/e1")
            assertThat(cursor.getString(2)).isNotEmpty()
        }
    }

    // --- undo: the assertion that matters most ---

    @Test
    fun undoRemovesOnlyThisImportAndLeavesTheUsersOwnEventAlone() = runBlocking<Unit> {
        val mine = newImport()
        val other = newImport()

        executor.execute(mine, listOf(candidate("a1", DayOfWeek.MONDAY, "Mine")), target) { _, _ -> }
        executor.execute(other, listOf(candidate("b1", DayOfWeek.TUESDAY, "Other import")), target) { _, _ -> }

        assertThat(controlStillExists()).isTrue()

        val report = (executor.revert(mine) as Outcome.Success).value

        assertThat(report.removed).isEqualTo(1)
        assertThat(ourEventCount(mine)).isEqualTo(0)
        // The other import survives...
        assertThat(ourEventCount(other)).isEqualTo(1)
        // ...and so does the event the user made themselves.
        assertThat(controlStillExists()).isTrue()
    }

    @Test
    fun undoOfAnImportThatWroteNothingRemovesNothing() = runBlocking<Unit> {
        val empty = newImport()

        val report = (executor.revert(empty) as Outcome.Success).value

        assertThat(report.removed).isEqualTo(0)
        assertThat(controlStillExists()).isTrue()
    }

    // --- duplicates ---

    @Test
    fun reimportingTheSameScheduleIsDetectedBeforeWriting() = runBlocking<Unit> {
        val first = newImport()
        val candidates = listOf(candidate("e1", DayOfWeek.MONDAY, "Data Structures"))
        executor.execute(first, candidates, target) { _, _ -> }

        val duplicates = (executor.findDuplicates(candidates, target) as Outcome.Success).value

        assertThat(duplicates).isNotEmpty()
        assertThat(duplicates.first().createdByUs).isTrue()
    }

    @Test
    fun anUnrelatedScheduleIsNotFlaggedAsDuplicate() = runBlocking<Unit> {
        val importId = newImport()
        executor.execute(importId, listOf(candidate("e1", DayOfWeek.MONDAY, "Data Structures")), target) { _, _ -> }

        val different = listOf(candidate("z9", DayOfWeek.FRIDAY, "Completely Different Subject"))
        val duplicates = (executor.findDuplicates(different, target) as Outcome.Success).value

        assertThat(duplicates).isEmpty()
    }

    // --- notification ownership ---

    @Test
    fun whenDocActionOwnsNotificationsTheCalendarRowCarriesNoReminder() = runBlocking<Unit> {
        val importId = newImport()
        executor.execute(importId, listOf(candidate("e1", DayOfWeek.MONDAY, "DSA")), target) { _, _ -> }

        val eventId = handle.createdEvents.forImport(importId.value).single().calendarEventId
        val reminders = context.contentResolver.query(
            CalendarContract.Reminders.CONTENT_URI,
            arrayOf(CalendarContract.Reminders._ID),
            "${CalendarContract.Reminders.EVENT_ID} = ?",
            arrayOf(eventId.toString()),
            null,
        )?.use { it.count } ?: 0

        assertThat(reminders).isEqualTo(0)
    }

    @Test
    fun whenTheCalendarOwnsNotificationsExactlyOneReminderIsWritten() = runBlocking<Unit> {
        val importId = newImport()
        val handing = CalendarEventExecutor(
            context,
            handle.createdEvents,
            notificationOwner = NotificationOwner.CalendarApp,
        )

        handing.execute(importId, listOf(candidate("e1", DayOfWeek.MONDAY, "DSA")), target) { _, _ -> }

        val eventId = handle.createdEvents.forImport(importId.value).single().calendarEventId
        val reminders = context.contentResolver.query(
            CalendarContract.Reminders.CONTENT_URI,
            arrayOf(CalendarContract.Reminders._ID),
            "${CalendarContract.Reminders.EVENT_ID} = ?",
            arrayOf(eventId.toString()),
            null,
        )?.use { it.count } ?: 0

        assertThat(reminders).isEqualTo(1)
    }

    // --- targets ---

    @Test
    fun onlyWritableCalendarsAreOffered() {
        val writable = CalendarTargets(context).writable()

        assertThat(writable).isNotEmpty()
        assertThat(writable.all { it.calendarId > 0 }).isTrue()
        assertThat(writable.all { it.label.isNotBlank() }).isTrue()
    }
}
