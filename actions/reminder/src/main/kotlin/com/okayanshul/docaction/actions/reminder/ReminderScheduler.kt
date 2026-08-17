package com.okayanshul.docaction.actions.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.okayanshul.docaction.core.database.ReminderDao
import com.okayanshul.docaction.core.database.ScheduledReminderEntity
import com.okayanshul.docaction.domain.ImportId
import com.okayanshul.docaction.domain.ReminderCandidate
import com.okayanshul.docaction.domain.ReminderKind
import java.time.Duration
import java.time.Instant

/** What happened when the scheduler last armed alarms — surfaced in Settings, not hidden. */
data class ArmingReport(
    val armed: Int,
    val inexact: Int,
    val windowEnd: Instant,
) {
    /** True when the user has not granted exact alarms, so reminders may drift. */
    val degraded: Boolean get() = inexact > 0
}

/**
 * Arms alarms for reminders that are about to come due.
 *
 * **Only a rolling window is ever armed.** A term of 23 weekly classes at four rungs each is
 * roughly 1,400 alarms; arming them all would be abusive to the system and would break the
 * moment anything changed. Instead the next [windowLength] is armed, and each firing — plus
 * a daily sweep — re-arms the next slice. Recurrence is expanded lazily into rows by
 * [ReminderPlanner]; the series is never materialised.
 *
 * Exactness degrades rather than failing: without `SCHEDULE_EXACT_ALARM` the alarm is still
 * set, just inexactly, and the fact is recorded so Settings can say so. A reminder that is a
 * few minutes late is worth having; one that silently never arrives is not.
 */
class ReminderScheduler(
    private val context: Context,
    private val dao: ReminderDao,
    private val windowLength: Duration = Duration.ofHours(48),
    private val maxAlarmsPerPass: Int = 100,
) {

    private val alarms = context.getSystemService(AlarmManager::class.java)

    suspend fun armWindow(now: Instant = Instant.now()): ArmingReport {
        val windowEnd = now.plus(windowLength)
        val due = dao.dueBetween(now.toEpochMilli(), windowEnd.toEpochMilli(), maxAlarmsPerPass)

        var armed = 0
        var inexact = 0

        due.forEach { reminder ->
            val wasInexact = arm(reminder)
            dao.markArmed(reminder.id, armed = true, inexact = wasInexact)
            armed++
            if (wasInexact) inexact++
        }

        return ArmingReport(armed, inexact, windowEnd)
    }

    /** @return true when the alarm had to be set inexactly. */
    private fun arm(reminder: ScheduledReminderEntity): Boolean {
        val intent = PendingIntent.getBroadcast(
            context,
            reminder.id.hashCode(),
            Intent(context, ReminderReceiver::class.java).apply {
                action = ReminderReceiver.ACTION_FIRE
                putExtra(ReminderReceiver.EXTRA_ID, reminder.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()

        return if (exactAllowed) {
            runCatching {
                alarms.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    reminder.dueAtEpochMillis,
                    intent,
                )
            }.isFailure.also { failed ->
                // A SecurityException here means permission was revoked between the check
                // and the call. Fall back rather than losing the reminder.
                if (failed) setInexact(reminder, intent)
            }
        } else {
            setInexact(reminder, intent)
            true
        }
    }

    private fun setInexact(reminder: ScheduledReminderEntity, intent: PendingIntent) {
        alarms.setWindow(
            AlarmManager.RTC_WAKEUP,
            reminder.dueAtEpochMillis,
            INEXACT_WINDOW_MILLIS,
            intent,
        )
    }

    /** Cancels one import's reminders and only that import's. */
    suspend fun cancelImport(importId: ImportId): Int {
        dao.pending(limit = Int.MAX_VALUE)
            .filter { it.importId == importId.value }
            .forEach { cancelAlarm(it.id) }
        return dao.deleteForImport(importId.value)
    }

    private fun cancelAlarm(id: String) {
        val intent = PendingIntent.getBroadcast(
            context,
            id.hashCode(),
            Intent(context, ReminderReceiver::class.java).apply { action = ReminderReceiver.ACTION_FIRE },
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        intent?.let {
            alarms.cancel(it)
            it.cancel()
        }
    }

    companion object {
        /** Fifteen minutes of slack when exactness is unavailable. */
        const val INEXACT_WINDOW_MILLIS = 15L * 60 * 1000

        fun kindOf(candidate: ReminderCandidate): ReminderKind = candidate.kind
    }
}
