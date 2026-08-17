package com.okayanshul.docaction.timetable

import android.content.Context
import com.okayanshul.docaction.core.database.Databases
import com.okayanshul.docaction.core.database.TimetableEntity
import com.okayanshul.docaction.core.database.TimetableSlotEntity
import com.okayanshul.docaction.domain.Assumption
import com.okayanshul.docaction.domain.CalendarEventCandidate
import com.okayanshul.docaction.domain.ImportId
import com.okayanshul.docaction.domain.TermBounds
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/**
 * Keeps a weekly schedule the user asked us to remember.
 *
 * Built from the same [CalendarEventCandidate]s that go to the calendar, never from a second
 * extraction. Two readers of one document would drift on exactly the documents that are
 * hard, and the corpus would stop being a regression suite for half the product.
 *
 * Only recurring candidates are kept: a timetable is the thing that repeats. A one-off exam
 * in the same import goes to the calendar and nowhere else, because a weekly view has no
 * honest place to show it.
 */
class TimetableStore(private val context: Context) {

    private val dao by lazy { Databases.timetables(context) }

    fun timetables(): Flow<List<TimetableEntity>> = dao.all()

    fun slots(timetableId: String): Flow<List<TimetableSlotEntity>> = dao.slots(timetableId)

    suspend fun mostRecent(): TimetableEntity? = dao.mostRecent()

    /** True when this document looks like a revision of one we already hold. */
    suspend fun existingFor(label: String, documentHash: String?): TimetableEntity? =
        dao.byLabel(label)?.takeIf { it.sourceHash != documentHash }

    suspend fun save(
        label: String,
        candidates: List<CalendarEventCandidate>,
        term: TermBounds,
        importId: ImportId,
        sourceName: String?,
        sourceHash: String?,
    ): String? {
        val recurring = candidates.filter { it.recurrence != null }
        if (recurring.isEmpty()) return null

        val now = System.currentTimeMillis()
        // A revision replaces its predecessor rather than sitting beside it. Two timetables
        // called "Section CS-1" would be indistinguishable in the list, and the older one
        // would keep showing classes that have moved.
        val existing = dao.byLabel(label)
        val id = existing?.id ?: UUID.randomUUID().toString()

        val timetable = TimetableEntity(
            id = id,
            label = label,
            termStartEpochDay = term.start.toEpochDay(),
            termEndEpochDay = term.end.toEpochDay(),
            zoneId = recurring.first().start.zone.id,
            sourceName = sourceName,
            sourceHash = sourceHash,
            importId = importId.value,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )

        val slots = recurring.map { candidate ->
            TimetableSlotEntity(
                id = "$id-${candidate.entryId.value}",
                timetableId = id,
                entryId = candidate.entryId.value,
                weekday = candidate.start.dayOfWeek.value,
                startMinute = candidate.start.hour * 60 + candidate.start.minute,
                endMinute = candidate.end.hour * 60 + candidate.end.minute,
                title = candidate.title,
                location = candidate.location,
                customAppUri = "docaction://import/${importId.value}/${candidate.entryId.value}",
                endAssumed = candidate.assumptions.any { it is Assumption.EndTime },
            )
        }

        dao.replace(timetable, slots)
        return id
    }

    suspend fun forget(timetableId: String) {
        dao.deleteSlots(timetableId)
        dao.delete(timetableId)
    }

    companion object {
        /**
         * Identifies the document, so a revision can be recognised as one.
         *
         * Content, not filename: institutions reuse names ("timetable.pdf") and change
         * content, which is the case that matters. Truncated because this is for equality,
         * not security — nothing is protected by it.
         */
        fun hashOf(file: File?): String? = file?.takeIf { it.exists() }?.let {
            runCatching {
                MessageDigest.getInstance("SHA-256")
                    .digest(it.readBytes())
                    .take(8)
                    .joinToString("") { byte -> "%02x".format(byte) }
            }.getOrNull()
        }
    }
}
