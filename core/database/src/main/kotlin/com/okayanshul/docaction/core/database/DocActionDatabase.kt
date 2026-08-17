package com.okayanshul.docaction.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.execSQL

@Database(
    entities = [
        ImportEntity::class,
        CreatedEventEntity::class,
        ResolvedConventionEntity::class,
        ScheduledReminderEntity::class,
        TimetableEntity::class,
        TimetableSlotEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class DocActionDatabase : RoomDatabase() {

    abstract fun reminders(): ReminderDao

    abstract fun createdEvents(): CreatedEventDao

    abstract fun timetables(): TimetableDao

    companion object {
        const val NAME = "docaction.db"

        fun build(context: Context): DocActionDatabase =
            Room.databaseBuilder(context.applicationContext, DocActionDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2)
                .build()

        /**
         * Adds the timetable tables. Additive only — nothing existing is touched.
         *
         * Written by hand rather than falling back to destructive migration: the reminder
         * rows in version 1 are armed alarms, and dropping them would silently stop every
         * pending notification on a user's phone during an app update.
         */
        internal val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(connection: androidx.sqlite.SQLiteConnection) {
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `timetables` (
                        `id` TEXT NOT NULL,
                        `label` TEXT NOT NULL,
                        `termStartEpochDay` INTEGER NOT NULL,
                        `termEndEpochDay` INTEGER NOT NULL,
                        `zoneId` TEXT NOT NULL,
                        `sourceName` TEXT,
                        `sourceHash` TEXT,
                        `importId` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `timetable_slots` (
                        `id` TEXT NOT NULL,
                        `timetableId` TEXT NOT NULL,
                        `entryId` TEXT NOT NULL,
                        `weekday` INTEGER NOT NULL,
                        `startMinute` INTEGER NOT NULL,
                        `endMinute` INTEGER NOT NULL,
                        `title` TEXT NOT NULL,
                        `location` TEXT,
                        `customAppUri` TEXT,
                        `endAssumed` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_timetable_slots_timetableId` " +
                        "ON `timetable_slots` (`timetableId`)"
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_timetable_slots_weekday` " +
                        "ON `timetable_slots` (`weekday`)"
                )
            }
        }

        /** For instrumented tests: same schema, nothing written to disk. */
        fun inMemory(context: Context): DocActionDatabase =
            Room.inMemoryDatabaseBuilder(context, DocActionDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }
}
