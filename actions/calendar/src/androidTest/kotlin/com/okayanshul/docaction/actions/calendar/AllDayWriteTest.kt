package com.okayanshul.docaction.actions.calendar

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
import com.okayanshul.docaction.domain.Outcome
import com.okayanshul.docaction.domain.ScheduleEntry
import com.okayanshul.docaction.domain.SourceReference
import com.okayanshul.docaction.domain.TermBounds
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * An all-day event has to land on the day the document said.
 *
 * The trap is specific and silent: the provider measures an all-day row in **UTC** midnights.
 * Pass local midnight with `ALL_DAY = 1` and the row is stored 5½ hours early in Kolkata,
 * which the calendar app then renders as *the previous day*. Nothing errors. The user simply
 * finds their bill due on the 14th.
 *
 * It cannot be caught in the emulator's default zone either, because UTC and UTC agree. So
 * this asserts the stored instants directly rather than trusting a rendering.
 */
@RunWith(AndroidJUnit4::class)
class AllDayWriteTest {

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
    private val at = SourceReference.PdfSpan(0, BoundingBox(0f, 0f, 1f, 1f))
    private val term = TermBounds(LocalDate.of(2026, 8, 17), LocalDate.of(2026, 12, 5))
    private val due = LocalDate.of(2026, 9, 15)

    private lateinit var handle: Databases.Handle
    private lateinit var executor: CalendarEventExecutor
    private lateinit var target: CalendarTarget
    private val written = mutableListOf<ImportId>()

    @Before
    fun setUp() {
        handle = Databases.inMemory(context)
        executor = CalendarEventExecutor(context, handle.createdEvents)
        // Created here rather than assumed: relying on another test class to have made one
        // makes this suite's result depend on execution order, and a skip that reads as a
        // pass is worse than a failure.
        val writable = LocalCalendar.ensure(context)
        assumeTrue("could not create a writable calendar", writable.isNotEmpty())
        target = writable.first()
    }

    @After
    fun tearDown() = runBlocking {
        written.forEach { executor.revert(it) }
        handle.close()
    }

    /** Deliberately a zone well east of UTC — where the bug shows. */
    private fun allDayCandidate(zone: ZoneId = ZoneId.of("Asia/Kolkata")): CalendarEventCandidate {
        val entry = ScheduleEntry(
            id = EntryId(UUID.randomUUID().toString()),
            title = Confident.High("Electricity bill due", at),
            date = Confident.High(due, at),
            startTime = Confident.Missing("no time given"),
            endTime = Confident.Missing("no end time given"),
        )
        return (CalendarEventCandidate.from(entry, zone, term, allowAllDay = true)
            as CalendarEventCandidate.Result.Accepted).candidate
    }

    private fun query(importId: ImportId, column: String): Long {
        var value = -1L
        context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(column),
            "${CalendarContract.Events.CUSTOM_APP_URI} LIKE ?",
            arrayOf("docaction://import/${importId.value}/%"),
            null,
        )?.use { if (it.moveToFirst()) value = it.getLong(0) }
        return value
    }

    private fun queryText(importId: ImportId, column: String): String? {
        var value: String? = null
        context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(column),
            "${CalendarContract.Events.CUSTOM_APP_URI} LIKE ?",
            arrayOf("docaction://import/${importId.value}/%"),
            null,
        )?.use { if (it.moveToFirst()) value = it.getString(0) }
        return value
    }

    @Test
    fun anAllDayEventIsStoredAtUtcMidnightOnTheStatedDay() = runBlocking {
        val importId = ImportId(UUID.randomUUID().toString())
        written += importId

        val report = executor.execute(importId, listOf(allDayCandidate()), target) { _, _ -> }
        assertThat(report).isInstanceOf(Outcome.Success::class.java)

        val expected = due.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        assertThat(query(importId, CalendarContract.Events.DTSTART)).isEqualTo(expected)

        // A whole day, ending at the next UTC midnight.
        assertThat(query(importId, CalendarContract.Events.DTEND))
            .isEqualTo(due.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())

        assertThat(query(importId, CalendarContract.Events.ALL_DAY)).isEqualTo(1)
        // The provider requires UTC for an all-day row; the user's own zone would be wrong
        // here even though it is right for every timed event we write.
        assertThat(queryText(importId, CalendarContract.Events.EVENT_TIMEZONE)).isEqualTo("UTC")
    }

    @Test
    fun theStoredDayDoesNotDependOnTheUsersTimeZone() = runBlocking {
        // Kolkata is +5:30 and Honolulu is −10:00. If the write used local midnight, these
        // two would be stored sixteen hours apart and render on different days.
        val zones = listOf(ZoneId.of("Asia/Kolkata"), ZoneId.of("Pacific/Honolulu"))
        val stored = zones.map { zone ->
            val importId = ImportId(UUID.randomUUID().toString())
            written += importId
            executor.execute(importId, listOf(allDayCandidate(zone)), target) { _, _ -> }
            query(importId, CalendarContract.Events.DTSTART)
        }

        assertThat(stored.distinct()).hasSize(1)
        assertThat(Instant.ofEpochMilli(stored.first()).atZone(ZoneOffset.UTC).toLocalDate())
            .isEqualTo(due)
    }
}
