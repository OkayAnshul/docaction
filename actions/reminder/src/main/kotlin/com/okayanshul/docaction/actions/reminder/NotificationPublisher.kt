package com.okayanshul.docaction.actions.reminder

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.okayanshul.docaction.core.database.ScheduledReminderEntity
import com.okayanshul.docaction.domain.ReminderKind
import java.time.Duration
import java.time.Instant

/**
 * Posts reminder notifications.
 *
 * One channel per kind, so a student can silence deadline nudges while keeping the
 * five-minute class alarm loud — that separation only exists if the channels do.
 *
 * The final rung is high-priority with sound, which is as insistent as this app should get.
 * A full-screen alarm would need `USE_FULL_SCREEN_INTENT`, restricted on modern Android to
 * calling and alarm apps and reviewed case-by-case; a document app requesting it risks the
 * whole release, not just the feature.
 */
class NotificationPublisher(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    /**
     * Idempotent, and called from [post] as well as at import time.
     *
     * `notify()` on a channel that does not exist is silently dropped — no exception, no
     * log, nothing on screen. The channels are normally created when an import arms its
     * reminders, but that leaves a real window: an alarm that survives a reinstall, or fires
     * before any import has run in this installation, would post into a channel that is not
     * there and the user would simply never be reminded.
     *
     * Creating a channel that already exists is a no-op in the framework, so paying for this
     * on every post costs nothing and closes the window.
     */
    fun ensureChannels() {
        val system = context.getSystemService(NotificationManager::class.java)
        Channel.entries.forEach { channel ->
            system.createNotificationChannel(
                NotificationChannel(channel.id, channel.title, channel.importance).apply {
                    description = channel.description
                }
            )
        }
    }

    /** @return false when the notification could not be posted (permission not granted). */
    fun post(reminder: ScheduledReminderEntity, now: Instant = Instant.now()): Boolean {
        if (!canPost()) return false
        ensureChannels()

        val kind = runCatching { ReminderKind.valueOf(reminder.kind) }.getOrDefault(ReminderKind.Deadline)
        val channel = Channel.forKind(kind)
        val eventAt = Instant.ofEpochMilli(reminder.eventAtEpochMillis)
        val lead = Duration.between(now, eventAt)

        val notification = NotificationCompat.Builder(context, channel.id)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(headline(reminder, lead))
            .setContentText(listOfNotNull(reminder.title, reminder.detail).joinToString(" · "))
            .setPriority(if (isFinalNudge(lead)) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .build()

        return runCatching { manager.notify(reminder.id.hashCode(), notification) }.isSuccess
    }

    private fun headline(reminder: ScheduledReminderEntity, lead: Duration): String = when {
        reminder.allDay -> reminder.title
        lead.isNegative -> "Starting now"
        lead < Duration.ofMinutes(1) -> "Starting now"
        lead < Duration.ofHours(1) -> "In ${lead.toMinutes()} minutes"
        lead < Duration.ofDays(1) -> "In ${lead.toHours()} hours"
        else -> "Tomorrow"
    }

    private fun isFinalNudge(lead: Duration) = !lead.isNegative && lead <= Duration.ofMinutes(15)

    private fun canPost(): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    enum class Channel(
        val id: String,
        val title: String,
        val description: String,
        val importance: Int,
    ) {
        Classes(
            id = "docaction.classes",
            title = "Classes",
            description = "Reminders before a class starts",
            importance = NotificationManager.IMPORTANCE_HIGH,
        ),
        Deadlines(
            id = "docaction.deadlines",
            title = "Deadlines",
            description = "Reminders before something is due",
            importance = NotificationManager.IMPORTANCE_DEFAULT,
        ),
        Appointments(
            id = "docaction.appointments",
            title = "Appointments",
            description = "Reminders before an appointment",
            importance = NotificationManager.IMPORTANCE_HIGH,
        );

        companion object {
            fun forKind(kind: ReminderKind) = when (kind) {
                ReminderKind.Class -> Classes
                ReminderKind.Deadline -> Deadlines
                ReminderKind.Appointment -> Appointments
            }
        }
    }
}
