package com.okayanshul.docaction.actions.calendar

import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import java.time.ZoneId

/**
 * A writable calendar on a device that has no accounts.
 *
 * A bare emulator has no calendar at all, so every test that writes one would `assumeTrue`
 * its way to a green build that proved nothing. Worse, a test that *relies on another test*
 * having created the calendar passes or skips depending on execution order — which is how
 * `AllDayWriteTest` reported two silent skips on a freshly booted emulator while claiming
 * success.
 *
 * `ACCOUNT_TYPE_LOCAL` is the device-only account type: no sync, no network, and
 * indistinguishable from any other writable calendar as far as the provider is concerned,
 * which is exactly what makes it a faithful target.
 */
internal object LocalCalendar {

    const val ACCOUNT = "docaction.test.local"

    /** Idempotent: returns the existing test calendar, or creates one. */
    fun ensure(context: Context, zone: ZoneId = ZoneId.systemDefault()): List<CalendarTarget> {
        CalendarTargets(context).writable().let { if (it.isNotEmpty()) return it }

        val uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT)
            .appendQueryParameter(
                CalendarContract.Calendars.ACCOUNT_TYPE,
                CalendarContract.ACCOUNT_TYPE_LOCAL,
            )
            .build()

        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME, "DocAction Test")
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "DocAction Test")
            put(CalendarContract.Calendars.CALENDAR_COLOR, 0x1B3A5C)
            put(
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                CalendarContract.Calendars.CAL_ACCESS_OWNER,
            )
            put(CalendarContract.Calendars.OWNER_ACCOUNT, ACCOUNT)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, zone.id)
        }

        runCatching { context.contentResolver.insert(uri, values) }
        return CalendarTargets(context).writable()
    }
}
