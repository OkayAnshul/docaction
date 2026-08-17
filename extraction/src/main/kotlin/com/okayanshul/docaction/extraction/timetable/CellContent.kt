package com.okayanshul.docaction.extraction.timetable

import com.okayanshul.docaction.extraction.date.DateEngine
import com.okayanshul.docaction.extraction.time.TimeEngine

/** What a column or row of a timetable holds. Decided by sampling content, never by position. */
enum class CellRole { Weekday, Time, Date, Subject, Location, Instructor, Unknown }

/** A subject cell split into its parts. */
data class SubjectCell(val subject: String, val location: String?, val instructor: String?)

/**
 * Recognises what a cell contains.
 *
 * Header text raises confidence in a column's role but never overrides its content: a
 * column where most cells match a time pattern is the time column whether it is labelled
 * "Time", "Period", or nothing at all. Real documents label inconsistently and leave
 * headers blank often enough that trusting them would be fragile.
 */
class CellContent(
    private val dateEngine: DateEngine = DateEngine(),
    private val timeEngine: TimeEngine = TimeEngine(),
) {

    fun looksLikeWeekday(text: String): Boolean =
        dateEngine.parse(text.trim()).any { it.weekday != null && it.readings.isEmpty() }

    fun looksLikeTime(text: String): Boolean = timeEngine.parse(text).isNotEmpty()

    /**
     * Only a *readable* date counts. Impossible parses are deliberately excluded: the time
     * range `09:00-10:00` contains `00-10`, which the date engine correctly reports as an
     * impossible date, and counting that as "this cell holds a date" made the engine
     * mistake ordinary timetables for dated schedules.
     */
    fun looksLikeDate(text: String): Boolean =
        dateEngine.parse(text).any { it.readings.isNotEmpty() }

    /**
     * Room codes: `K10`, `LT-3`, `Room 204`, `Lab 2`, `C-101`.
     *
     * These are deliberately *not* treated as geographic locations. Handing `K10` to a
     * maps app produces a confident, absurd result somewhere in another country — exactly
     * the class of failure this product exists to avoid.
     */
    fun looksLikeRoom(text: String): Boolean = roomScore(text) > 0

    /**
     * How strongly this reads as a room rather than a subject code, 0–2.
     *
     * Course codes and room codes overlap badly: `IND4` has exactly the shape of `K10`.
     * When a cell holds two room-shaped tokens the more specific one wins, which is how
     * `IND4 / C25-B001` resolves the right way round without assuming an order — both
     * `DSA / K10` and `K10 / DSA` occur in real documents.
     */
    fun roomScore(text: String): Int {
        val value = text.trim()
        if (value.isEmpty() || value.length > 20) return 0
        return when {
            COMPOUND_ROOM.matches(value) || KEYWORD_ROOM.matches(value) -> 2
            SIMPLE_ROOM.matches(value) || NUMERIC_ROOM.matches(value) -> 1
            else -> 0
        }
    }

    fun looksLikeInstructor(text: String): Boolean {
        val value = text.trim()
        return INSTRUCTOR_PREFIX.containsMatchIn(value)
    }

    /** The dominant role across a set of cells, if any single role covers most of them. */
    fun classify(values: List<String>, threshold: Float = 0.6f): CellRole {
        val populated = values.map { it.trim() }.filter { it.isNotEmpty() }
        if (populated.isEmpty()) return CellRole.Unknown

        val counts = mapOf(
            CellRole.Weekday to populated.count { looksLikeWeekday(it) },
            CellRole.Time to populated.count { looksLikeTime(it) },
            CellRole.Date to populated.count { looksLikeDate(it) },
            CellRole.Location to populated.count { looksLikeRoom(it) },
            CellRole.Instructor to populated.count { looksLikeInstructor(it) },
        )

        // Weekday beats date when both match — "Monday" is not a date.
        val ordered = listOf(CellRole.Weekday, CellRole.Time, CellRole.Date, CellRole.Location, CellRole.Instructor)
        val winner = ordered.firstOrNull { (counts[it] ?: 0).toFloat() / populated.size >= threshold }

        return winner ?: CellRole.Subject
    }

    /**
     * Splits `DSA / K10`, `DSA (K10)`, `DSA\nK10`, `K10 / DSA` into parts.
     *
     * The room is identified by pattern rather than by position, because both orders occur
     * in real timetables and assuming one would silently swap subject and room.
     */
    fun parseSubject(text: String): SubjectCell? {
        val parts = text.split(*SPLITTERS)
            .map { it.trim().trim('(', ')', '[', ']', '-', ',') }
            .filter { it.isNotEmpty() }

        if (parts.isEmpty()) return null

        // The most room-like token is the room; ties go to the later one, since a room
        // more often follows a subject than precedes it.
        val location = parts.filter { roomScore(it) > 0 }
            .maxByOrNull { roomScore(it) * 10 + parts.indexOf(it) }
        val instructor = parts.firstOrNull { it != location && looksLikeInstructor(it) }
        val subject = parts.firstOrNull { it != location && it != instructor }

        return if (subject == null) null else SubjectCell(subject, location, instructor)
    }

    private companion object {
        val SPLITTERS = arrayOf("\n", "/", "|", ",", " - ", " – ", "(", ")")

        /** K10, LT3, C-101, AB-205 */
        val SIMPLE_ROOM = Regex("""^[A-Za-z]{1,4}[-\s]?\d{1,4}[A-Za-z]?$""")

        /**
         * Building-plus-room codes: C25-A107, C25-B318. More specific than [SIMPLE_ROOM],
         * so it outranks a subject code that happens to share that shape.
         */
        val COMPOUND_ROOM = Regex("""^[A-Za-z]{1,4}\d{1,4}[-/\s][A-Za-z]{1,4}\d{1,4}[A-Za-z]?$""")

        /** Room 204, Lab 2, Hall A, Block C — named explicitly, so unambiguous. */
        val KEYWORD_ROOM =
            Regex("""^(room|rm|lab|hall|block|theatre|theater|lt)\.?\s*[-\s]?\w{1,6}$""", RegexOption.IGNORE_CASE)

        /** 204-B */
        val NUMERIC_ROOM = Regex("""^\d{1,4}[-\s]?[A-Za-z]{1,3}$""")

        val INSTRUCTOR_PREFIX = Regex("""\b(prof|dr|mr|mrs|ms|miss)\.?\s""", RegexOption.IGNORE_CASE)
    }
}
