package com.okayanshul.docaction.core.database

import android.content.Context

/**
 * The only way other modules get at persistence.
 *
 * Handing out DAOs rather than the `RoomDatabase` keeps Room an implementation detail of
 * this module: consumers compile against plain interfaces, and swapping the storage engine
 * would not touch them. It also keeps a single process-wide instance, which matters because
 * a broadcast receiver and the app can both reach for the database at once.
 */
object Databases {

    @Volatile
    private var instance: DocActionDatabase? = null

    fun reminders(context: Context): ReminderDao = database(context).reminders()

    fun createdEvents(context: Context): CreatedEventDao = database(context).createdEvents()

    fun timetables(context: Context): TimetableDao = database(context).timetables()

    private fun database(context: Context): DocActionDatabase =
        instance ?: synchronized(this) {
            instance ?: DocActionDatabase.build(context).also { instance = it }
        }

    /**
     * A disposable in-memory database for instrumented tests.
     *
     * Returns a handle exposing DAOs and `close()` only — deliberately not the
     * `RoomDatabase` itself, so tests compile against the same Room-free surface production
     * code does.
     */
    fun inMemory(context: Context): Handle = Handle(DocActionDatabase.inMemory(context))

    class Handle internal constructor(private val database: DocActionDatabase) : AutoCloseable {
        val reminders: ReminderDao get() = database.reminders()
        val createdEvents: CreatedEventDao get() = database.createdEvents()
        val timetables: TimetableDao get() = database.timetables()
        override fun close() = database.close()
    }
}
