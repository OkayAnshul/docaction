package com.okayanshul.docaction.timetable

import android.content.Context
import com.okayanshul.docaction.core.database.Databases
import com.okayanshul.docaction.core.database.TimetableDao
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
 * A timetable already held that the incoming one might be about to overwrite.
 *
 * Carries what the user needs to decide — chiefly [slotCount], because "replace" is only an
 * informed choice if it says how much it removes.
 */
data class TimetableCollision(
    val timetableId: String,
    val label: String,
    val slotCount: Int,
    val sourceName: String?,
    val updatedAt: Long,
)

/**
 * What to do about a [TimetableCollision].
 *
 * There is deliberately no default. The whole failure this type exists to prevent was code
 * picking one on the user's behalf.
 */
enum class TimetableResolution {
    /** Keep both sets of slots. The incoming ones win where they collide by entry. */
    Merge,

    /** Remove the stored slots and use the incoming ones. Destructive; snapshotted. */
    Replace,

    /** Leave the stored timetable alone and store this one beside it. */
    CreateNew,

    /** Leave the stored timetable alone and do not store this one at all. */
    Skip,
}

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
 *
 * **On identity.** A timetable is identified by the document it came from, never by what it
 * is called. Identifying by label was a data-loss bug: institutions reuse filenames, so an
 * unrelated schedule that happened to produce the same label silently replaced the one
 * already stored — no prompt, no confirmation, no way back. The rules now:
 *
 * - Same [sourceIdentity] — the same document, re-imported. Updates in place, silently.
 * - Same label, different document — ambiguous. Could be this term's revision, could be a
 *   different class entirely. Only the user knows, so [collisionFor] reports it and the
 *   caller must supply a [TimetableResolution].
 * - Neither — a new timetable. Stored beside whatever else is there.
 */
class TimetableStore(daoProvider: () -> TimetableDao) {

    /** The production path. Still lazy: nothing opens the database until something is asked. */
    constructor(context: Context) : this({ Databases.timetables(context) })

    private val dao by lazy(daoProvider)

    fun timetables(): Flow<List<TimetableEntity>> = dao.all()

    fun slots(timetableId: String): Flow<List<TimetableSlotEntity>> = dao.slots(timetableId)

    suspend fun mostRecent(): TimetableEntity? = dao.mostRecent()

    /**
     * Whether saving this would land on top of a timetable the user already has.
     *
     * Null means saving is unambiguous — either nothing matches, or the match is this exact
     * document being imported again. Non-null means a question has to be asked before
     * anything is written.
     */
    suspend fun collisionFor(label: String, sourceIdentity: String?): TimetableCollision? {
        // The same document again is never a collision, whatever it is called now — the user
        // may have renamed it, and renaming must not fork a timetable in two.
        if (sourceIdentity != null && dao.bySourceIdentity(sourceIdentity) != null) return null

        val existing = dao.byLabel(label) ?: return null
        return TimetableCollision(
            timetableId = existing.id,
            label = existing.label,
            slotCount = dao.slotCount(existing.id),
            sourceName = existing.sourceName,
            updatedAt = existing.updatedAt,
        )
    }

    /**
     * Stores a timetable.
     *
     * @param resolution required only when [collisionFor] reported one, and ignored otherwise.
     *   Passing null while a collision exists stores nothing and returns null rather than
     *   guessing — a caller that has not asked the user does not get to overwrite them.
     * @return the timetable's id, or null when nothing was stored.
     */
    suspend fun save(
        label: String,
        candidates: List<CalendarEventCandidate>,
        term: TermBounds,
        importId: ImportId,
        sourceName: String?,
        sourceHash: String?,
        sourceIdentity: String? = null,
        resolution: TimetableResolution? = null,
    ): String? {
        val recurring = candidates.filter { it.recurrence != null }
        if (recurring.isEmpty()) return null

        val sameDocument = sourceIdentity?.let { dao.bySourceIdentity(it) }
        val collision = if (sameDocument == null) dao.byLabel(label) else null

        // Deciding what "save" means here, once, so the branches below cannot disagree.
        val target: TimetableEntity?
        val mode: Mode

        when {
            // The same document again. Update it where it stands.
            sameDocument != null -> {
                target = sameDocument
                mode = Mode.Replace
            }

            collision == null -> {
                target = null
                mode = Mode.Create
            }

            resolution == null -> return null

            else -> when (resolution) {
                TimetableResolution.Skip -> return null
                TimetableResolution.CreateNew -> {
                    target = null
                    mode = Mode.Create
                }

                TimetableResolution.Merge -> {
                    target = collision
                    mode = Mode.Merge
                }

                TimetableResolution.Replace -> {
                    target = collision
                    mode = Mode.Replace
                }
            }
        }

        val now = System.currentTimeMillis()
        val id = target?.id ?: UUID.randomUUID().toString()

        val timetable = TimetableEntity(
            id = id,
            // A merge keeps the name the user already knows this schedule by.
            label = if (mode == Mode.Merge) target?.label ?: label else label,
            termStartEpochDay = term.start.toEpochDay(),
            termEndEpochDay = term.end.toEpochDay(),
            zoneId = recurring.first().start.zone.id,
            sourceName = sourceName,
            sourceHash = sourceHash,
            sourceIdentity = sourceIdentity,
            importId = importId.value,
            createdAt = target?.createdAt ?: now,
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

        when (mode) {
            Mode.Create -> dao.create(timetable, slots)
            Mode.Merge -> dao.merge(timetable, slots)
            Mode.Replace -> dao.replace(timetable, slots)
        }
        return id
    }

    // --- editing a stored week ---

    /**
     * Adds a slot, or rewrites one that already exists.
     *
     * The calendar row is **not** written here. It is the caller's job, because whether the
     * user's calendar should change is a question about permissions and consent that this
     * class has no business answering — and a store that silently reaches into the calendar
     * is exactly the kind of hidden write this codebase avoids everywhere else.
     */
    suspend fun putSlot(slot: TimetableSlotEntity) {
        dao.upsertSlots(listOf(slot))
        touch(slot.timetableId)
    }

    suspend fun deleteSlot(slot: TimetableSlotEntity) {
        dao.deleteSlot(slot.id)
        touch(slot.timetableId)
    }

    /**
     * Copies a slot onto other weekdays.
     *
     * The common case by a distance: a lab that runs Tuesday and Thursday is one slot the
     * user should enter once. Each copy gets its own id and its own provenance, so they can
     * be edited and removed independently afterwards.
     */
    suspend fun duplicateSlot(
        slot: TimetableSlotEntity,
        toWeekdays: Set<Int>,
    ): List<TimetableSlotEntity> {
        val copies = toWeekdays
            .filter { it != slot.weekday }
            .map { weekday ->
                val entryId = "${slot.entryId}-w$weekday"
                slot.copy(
                    id = "${slot.timetableId}-$entryId",
                    entryId = entryId,
                    weekday = weekday,
                    // A copy has not been written to the calendar yet. Inheriting the
                    // original's provenance would make one row the target of two slots, and
                    // editing either would silently move the other.
                    customAppUri = null,
                )
            }
        if (copies.isNotEmpty()) {
            dao.upsertSlots(copies)
            touch(slot.timetableId)
        }
        return copies
    }

    /** Renames a timetable. Purely a label — see [TimetableEntity.label]. */
    suspend fun rename(timetableId: String, label: String) {
        val existing = dao.byId(timetableId) ?: return
        val clean = label.trim().ifBlank { return }
        dao.upsert(existing.copy(label = clean, updatedAt = System.currentTimeMillis()))
    }

    suspend fun slotsNow(timetableId: String): List<TimetableSlotEntity> = dao.slotsNow(timetableId)

    suspend fun byId(timetableId: String): TimetableEntity? = dao.byId(timetableId)

    private suspend fun touch(timetableId: String) {
        dao.byId(timetableId)?.let {
            dao.upsert(it.copy(updatedAt = System.currentTimeMillis()))
        }
    }

    /**
     * Puts a timetable back as it was before the last destructive change.
     *
     * @return true when something was restored; false when there was nothing to restore, which
     *   the caller must report honestly rather than as a successful undo.
     */
    suspend fun undoLastChange(timetableId: String): Boolean = dao.restore(timetableId)

    suspend fun forget(timetableId: String) {
        dao.snapshot(timetableId, importId = null)
        dao.deleteSlots(timetableId)
        dao.delete(timetableId)
    }

    private enum class Mode { Create, Merge, Replace }

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

        /**
         * The stable identity of a timetable: which document, and which schedule inside it.
         *
         * The group matters. One workbook can hold a schedule per section, and importing
         * section B after section A is not a revision of section A — it is a second
         * timetable. Keyed on content alone, the second import would replace the first.
         *
         * Null when the document could not be hashed, which reads as "unknown" everywhere and
         * therefore never matches.
         */
        fun identityOf(documentHash: String?, groupId: String?): String? =
            documentHash?.let { "$it/${groupId.orEmpty()}" }
    }
}
