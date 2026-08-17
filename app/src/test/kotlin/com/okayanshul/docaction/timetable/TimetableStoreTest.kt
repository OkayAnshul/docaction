package com.okayanshul.docaction.timetable

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.okayanshul.docaction.core.database.Databases
import com.okayanshul.docaction.domain.CalendarEventCandidate
import com.okayanshul.docaction.domain.Confident
import com.okayanshul.docaction.domain.EntryId
import com.okayanshul.docaction.domain.ImportId
import com.okayanshul.docaction.domain.ScheduleEntry
import com.okayanshul.docaction.domain.SourceReference
import com.okayanshul.docaction.domain.TermBounds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/**
 * The rules that keep a stored timetable from being destroyed without the user's say-so.
 *
 * This file exists because there was none, and the code it covers was silently replacing
 * timetables by display name. Institutions reuse filenames — "timetable.pdf" is not a
 * identity — so a student importing an unrelated schedule lost the one they already had, with
 * no prompt and no way back.
 *
 * Every test here is a sentence from that failure. The first one is the bug itself.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TimetableStoreTest {

    private val handle = Databases.inMemory(ApplicationProvider.getApplicationContext())
    private val store = TimetableStore { handle.timetables }

    private val term = TermBounds(LocalDate.of(2026, 8, 3), LocalDate.of(2026, 12, 4))
    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")

    @After
    fun close() = handle.close()

    // --- the bug ---

    @Test
    fun adifferentDocumentWithTheSameNameNeverOverwritesSilently(): Unit = runBlocking {
        store.save(
            label = "timetable.pdf",
            candidates = listOf(candidate("Data Structures", DayOfWeek.MONDAY, 9)),
            term = term,
            importId = ImportId("first"),
            sourceName = "timetable.pdf",
            sourceHash = "aaaa",
            sourceIdentity = TimetableStore.identityOf("aaaa", null),
        )

        // A second, unrelated document that happens to produce the same label.
        val second = TimetableStore.identityOf("bbbb", null)
        val collision = store.collisionFor("timetable.pdf", second)

        // It must be reported, not resolved on the user's behalf.
        assertThat(collision).isNotNull()
        assertThat(collision!!.slotCount).isEqualTo(1)
        assertThat(collision.label).isEqualTo("timetable.pdf")

        // And with no answer supplied, nothing is written at all.
        val id = store.save(
            label = "timetable.pdf",
            candidates = listOf(candidate("Thermodynamics", DayOfWeek.FRIDAY, 14)),
            term = term,
            importId = ImportId("second"),
            sourceName = "timetable.pdf",
            sourceHash = "bbbb",
            sourceIdentity = second,
            resolution = null,
        )

        assertThat(id).isNull()
        assertThat(titlesOfOnlyTimetable()).containsExactly("Data Structures")
    }

    // --- the safe paths ---

    @Test
    fun theSameDocumentReimportedUpdatesInPlace(): Unit = runBlocking {
        val identity = TimetableStore.identityOf("aaaa", null)
        val first = store.save(
            label = "Section CS-1",
            candidates = listOf(candidate("Data Structures", DayOfWeek.MONDAY, 9)),
            term = term,
            importId = ImportId("first"),
            sourceName = "tt.pdf",
            sourceHash = "aaaa",
            sourceIdentity = identity,
        )

        // Same document again: no question, no second timetable.
        assertThat(store.collisionFor("Section CS-1", identity)).isNull()

        val second = store.save(
            label = "Section CS-1",
            candidates = listOf(
                candidate("Data Structures", DayOfWeek.MONDAY, 9),
                candidate("Operating Systems", DayOfWeek.TUESDAY, 11),
            ),
            term = term,
            importId = ImportId("second"),
            sourceName = "tt.pdf",
            sourceHash = "aaaa",
            sourceIdentity = identity,
        )

        assertThat(second).isEqualTo(first)
        assertThat(storedTimetables()).hasSize(1)
        assertThat(titlesOfOnlyTimetable())
            .containsExactly("Data Structures", "Operating Systems")
    }

    @Test
    fun renamingDoesNotForkATimetable(): Unit = runBlocking {
        val identity = TimetableStore.identityOf("aaaa", null)
        val first = store.save(
            label = "timetable.pdf",
            candidates = listOf(candidate("Data Structures", DayOfWeek.MONDAY, 9)),
            term = term, importId = ImportId("first"),
            sourceName = "tt.pdf", sourceHash = "aaaa", sourceIdentity = identity,
        )

        // The user has since renamed it to something meaningful. Same document, new name.
        val second = store.save(
            label = "My Semester 5",
            candidates = listOf(candidate("Data Structures", DayOfWeek.MONDAY, 9)),
            term = term, importId = ImportId("second"),
            sourceName = "tt.pdf", sourceHash = "aaaa", sourceIdentity = identity,
        )

        assertThat(second).isEqualTo(first)
        assertThat(storedTimetables()).hasSize(1)
    }

    @Test
    fun anUnrelatedNameCreatesASecondTimetableWithoutAsking(): Unit = runBlocking {
        store.save(
            label = "Section CS-1",
            candidates = listOf(candidate("Data Structures", DayOfWeek.MONDAY, 9)),
            term = term, importId = ImportId("first"),
            sourceName = "a.pdf", sourceHash = "aaaa",
            sourceIdentity = TimetableStore.identityOf("aaaa", null),
        )

        val identity = TimetableStore.identityOf("bbbb", null)
        assertThat(store.collisionFor("Section CS-2", identity)).isNull()

        store.save(
            label = "Section CS-2",
            candidates = listOf(candidate("Thermodynamics", DayOfWeek.FRIDAY, 14)),
            term = term, importId = ImportId("second"),
            sourceName = "b.pdf", sourceHash = "bbbb", sourceIdentity = identity,
        )

        assertThat(storedTimetables()).hasSize(2)
    }

    @Test
    fun oneWorkbookTwoSectionsAreTwoTimetablesNotAnOverwrite(): Unit = runBlocking {
        // The same file, so the same content hash — but a different schedule inside it.
        // Keyed on content alone, importing section B would replace section A.
        val sectionA = TimetableStore.identityOf("aaaa", "group-a")
        val sectionB = TimetableStore.identityOf("aaaa", "group-b")
        assertThat(sectionA).isNotEqualTo(sectionB)

        store.save(
            label = "Section A", candidates = listOf(candidate("Data Structures", DayOfWeek.MONDAY, 9)),
            term = term, importId = ImportId("a"),
            sourceName = "tt.xlsx", sourceHash = "aaaa", sourceIdentity = sectionA,
        )
        store.save(
            label = "Section B", candidates = listOf(candidate("Thermodynamics", DayOfWeek.FRIDAY, 14)),
            term = term, importId = ImportId("b"),
            sourceName = "tt.xlsx", sourceHash = "aaaa", sourceIdentity = sectionB,
        )

        assertThat(storedTimetables()).hasSize(2)
    }

    // --- resolutions ---

    @Test
    fun replaceRemovesTheOldSlotsAndIsUndoable(): Unit = runBlocking {
        val id = store.save(
            label = "Section CS-1",
            candidates = listOf(
                candidate("Data Structures", DayOfWeek.MONDAY, 9),
                candidate("Operating Systems", DayOfWeek.TUESDAY, 11),
            ),
            term = term, importId = ImportId("first"),
            sourceName = "old.pdf", sourceHash = "aaaa",
            sourceIdentity = TimetableStore.identityOf("aaaa", null),
        )!!

        store.save(
            label = "Section CS-1",
            candidates = listOf(candidate("Thermodynamics", DayOfWeek.FRIDAY, 14)),
            term = term, importId = ImportId("second"),
            sourceName = "new.pdf", sourceHash = "bbbb",
            sourceIdentity = TimetableStore.identityOf("bbbb", null),
            resolution = TimetableResolution.Replace,
        )

        assertThat(titlesOfOnlyTimetable()).containsExactly("Thermodynamics")

        // A destructive choice the user consented to is still one they can take back.
        assertThat(store.undoLastChange(id)).isTrue()
        assertThat(titlesOfOnlyTimetable())
            .containsExactly("Data Structures", "Operating Systems")
    }

    @Test
    fun mergeAddsToTheExistingTimetableWithoutRemovingAnything(): Unit = runBlocking {
        store.save(
            label = "Section CS-1",
            candidates = listOf(candidate("Data Structures", DayOfWeek.MONDAY, 9)),
            term = term, importId = ImportId("first"),
            sourceName = "old.pdf", sourceHash = "aaaa",
            sourceIdentity = TimetableStore.identityOf("aaaa", null),
        )

        store.save(
            label = "Section CS-1",
            candidates = listOf(candidate("Thermodynamics", DayOfWeek.FRIDAY, 14)),
            term = term, importId = ImportId("second"),
            sourceName = "new.pdf", sourceHash = "bbbb",
            sourceIdentity = TimetableStore.identityOf("bbbb", null),
            resolution = TimetableResolution.Merge,
        )

        assertThat(storedTimetables()).hasSize(1)
        assertThat(titlesOfOnlyTimetable())
            .containsExactly("Data Structures", "Thermodynamics")
    }

    @Test
    fun createNewLeavesTheExistingTimetableUntouched(): Unit = runBlocking {
        store.save(
            label = "Section CS-1",
            candidates = listOf(candidate("Data Structures", DayOfWeek.MONDAY, 9)),
            term = term, importId = ImportId("first"),
            sourceName = "old.pdf", sourceHash = "aaaa",
            sourceIdentity = TimetableStore.identityOf("aaaa", null),
        )

        store.save(
            label = "Section CS-1",
            candidates = listOf(candidate("Thermodynamics", DayOfWeek.FRIDAY, 14)),
            term = term, importId = ImportId("second"),
            sourceName = "new.pdf", sourceHash = "bbbb",
            sourceIdentity = TimetableStore.identityOf("bbbb", null),
            resolution = TimetableResolution.CreateNew,
        )

        val all = storedTimetables()
        assertThat(all).hasSize(2)
        val original = all.single { it.sourceHash == "aaaa" }
        assertThat(handle.timetables.slotsNow(original.id).map { it.title })
            .containsExactly("Data Structures")
    }

    @Test
    fun skipWritesNothingAtAll(): Unit = runBlocking {
        store.save(
            label = "Section CS-1",
            candidates = listOf(candidate("Data Structures", DayOfWeek.MONDAY, 9)),
            term = term, importId = ImportId("first"),
            sourceName = "old.pdf", sourceHash = "aaaa",
            sourceIdentity = TimetableStore.identityOf("aaaa", null),
        )

        val id = store.save(
            label = "Section CS-1",
            candidates = listOf(candidate("Thermodynamics", DayOfWeek.FRIDAY, 14)),
            term = term, importId = ImportId("second"),
            sourceName = "new.pdf", sourceHash = "bbbb",
            sourceIdentity = TimetableStore.identityOf("bbbb", null),
            resolution = TimetableResolution.Skip,
        )

        assertThat(id).isNull()
        assertThat(storedTimetables()).hasSize(1)
        assertThat(titlesOfOnlyTimetable()).containsExactly("Data Structures")
    }

    // --- honesty ---

    @Test
    fun undoingWhenThereIsNothingToUndoSaysSoRatherThanClaimingSuccess(): Unit = runBlocking {
        val id = store.save(
            label = "Section CS-1",
            candidates = listOf(candidate("Data Structures", DayOfWeek.MONDAY, 9)),
            term = term, importId = ImportId("first"),
            sourceName = "tt.pdf", sourceHash = "aaaa",
            sourceIdentity = TimetableStore.identityOf("aaaa", null),
        )!!

        // A first import destroys nothing, so there is no snapshot to go back to.
        assertThat(store.undoLastChange(id)).isFalse()
        assertThat(titlesOfOnlyTimetable()).containsExactly("Data Structures")
    }

    @Test
    fun aTimetableStoredBeforeIdentitiesExistedIsNeverTreatedAsAMatch(): Unit = runBlocking {
        // Migrated rows carry a null identity. Null must read as "unknown", never as equal to
        // another null — that would be the original overwrite bug wearing a new column.
        handle.timetables.create(
            com.okayanshul.docaction.core.database.TimetableEntity(
                id = "legacy", label = "Section CS-1",
                termStartEpochDay = term.start.toEpochDay(),
                termEndEpochDay = term.end.toEpochDay(),
                zoneId = zone.id, sourceName = "old.pdf", sourceHash = null,
                sourceIdentity = null, importId = null,
                createdAt = 0, updatedAt = 0,
            ),
            emptyList(),
        )

        assertThat(handle.timetables.bySourceIdentity("anything")).isNull()
        assertThat(store.collisionFor("Section CS-1", null)).isNotNull()
    }

    @Test
    fun aNonRecurringImportIsNotATimetable(): Unit = runBlocking {
        // A timetable is the thing that repeats. An exam schedule goes to the calendar only.
        val id = store.save(
            label = "Exams",
            candidates = listOf(datedCandidate("Maths Paper", LocalDate.of(2026, 9, 14))),
            term = term, importId = ImportId("first"),
            sourceName = "exams.pdf", sourceHash = "cccc",
            sourceIdentity = TimetableStore.identityOf("cccc", null),
        )

        assertThat(id).isNull()
        assertThat(storedTimetables()).isEmpty()
    }

    // --- helpers ---

    private suspend fun titlesOfOnlyTimetable(): List<String> {
        val only = handle.timetables.all().first().single()
        return handle.timetables.slotsNow(only.id).map { it.title }
    }

    private suspend fun storedTimetables() = handle.timetables.all().first()

    /** A weekly class — the thing a timetable is made of. */
    private fun candidate(title: String, day: DayOfWeek, hour: Int): CalendarEventCandidate {
        val entry = ScheduleEntry(
            id = EntryId(UUID.randomUUID().toString()),
            title = Confident.High(title, source),
            weekday = Confident.High(day, source),
            startTime = Confident.High(LocalTime.of(hour, 0), source),
            endTime = Confident.High(LocalTime.of(hour + 1, 0), source),
        )
        return accepted(entry)
    }

    private fun datedCandidate(title: String, date: LocalDate): CalendarEventCandidate {
        val entry = ScheduleEntry(
            id = EntryId(UUID.randomUUID().toString()),
            title = Confident.High(title, source),
            date = Confident.High(date, source),
            startTime = Confident.High(LocalTime.of(9, 0), source),
            endTime = Confident.High(LocalTime.of(12, 0), source),
        )
        return accepted(entry)
    }

    private fun accepted(entry: ScheduleEntry): CalendarEventCandidate {
        val result = CalendarEventCandidate.from(entry, zone, term)
        return (result as CalendarEventCandidate.Result.Accepted).candidate
    }

    private val source = SourceReference.SheetCell("Sheet1", 1, 1)
}
