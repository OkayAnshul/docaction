package com.okayanshul.docaction.actions.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.okayanshul.docaction.core.database.Databases
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Delivers a reminder, then arms the next slice of the rolling window.
 *
 * Re-arming here is what keeps the window rolling without a foreground service: each firing
 * pulls the next reminders into range.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val id = intent.getStringExtra(EXTRA_ID) ?: return

        val pending = goAsync()
        val app = context.applicationContext

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = Databases.reminders(app)
                val reminder = dao.byId(id)

                if (reminder != null && reminder.firedAt == null) {
                    NotificationPublisher(app).apply { ensureChannels() }.post(reminder)
                    dao.markFired(id, Instant.now().toEpochMilli())
                }

                ReminderScheduler(app, dao).armWindow()
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "com.okayanshul.docaction.action.FIRE_REMINDER"
        const val EXTRA_ID = "reminderId"
    }
}

/**
 * Re-arms alarms after events that silently destroy them.
 *
 * Alarms do not survive a reboot, and the system clears them on a timezone change or when
 * the app is replaced. Without this the reminder feature works perfectly in testing and then
 * quietly stops overnight on a real phone — a failure with no error and no symptom until a
 * class is missed.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in REARM_ACTIONS) return

        val pending = goAsync()
        val app = context.applicationContext

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = Databases.reminders(app)
                // A reminder whose moment passed while the device was off is not fired late.
                dao.deleteExpired(Instant.now().toEpochMilli())
                ReminderScheduler(app, dao).armWindow()
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        val REARM_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
        )
    }
}
