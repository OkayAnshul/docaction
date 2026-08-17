package com.okayanshul.docaction.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Version 1 to 2, against a real database file.
 *
 * Worth a test rather than a destructive fallback for one reason: version 1 holds **armed
 * reminder rows**. Dropping them during an app update would silently stop every pending
 * notification on someone's phone — no error, no symptom, nothing to notice until a class is
 * missed. So the migration is additive, and this proves the existing rows come through it.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    // The driver-based constructor, not the older assets-folder one: only this overload
    // exposes createDatabase(version) / runMigrationsAndValidate(version, migrations), which
    // are the calls that validate the migrated schema against Room's exported JSON. The file
    // is a scratch path under the test app, deleted by the helper between runs.
    private val file = InstrumentationRegistry.getInstrumentation()
        .targetContext.getDatabasePath("migration-test.db")

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        file = file,
        driver = AndroidSQLiteDriver(),
        databaseClass = DocActionDatabase::class,
    )

    /**
     * The helper reuses one file across the class, so a test that leaves the database at the
     * latest version makes the next `createDatabase(1)` ask Room to migrate *backwards*.
     * Deleting it here keeps each test starting from the version it says it starts from.
     */
    @Before
    fun startClean() {
        listOf(file, File("${file.path}-wal"), File("${file.path}-shm")).forEach { it.delete() }
    }

    @Test
    fun addingTimetablesKeepsThePendingReminders() {
        helper.createDatabase(version = 1).use { db ->
            db.execSQL(
                """
                INSERT INTO scheduled_reminders
                (id, importId, entryId, customAppUri, title, detail, kind,
                 dueAtEpochMillis, eventAtEpochMillis, allDay, armed, firedAt, inexact)
                VALUES ('r1', 'i1', 'e1', 'docaction://import/i1/e1', 'Data Structures',
                        NULL, 'Class', 1790000000000, 1790000900000, 0, 1, NULL, 0)
                """.trimIndent()
            )
        }

        helper.runMigrationsAndValidate(
            version = 2,
            migrations = listOf(DocActionDatabase.MIGRATION_1_2),
        ).use { db ->
            db.prepare("SELECT id, armed FROM scheduled_reminders").use { statement ->
                assertThat(statement.step()).isTrue()
                assertThat(statement.getText(0)).isEqualTo("r1")
                // Still armed. A migration that silently disarmed alarms would look exactly
                // like a successful one from every angle except the user's.
                assertThat(statement.getLong(1)).isEqualTo(1L)
            }

            // And the new tables genuinely exist, rather than the migration having been
            // skipped because Room decided the schemas already matched.
            db.prepare("SELECT COUNT(*) FROM timetable_slots").use { statement ->
                assertThat(statement.step()).isTrue()
                assertThat(statement.getLong(0)).isEqualTo(0L)
            }
        }
    }

    /**
     * Version 2 to 3: a timetable stops being identified by its name.
     *
     * The migration behind a data-loss fix, so what matters is not that it runs but what it
     * leaves behind. An already-stored timetable cannot have its `sourceIdentity` recovered —
     * we never knew which document it came from — and the honest value is null. Guessing one
     * would recreate the bug: two migrated timetables sharing a fabricated identity would
     * overwrite each other exactly as they did when the name was the identity.
     */
    @Test
    fun givingTimetablesAnIdentityLeavesExistingOnesHonestlyUnknown() {
        helper.createDatabase(version = 2).use { db ->
            db.execSQL(
                """
                INSERT INTO timetables
                (id, label, termStartEpochDay, termEndEpochDay, zoneId, sourceName,
                 sourceHash, importId, createdAt, updatedAt)
                VALUES ('t1', 'Section CS-1', 20669, 20789, 'Asia/Kolkata', 'tt.pdf',
                        'aaaa', 'i1', 100, 100)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO timetable_slots
                (id, timetableId, entryId, weekday, startMinute, endMinute, title,
                 location, customAppUri, endAssumed)
                VALUES ('t1-e1', 't1', 'e1', 1, 540, 600, 'Data Structures', 'K10',
                        'docaction://import/i1/e1', 0)
                """.trimIndent()
            )
        }

        helper.runMigrationsAndValidate(
            version = 3,
            migrations = listOf(DocActionDatabase.MIGRATION_2_3),
        ).use { db ->
            // The user's week survives the upgrade intact.
            db.prepare("SELECT label, sourceIdentity FROM timetables WHERE id = 't1'")
                .use { statement ->
                    assertThat(statement.step()).isTrue()
                    assertThat(statement.getText(0)).isEqualTo("Section CS-1")
                    // Null, not a guess. A null identity matches nothing, so the worst case
                    // for a migrated timetable is being asked a question rather than being
                    // silently replaced.
                    assertThat(statement.isNull(1)).isTrue()
                }

            db.prepare("SELECT COUNT(*) FROM timetable_slots WHERE timetableId = 't1'")
                .use { statement ->
                    assertThat(statement.step()).isTrue()
                    assertThat(statement.getLong(0)).isEqualTo(1L)
                }

            // Somewhere for a destructive change to put what it overwrote.
            listOf("timetable_snapshots", "timetable_slot_snapshots").forEach { table ->
                db.prepare("SELECT COUNT(*) FROM $table").use { statement ->
                    assertThat(statement.step()).isTrue()
                    assertThat(statement.getLong(0)).isEqualTo(0L)
                }
            }
        }
    }

    /** Straight from version 1, the way a phone that skipped an update arrives. */
    @Test
    fun theWholeChainRunsInOneGo() {
        helper.createDatabase(version = 1).use { db ->
            db.execSQL(
                """
                INSERT INTO scheduled_reminders
                (id, importId, entryId, customAppUri, title, detail, kind,
                 dueAtEpochMillis, eventAtEpochMillis, allDay, armed, firedAt, inexact)
                VALUES ('r1', 'i1', 'e1', 'docaction://import/i1/e1', 'Data Structures',
                        NULL, 'Class', 1790000000000, 1790000900000, 0, 1, NULL, 0)
                """.trimIndent()
            )
        }

        helper.runMigrationsAndValidate(
            version = 3,
            migrations = listOf(
                DocActionDatabase.MIGRATION_1_2,
                DocActionDatabase.MIGRATION_2_3,
            ),
        ).use { db ->
            db.prepare("SELECT armed FROM scheduled_reminders WHERE id = 'r1'").use { statement ->
                assertThat(statement.step()).isTrue()
                assertThat(statement.getLong(0)).isEqualTo(1L)
            }
        }
    }
}
