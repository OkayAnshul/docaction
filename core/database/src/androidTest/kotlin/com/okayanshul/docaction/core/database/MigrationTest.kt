package com.okayanshul.docaction.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

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
    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        file = InstrumentationRegistry.getInstrumentation()
            .targetContext.getDatabasePath("migration-test.db"),
        driver = AndroidSQLiteDriver(),
        databaseClass = DocActionDatabase::class,
    )

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
}
