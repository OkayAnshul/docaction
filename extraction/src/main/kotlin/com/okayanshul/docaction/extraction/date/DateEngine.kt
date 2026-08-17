package com.okayanshul.docaction.extraction.date

import com.okayanshul.docaction.domain.DateOrder
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month

/**
 * One possible reading of a date found in text. [year] is null when the document didn't
 * state one — that is a fact to be resolved later, never a value to be assumed here.
 */
data class DateReading(val day: Int, val month: Int, val year: Int?) {

    fun toLocalDate(assumedYear: Int? = null): LocalDate? {
        val y = year ?: assumedYear ?: return null
        return runCatching { LocalDate.of(y, month, day) }.getOrNull()
    }

    val isCalendarValid: Boolean
        get() {
            if (month !in 1..12 || day < 1) return false
            val knownYear = year
            // Without a year, February 29 stays possible — a leap year would make it real.
            return if (knownYear == null) {
                day <= Month.of(month).maxLength()
            } else {
                runCatching { LocalDate.of(knownYear, month, day) }.isSuccess
            }
        }
}

/**
 * A date-shaped piece of text and every reading of it.
 *
 * - `readings.size == 1` — unambiguous
 * - `readings.size == 2` — the numeric order is genuinely ambiguous; both are kept
 * - `readings.isEmpty()` with [invalidReason] set — impossible, and never coerced
 * - [weekday] set with no readings — a bare weekday name, used by recurring schedules
 */
data class DateMatch(
    val raw: String,
    val range: IntRange,
    val readings: List<DateReading>,
    val weekday: DayOfWeek? = null,
    val invalidReason: String? = null,
    /**
     * Set only when this match proves the document's numeric order on its own — i.e. a
     * component greater than 12 ruled the other reading out. This is the strongest
     * evidence available for resolving *other* dates in the same document.
     */
    val provenOrder: DateOrder? = null,
) {
    val isAmbiguous: Boolean get() = readings.size > 1
    val isInvalid: Boolean get() = invalidReason != null
}

/**
 * Finds dates in text. Never resolves ambiguity — that is the resolver's job, and it
 * needs the whole document to do it properly.
 *
 * Stateless and clock-free: two calls with the same input always agree.
 */
class DateEngine {

    fun parse(text: String): List<DateMatch> {
        val matches = mutableListOf<DateMatch>()
        matches += isoMatches(text)
        matches += monthNameMatches(text)
        matches += numericMatches(text)
        matches += weekdayMatches(text)
        return dropOverlaps(matches)
    }

    /** Highest-priority (most specific) form wins where two patterns cover the same span. */
    private fun dropOverlaps(all: List<DateMatch>): List<DateMatch> {
        val sorted = all.sortedWith(compareBy({ it.range.first }, { -(it.range.last - it.range.first) }))
        val kept = mutableListOf<DateMatch>()
        for (match in sorted) {
            if (kept.none { it.range.first <= match.range.last && match.range.first <= it.range.last }) {
                kept += match
            }
        }
        return kept.sortedBy { it.range.first }
    }

    // 2026-09-18 — unambiguous by definition.
    private fun isoMatches(text: String) = ISO.findAll(text).map { m ->
        val (y, mo, d) = m.destructured
        val reading = DateReading(d.toInt(), mo.toInt(), y.toInt())
        if (reading.isCalendarValid) {
            DateMatch(m.value, m.range, listOf(reading))
        } else {
            DateMatch(m.value, m.range, emptyList(), invalidReason = invalidReasonFor(reading))
        }
    }.toList()

    // 18/09/2026, 18-09-26, 18.09 — the ambiguous family.
    private fun numericMatches(text: String) = NUMERIC.findAll(text).mapNotNull { m ->
        val a = m.groupValues[1].toInt()
        val b = m.groupValues[2].toInt()
        val rawYear = m.groupValues.getOrNull(3)?.takeIf { it.isNotEmpty() }
        val year = rawYear?.let { normaliseYear(it) }

        val dayFirst = DateReading(a, b, year)
        val monthFirst = DateReading(b, a, year)

        val valid = listOf(dayFirst, monthFirst).filter { it.isCalendarValid }
        when {
            valid.isEmpty() -> DateMatch(
                raw = m.value,
                range = m.range,
                readings = emptyList(),
                invalidReason = invalidReasonFor(dayFirst),
            )

            // Only one reading is possible: a component > 12 proved the order.
            valid.size == 1 -> DateMatch(
                raw = m.value,
                range = m.range,
                readings = valid,
                provenOrder = if (valid.single() == dayFirst) DateOrder.DayFirst else DateOrder.MonthFirst,
            )

            // Both readings are calendar-valid. Genuinely ambiguous — keep both,
            // day-first listed first so the caller can present a stable order.
            dayFirst == monthFirst -> DateMatch(m.value, m.range, listOf(dayFirst))

            else -> DateMatch(m.value, m.range, listOf(dayFirst, monthFirst))
        }
    }.toList()

    // Sep 18 / September 18 / 18 September / 18th Sept 2026 — unambiguous.
    private fun monthNameMatches(text: String): List<DateMatch> {
        val out = mutableListOf<DateMatch>()

        DAY_MONTH.findAll(text).forEach { m ->
            val day = m.groupValues[1].toInt()
            val month = monthOf(m.groupValues[3]) ?: return@forEach
            val year = m.groupValues.getOrNull(4)?.takeIf { it.isNotEmpty() }?.let { normaliseYear(it) }
            out += buildNamedMatch(m.value, m.range, day, month, year)
        }

        MONTH_DAY.findAll(text).forEach { m ->
            val month = monthOf(m.groupValues[1]) ?: return@forEach
            val day = m.groupValues[2].toInt()
            val year = m.groupValues.getOrNull(4)?.takeIf { it.isNotEmpty() }?.let { normaliseYear(it) }
            out += buildNamedMatch(m.value, m.range, day, month, year)
        }

        return out
    }

    private fun buildNamedMatch(raw: String, range: IntRange, day: Int, month: Int, year: Int?): DateMatch {
        val reading = DateReading(day, month, year)
        return if (reading.isCalendarValid) {
            DateMatch(raw, range, listOf(reading))
        } else {
            DateMatch(raw, range, emptyList(), invalidReason = invalidReasonFor(reading))
        }
    }

    private fun weekdayMatches(text: String) = WEEKDAY.findAll(text).mapNotNull { m ->
        weekdayOf(m.groupValues[1])?.let { DateMatch(m.value, m.range, emptyList(), weekday = it) }
    }.toList()

    private fun invalidReasonFor(reading: DateReading): String = when {
        reading.month !in 1..12 -> "there is no month ${reading.month}"
        reading.day < 1 -> "there is no day ${reading.day}"
        reading.day > Month.of(reading.month).maxLength() ->
            "${Month.of(reading.month).displayName()} doesn't have ${reading.day} days"

        else -> "${Month.of(reading.month).displayName()} ${reading.day} isn't a real date in ${reading.year}"
    }

    /** Two-digit years window to ±50 years around the pivot, matching ISO 8601 practice. */
    private fun normaliseYear(raw: String): Int {
        val value = raw.toInt()
        if (raw.length == 4) return value
        val century = PIVOT_YEAR / 100 * 100
        val candidate = century + value
        return if (candidate - PIVOT_YEAR > 50) candidate - 100 else candidate
    }

    private fun monthOf(name: String): Int? = MONTHS[name.lowercase().removeSuffix(".")]

    private fun weekdayOf(name: String): DayOfWeek? = WEEKDAYS[name.lowercase().removeSuffix(".")]

    private fun Month.displayName() = name.lowercase().replaceFirstChar { it.uppercase() }

    companion object {
        /**
         * Pivot for two-digit years. Fixed rather than derived from the clock so parsing
         * is deterministic and testable; revisit before it drifts far from the present.
         */
        const val PIVOT_YEAR = 2026

        private val ISO = Regex("""\b(\d{4})-(\d{1,2})-(\d{1,2})\b""")

        private val NUMERIC = Regex("""\b(\d{1,2})\s*[/.\-]\s*(\d{1,2})(?:\s*[/.\-]\s*(\d{4}|\d{2}))?\b""")

        private const val MONTH_NAMES =
            "jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|" +
                "aug(?:ust)?|sept?(?:ember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?"

        private val DAY_MONTH = Regex(
            """\b(\d{1,2})(st|nd|rd|th)?\s+($MONTH_NAMES)\.?(?:,?\s+(\d{4}|\d{2}))?\b""",
            RegexOption.IGNORE_CASE,
        )

        private val MONTH_DAY = Regex(
            """\b($MONTH_NAMES)\.?\s+(\d{1,2})(st|nd|rd|th)?(?:,?\s+(\d{4}|\d{2}))?\b""",
            RegexOption.IGNORE_CASE,
        )

        private val WEEKDAY = Regex(
            """\b(mon(?:day)?|tue(?:s|sday)?|wed(?:nesday)?|thu(?:r|rs|rsday)?|fri(?:day)?|sat(?:urday)?|sun(?:day)?)\b""",
            RegexOption.IGNORE_CASE,
        )

        private val MONTHS: Map<String, Int> = buildMap {
            val full = listOf(
                "january", "february", "march", "april", "may", "june",
                "july", "august", "september", "october", "november", "december",
            )
            full.forEachIndexed { index, name ->
                put(name, index + 1)
                put(name.take(3), index + 1)
            }
            put("sept", 9)
        }

        private val WEEKDAYS: Map<String, DayOfWeek> = buildMap {
            DayOfWeek.entries.forEach { day ->
                val name = day.name.lowercase()
                put(name, day)
                put(name.take(3), day)
            }
            put("tues", DayOfWeek.TUESDAY)
            put("thur", DayOfWeek.THURSDAY)
            put("thurs", DayOfWeek.THURSDAY)
        }
    }
}
