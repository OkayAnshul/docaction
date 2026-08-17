package com.okayanshul.docaction.core.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.okayanshul.docaction.domain.NotificationOwner
import com.okayanshul.docaction.domain.ReminderLadder
import java.time.Duration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.store: DataStore<Preferences> by preferencesDataStore(name = "reminder_settings")

/**
 * How the user wants to be reminded.
 *
 * Persisted preferences only — no UI in this pass. Everything here has a defensible default
 * so the engine is fully functional before a settings screen exists.
 */
data class ReminderPreferences(
    /** Minutes before the event, furthest first. */
    val offsetsMinutes: List<Long> = DEFAULT_OFFSETS,
    /** Keep nudging from the last offset until the event starts. */
    val repeatUntilStart: Boolean = false,
    val repeatEveryMinutes: Long = 5,
    val defaultOwner: NotificationOwner = NotificationOwner.DocAction,
    /**
     * True when the last scheduling pass had to fall back to inexact alarms. Surfaced so
     * Settings can explain late reminders instead of leaving the user to wonder.
     */
    val exactAlarmsUnavailable: Boolean = false,
) {
    fun toLadder(owner: NotificationOwner = defaultOwner) = ReminderLadder(
        offsets = offsetsMinutes.sortedDescending().map { Duration.ofMinutes(it) },
        repeatEvery = if (repeatUntilStart) Duration.ofMinutes(repeatEveryMinutes) else null,
        owner = owner,
    )

    companion object {
        /** A day ahead, an hour ahead, then closing in. */
        val DEFAULT_OFFSETS = listOf(1440L, 60L, 15L, 5L)
    }
}

class ReminderSettings(private val context: Context) {

    val preferences: Flow<ReminderPreferences> = context.store.data.map { it.toReminderPreferences() }

    suspend fun setOffsets(minutes: List<Long>) {
        // A ladder with no rungs would silently stop reminding; refuse rather than allow it.
        val sane = minutes.filter { it >= 0 }.distinct().sortedDescending()
        if (sane.isEmpty()) return
        context.store.edit { it[Keys.Offsets] = sane.joinToString(",") }
    }

    suspend fun setRepeatUntilStart(enabled: Boolean, everyMinutes: Long = 5) {
        context.store.edit {
            it[Keys.RepeatUntilStart] = enabled
            if (everyMinutes > 0) it[Keys.RepeatEvery] = everyMinutes
        }
    }

    suspend fun setDefaultOwner(owner: NotificationOwner) {
        context.store.edit { it[Keys.Owner] = owner.name }
    }

    suspend fun setExactAlarmsUnavailable(unavailable: Boolean) {
        context.store.edit { it[Keys.ExactUnavailable] = unavailable }
    }

    private fun Preferences.toReminderPreferences() = ReminderPreferences(
        offsetsMinutes = this[Keys.Offsets]
            ?.split(',')
            ?.mapNotNull { it.trim().toLongOrNull() }
            ?.takeIf { it.isNotEmpty() }
            ?: ReminderPreferences.DEFAULT_OFFSETS,
        repeatUntilStart = this[Keys.RepeatUntilStart] ?: false,
        repeatEveryMinutes = this[Keys.RepeatEvery] ?: 5,
        defaultOwner = this[Keys.Owner]
            ?.let { name -> runCatching { NotificationOwner.valueOf(name) }.getOrNull() }
            ?: NotificationOwner.DocAction,
        exactAlarmsUnavailable = this[Keys.ExactUnavailable] ?: false,
    )

    private object Keys {
        val Offsets = stringPreferencesKey("ladder_offsets_minutes")
        val RepeatUntilStart = booleanPreferencesKey("repeat_until_start")
        val RepeatEvery = longPreferencesKey("repeat_every_minutes")
        val Owner = stringPreferencesKey("default_notification_owner")
        val ExactUnavailable = booleanPreferencesKey("exact_alarms_unavailable")
    }
}
