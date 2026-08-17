package com.okayanshul.docaction.actions.calendar

import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import com.okayanshul.docaction.core.common.Text
import com.okayanshul.docaction.core.database.CreatedEventDao
import com.okayanshul.docaction.core.database.CreatedEventEntity
import com.okayanshul.docaction.domain.ActionExecutor
import com.okayanshul.docaction.domain.ActionTarget
import com.okayanshul.docaction.domain.CalendarEventCandidate
import com.okayanshul.docaction.domain.CandidateId
import com.okayanshul.docaction.domain.DuplicateMatch
import com.okayanshul.docaction.domain.ExecutionReport
import com.okayanshul.docaction.domain.FailureReason
import com.okayanshul.docaction.domain.ImportId
import com.okayanshul.docaction.domain.EventTiming
import com.okayanshul.docaction.domain.NotificationOwner
import com.okayanshul.docaction.domain.Outcome
import com.okayanshul.docaction.domain.RevertReport
import java.time.ZoneOffset
import java.util.UUID

/**
 * Writes confirmed events to the device calendar, and takes them back out again.
 *
 * The riskiest code in the product: it is the only part that changes something the user
 * owns. Four rules are load-bearing, and each of them is a bug that passes a happy-path
 * test and then ruins a calendar.
 *
 * 1. **Chunked writes.** The Binder transaction buffer is roughly 1 MB; a 42-event timetable
 *    with locations and reminders exceeds it in a single `applyBatch`, failing part-way and
 *    leaving a half-written calendar.
 * 2. **`DURATION`, not `DTEND`, for recurring events.** The provider rejects the latter.
 * 3. **Provenance on every row.** `CUSTOM_APP_URI` is what makes undo surgical, and it lives
 *    in the calendar rather than only in our database so it survives a data wipe (ADR-006).
 * 4. **Undo deletes by provenance, never by time range.** A time-range delete is the obvious
 *    implementation and it destroys events the user created themselves.
 */
class CalendarEventExecutor(
    private val context: Context,
    private val createdEvents: CreatedEventDao,
    private val targets: CalendarTargets = CalendarTargets(context),
    private val notificationOwner: NotificationOwner = NotificationOwner.DocAction,
    private val reminderMinutes: Int = 10,
) : ActionExecutor<CalendarEventCandidate> {

    override suspend fun targets(): Outcome<List<ActionTarget>> {
        if (!targets.canRead()) return Outcome.Failure(FailureReason.ProcessingUnavailable)
        val writable = targets.writable()
        return if (writable.isEmpty()) {
            Outcome.Failure(FailureReason.ProcessingUnavailable)
        } else {
            Outcome.Success(writable)
        }
    }

    /**
     * Looks for events that already exist, before anything is written.
     *
     * Checks our own provenance first — an exact answer — then falls back to comparing
     * title and start time against what is already in the range, which catches events the
     * user typed in themselves or imported with another tool.
     */
    override suspend fun findDuplicates(
        candidates: List<CalendarEventCandidate>,
        target: ActionTarget,
    ): Outcome<List<DuplicateMatch>> {
        if (!targets.canRead()) return Outcome.Failure(FailureReason.ProcessingUnavailable)
        if (candidates.isEmpty()) return Outcome.Success(emptyList())

        val calendarId = (target as CalendarTarget).calendarId
        val from = candidates.minOf { it.start.toInstant().toEpochMilli() }
        val to = candidates.maxOf { it.end.toInstant().toEpochMilli() }

        val existing = mutableListOf<Triple<String, Long, Boolean>>()

        context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.CUSTOM_APP_PACKAGE,
            ),
            "${CalendarContract.Events.CALENDAR_ID} = ? AND " +
                "${CalendarContract.Events.DELETED} = 0 AND " +
                "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?",
            arrayOf(calendarId.toString(), from.toString(), to.toString()),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                existing += Triple(
                    cursor.getString(0).orEmpty(),
                    cursor.getLong(1),
                    cursor.getString(2) == PACKAGE,
                )
            }
        }

        val matches = candidates.mapNotNull { candidate ->
            val startMillis = candidate.start.toInstant().toEpochMilli()
            existing.firstOrNull { (title, start, _) ->
                start == startMillis && Text.similarity(title, candidate.title) >= SIMILAR
            }?.let { (title, start, ours) ->
                DuplicateMatch(
                    candidateId = candidate.id,
                    existingTitle = title,
                    existingStartMillis = start,
                    createdByUs = ours,
                )
            }
        }

        return Outcome.Success(matches)
    }

    override suspend fun execute(
        importId: ImportId,
        candidates: List<CalendarEventCandidate>,
        target: ActionTarget,
        onProgress: (Int, Int) -> Unit,
    ): Outcome<ExecutionReport> {
        if (!targets.canWrite()) return Outcome.Failure(FailureReason.ProcessingUnavailable)
        if (candidates.isEmpty()) {
            return Outcome.Success(ExecutionReport(importId, emptyList(), emptyList(), emptyMap()))
        }

        val calendarId = (target as CalendarTarget).calendarId
        val written = mutableListOf<CandidateId>()
        val failed = mutableMapOf<CandidateId, String>()
        val recorded = mutableListOf<CreatedEventEntity>()

        candidates.chunked(CHUNK).forEachIndexed { chunkIndex, chunk ->
            val operations = ArrayList<ContentProviderOperation>(chunk.size * 2)
            chunk.forEach { candidate ->
                operations += ContentProviderOperation
                    .newInsert(CalendarContract.Events.CONTENT_URI)
                    .withValues(valuesFor(candidate, calendarId, importId))
                    .build()

                // Only hand the reminder to the calendar when the calendar owns
                // notifications. When DocAction owns them, adding one here would
                // double-notify.
                if (notificationOwner == NotificationOwner.CalendarApp) {
                    operations += ContentProviderOperation
                        .newInsert(CalendarContract.Reminders.CONTENT_URI)
                        .withValueBackReference(
                            CalendarContract.Reminders.EVENT_ID,
                            operations.size - 1,
                        )
                        .withValue(CalendarContract.Reminders.MINUTES, reminderMinutes)
                        .withValue(
                            CalendarContract.Reminders.METHOD,
                            CalendarContract.Reminders.METHOD_ALERT,
                        )
                        .build()
                }
            }

            try {
                val results = context.contentResolver
                    .applyBatch(CalendarContract.AUTHORITY, operations)

                chunk.forEachIndexed { index, candidate ->
                    val uri = results.getOrNull(if (notificationOwner == NotificationOwner.CalendarApp) index * 2 else index)?.uri
                    val eventId = uri?.let { ContentUris.parseId(it) }
                    if (eventId != null && eventId > 0) {
                        written += candidate.id
                        recorded += CreatedEventEntity(
                            id = candidate.entryId.value,
                            importId = importId.value,
                            calendarEventId = eventId,
                            calendarId = calendarId,
                            customAppUri = provenanceUri(importId, candidate),
                            createdAt = System.currentTimeMillis(),
                            revokedAt = null,
                        )
                    } else {
                        failed[candidate.id] = "the calendar didn't accept this event"
                    }
                }
            } catch (e: Exception) {
                // One bad chunk must not lose the chunks that already succeeded.
                chunk.forEach { failed[it.id] = "the calendar stopped responding" }
            }

            onProgress((chunkIndex + 1) * chunk.size, candidates.size)
        }

        createdEvents.record(recorded)

        // The count we report comes from reading the calendar back, not from what we tried
        // to write. Reporting success for a write that did not land is the one thing that
        // would make the whole product untrustworthy.
        val confirmed = countWritten(importId)
        if (confirmed < written.size) {
            written.drop(confirmed).forEach { failed[it] = "this event wasn't found after writing" }
        }

        return Outcome.Success(
            ExecutionReport(
                importId = importId,
                written = written.take(confirmed),
                skipped = emptyList(),
                failed = failed,
            )
        )
    }

    /**
     * Removes exactly what one import created.
     *
     * The predicate is the provenance URI prefix. There is deliberately no code path in
     * this class that deletes by time range.
     */
    override suspend fun revert(importId: ImportId): Outcome<RevertReport> {
        if (!targets.canWrite()) return Outcome.Failure(FailureReason.ProcessingUnavailable)

        val expected = createdEvents.forImport(importId.value).size

        val removed = context.contentResolver.delete(
            CalendarContract.Events.CONTENT_URI,
            "${CalendarContract.Events.CUSTOM_APP_PACKAGE} = ? AND " +
                "${CalendarContract.Events.CUSTOM_APP_URI} LIKE ?",
            arrayOf(PACKAGE, "${importPrefix(importId)}%"),
        )

        createdEvents.markRevoked(importId.value, System.currentTimeMillis())

        // Events the user already deleted are reported honestly rather than claimed.
        return Outcome.Success(
            RevertReport(
                removed = removed,
                alreadyGone = (expected - removed).coerceAtLeast(0),
                modifiedByUser = 0,
            )
        )
    }

    /**
     * Rewrites the single calendar row a timetable slot created.
     *
     * Editing a slot in the weekly view has to reach the calendar, or the app shows one thing
     * and the user's phone tells them another all term. The row is found by its exact
     * provenance URI — not a prefix, not a time range — so an edit can only ever touch the
     * one event this app wrote for this slot.
     *
     * @return how many rows changed. Zero means the user deleted the event in their calendar
     *   app, which is their prerogative and is reported rather than repaired.
     */
    suspend fun updateByProvenance(
        provenanceUri: String,
        candidate: CalendarEventCandidate,
        calendarId: Long,
    ): Outcome<Int> {
        if (!targets.canWrite()) return Outcome.Failure(FailureReason.ProcessingUnavailable)

        // contentFor, not valuesFor: the provenance columns are left exactly as they are, so
        // an edited event is still the one this import created and undo can still find it.
        val values = contentFor(candidate, calendarId).apply {
            // A timed event that used to recur, or vice versa, would otherwise keep the
            // column it no longer wants and the provider would reject the row.
            if (candidate.recurrence == null) {
                putNull(CalendarContract.Events.RRULE)
                putNull(CalendarContract.Events.DURATION)
            } else {
                putNull(CalendarContract.Events.DTEND)
            }
        }

        return runCatching {
            context.contentResolver.update(
                CalendarContract.Events.CONTENT_URI,
                values,
                "${CalendarContract.Events.CUSTOM_APP_PACKAGE} = ? AND " +
                    "${CalendarContract.Events.CUSTOM_APP_URI} = ?",
                arrayOf(PACKAGE, provenanceUri),
            )
        }.fold(
            onSuccess = { Outcome.Success(it) },
            onFailure = { Outcome.Failure(FailureReason.ProcessingUnavailable) },
        )
    }

    /**
     * Removes the single calendar row a timetable slot created.
     *
     * Same rule as [revert]: identified by provenance, so deleting a slot can never take an
     * event the user made themselves with it.
     */
    suspend fun deleteByProvenance(provenanceUri: String): Outcome<Int> {
        if (!targets.canWrite()) return Outcome.Failure(FailureReason.ProcessingUnavailable)

        return runCatching {
            context.contentResolver.delete(
                CalendarContract.Events.CONTENT_URI,
                "${CalendarContract.Events.CUSTOM_APP_PACKAGE} = ? AND " +
                    "${CalendarContract.Events.CUSTOM_APP_URI} = ?",
                arrayOf(PACKAGE, provenanceUri),
            )
        }.fold(
            onSuccess = { Outcome.Success(it) },
            onFailure = { Outcome.Failure(FailureReason.ProcessingUnavailable) },
        )
    }

    private fun countWritten(importId: ImportId): Int =
        context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(CalendarContract.Events._ID),
            "${CalendarContract.Events.CUSTOM_APP_PACKAGE} = ? AND " +
                "${CalendarContract.Events.CUSTOM_APP_URI} LIKE ? AND " +
                "${CalendarContract.Events.DELETED} = 0",
            arrayOf(PACKAGE, "${importPrefix(importId)}%"),
            null,
        )?.use { it.count } ?: 0

    private fun valuesFor(
        candidate: CalendarEventCandidate,
        calendarId: Long,
        importId: ImportId,
    ) = contentFor(candidate, calendarId).apply {
        // Provenance — app-writable columns, verified in CalendarContract (ADR-006).
        put(CalendarContract.Events.CUSTOM_APP_PACKAGE, PACKAGE)
        put(CalendarContract.Events.CUSTOM_APP_URI, provenanceUri(importId, candidate))
        put(CalendarContract.Events.UID_2445, UUID.randomUUID().toString())
    }

    /**
     * Everything about an event except who created it.
     *
     * Separate from its provenance because an update must rewrite the first and must never
     * touch the second: an edited event is still the one this import created, and undo has to
     * keep being able to find it.
     */
    private fun contentFor(
        candidate: CalendarEventCandidate,
        calendarId: Long,
    ) = ContentValues().apply {
        put(CalendarContract.Events.CALENDAR_ID, calendarId)
        put(CalendarContract.Events.TITLE, candidate.title)
        candidate.location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }

        when (val timing = candidate.timing) {
            is EventTiming.AllDay -> {
                // An all-day row is measured in **UTC** midnights, whatever the user's zone.
                // This is the provider's rule, not ours, and getting it wrong is silent: pass
                // local midnight with ALL_DAY = 1 and the event lands a day early for
                // everyone east of UTC — which is most of the people this app is for.
                val startOfDay = timing.date.atStartOfDay(ZoneOffset.UTC)
                put(CalendarContract.Events.ALL_DAY, 1)
                put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
                put(CalendarContract.Events.DTSTART, startOfDay.toInstant().toEpochMilli())
                put(
                    CalendarContract.Events.DTEND,
                    startOfDay.plusDays(1).toInstant().toEpochMilli(),
                )
            }

            is EventTiming.Timed -> {
                put(CalendarContract.Events.ALL_DAY, 0)
                put(CalendarContract.Events.DTSTART, timing.start.toInstant().toEpochMilli())
                // Always explicit. Letting the provider guess is how a timetable ends up an
                // hour out after travel or a daylight-saving change.
                put(CalendarContract.Events.EVENT_TIMEZONE, timing.start.zone.id)

                val recurrence = candidate.recurrence
                if (recurrence != null) {
                    put(CalendarContract.Events.RRULE, RecurrenceRule.toRRule(recurrence))
                    // DURATION, never DTEND — the provider rejects a recurring event with DTEND.
                    put(
                        CalendarContract.Events.DURATION,
                        RecurrenceRule.toDuration(candidate.duration),
                    )
                } else {
                    put(CalendarContract.Events.DTEND, timing.end.toInstant().toEpochMilli())
                }
            }
        }

        // When DocAction owns notifications, the calendar row must carry none of its own.
        put(
            CalendarContract.Events.HAS_ALARM,
            if (notificationOwner == NotificationOwner.CalendarApp) 1 else 0,
        )
    }

    private fun provenanceUri(importId: ImportId, candidate: CalendarEventCandidate) =
        "${importPrefix(importId)}${candidate.entryId.value}"

    private fun importPrefix(importId: ImportId) = "docaction://import/${importId.value}/"

    companion object {
        const val PACKAGE = "com.okayanshul.docaction"

        /**
         * Operations per batch. Well inside the ~1 MB Binder limit even with long titles
         * and locations, and small enough that a failure loses little.
         */
        const val CHUNK = 150

        /** Title similarity above which two events at the same moment are "the same". */
        const val SIMILAR = 0.85
    }
}
