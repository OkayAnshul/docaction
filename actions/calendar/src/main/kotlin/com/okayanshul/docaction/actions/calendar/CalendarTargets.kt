package com.okayanshul.docaction.actions.calendar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.okayanshul.docaction.domain.ActionTarget

/**
 * A calendar the user could write to.
 *
 * [isPrimary] only orders the list; it never selects. The user picks, always — silently
 * writing 42 events into a work account they forgot was on the device is a real harm and an
 * entirely avoidable one.
 */
data class CalendarTarget(
    override val id: String,
    override val label: String,
    val accountName: String,
    val colour: Int?,
    val isPrimary: Boolean,
) : ActionTarget {
    val calendarId: Long get() = id.toLong()
}

/** Reads the writable calendars on the device. */
class CalendarTargets(private val context: Context) {

    fun canRead(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.READ_CALENDAR,
    ) == PackageManager.PERMISSION_GRANTED

    fun canWrite(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.WRITE_CALENDAR,
    ) == PackageManager.PERMISSION_GRANTED

    /**
     * Calendars the user can actually add to.
     *
     * Filtered on access level rather than presence: a subscribed holiday calendar is
     * visible but read-only, and offering it would produce a write that fails for reasons
     * the user cannot act on.
     */
    fun writable(): List<CalendarTarget> {
        if (!canRead()) return emptyList()

        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.OWNER_ACCOUNT,
            CalendarContract.Calendars.CALENDAR_COLOR,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
        )

        val selection = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND " +
            "${CalendarContract.Calendars.VISIBLE} = 1 AND " +
            "${CalendarContract.Calendars.DELETED} = 0"

        val args = arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())

        return context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            selection,
            args,
            "${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC",
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val name = cursor.getString(1).orEmpty()
                    val account = cursor.getString(2).orEmpty()
                    val owner = cursor.getString(3).orEmpty()
                    add(
                        CalendarTarget(
                            id = id.toString(),
                            label = name.ifBlank { account },
                            accountName = account,
                            colour = if (cursor.isNull(4)) null else cursor.getInt(4),
                            isPrimary = owner.isNotEmpty() && owner == account,
                        )
                    )
                }
            }
        }.orEmpty()
    }
}
