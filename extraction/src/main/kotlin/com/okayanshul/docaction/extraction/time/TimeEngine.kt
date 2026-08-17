package com.okayanshul.docaction.extraction.time

import java.time.LocalTime

/** Whether AM/PM was stated, and if so which. */
enum class Meridiem { Am, Pm, Unstated }

/**
 * One time found in text, before any meridiem inference.
 *
 * [hour] is as written: `10` stays 10 whether or not it turns out to mean 22:00. That
 * decision belongs to [MeridiemResolver], which can see the whole column.
 */
data class TimeToken(
    val raw: String,
    val range: IntRange,
    val hour: Int,
    val minute: Int,
    val meridiem: Meridiem,
    /**
     * The hour was written zero-padded, e.g. `09:00` rather than `9:00`. Nobody writes a
     * 12-hour clock that way, so this is real evidence of 24-hour notation drawn from the
     * document itself rather than an assumption about habits.
     */
    val paddedHour: Boolean = false,
) {
    /** True when the written form can only be 24-hour, e.g. `14:30`. */
    val proves24Hour: Boolean
        get() = meridiem == Meridiem.Unstated && (hour in 13..23 || (paddedHour && hour in 0..12))

    val isValid: Boolean get() = hour in 0..23 && minute in 0..59

    /**
     * The time as written, with nothing inferred. Null for a bare 12-hour value such as
     * `10:00`, which genuinely could be either — resolving that is a separate step.
     */
    fun asWritten(): LocalTime? = when {
        !isValid -> null
        meridiem == Meridiem.Am -> LocalTime.of(if (hour == 12) 0 else hour, minute)
        meridiem == Meridiem.Pm -> LocalTime.of(if (hour == 12) 12 else hour + 12, minute)
        hour == 0 || hour > 12 -> LocalTime.of(hour, minute)
        else -> null
    }

    /**
     * The hour exactly as written, for a column already known to be 24-hour.
     *
     * Distinct from [under] on purpose, and the distinction matters at one value: in 12-hour
     * notation `12` before noon is `00`, and in 24-hour notation `12` is `12`. Using the
     * 12-hour conversion inside the 24-hour branch turned every `11:00-12:00` class into one
     * that ran from eleven in the morning until midnight.
     */
    internal fun literal(): LocalTime? =
        if (isValid) LocalTime.of(hour, minute) else null

    /** The time under an assumed meridiem, used by the column solver. */
    internal fun under(pm: Boolean): LocalTime? {
        if (!isValid) return null
        val h = when {
            hour == 12 -> if (pm) 12 else 0
            hour > 12 -> hour
            else -> if (pm) hour + 12 else hour
        }
        return if (h in 0..23) LocalTime.of(h, minute) else null
    }
}

/** A start, and an end when the text gave a range. */
data class TimeRange(val start: TimeToken, val end: TimeToken?, val raw: String, val range: IntRange)

/**
 * Finds times and time ranges in text. Never invents a meridiem.
 *
 * Invalid clock values such as `25:90` are returned with [TimeToken.isValid] false rather
 * than dropped, so validation can flag them alongside their original text instead of
 * silently losing them.
 */
class TimeEngine {

    fun parse(text: String): List<TimeRange> {
        val ranges = RANGE.findAll(text)
            .filterNot { partOfDate(text, it.range) }
            .mapNotNull { m ->
                val start = token(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.range)
                    ?: return@mapNotNull null
                val end = token(m.groupValues[4], m.groupValues[5], m.groupValues[6], m.range)
                // "9-10 AM" states the meridiem once, at the end; it governs both sides.
                val resolvedStart =
                    if (start.meridiem == Meridiem.Unstated && end != null && end.meridiem != Meridiem.Unstated) {
                        start.copy(meridiem = end.meridiem)
                    } else {
                        start
                    }
                TimeRange(resolvedStart, end, m.value, m.range)
            }
            .toList()

        val singles = SINGLE.findAll(text)
            .filterNot { partOfDate(text, it.range) }
            .mapNotNull { m ->
                val minute = m.groupValues[2]
                val marker = m.groupValues[3]
                // A bare integer is not a time. Require minutes or an explicit marker.
                if (minute.isEmpty() && marker.isEmpty()) return@mapNotNull null
                token(m.groupValues[1], minute, marker, m.range)
                    ?.let { TimeRange(it, null, m.value.trim(), m.range) }
            }
            .toList()

        val kept = ranges.toMutableList()
        singles.forEach { single ->
            val covered = ranges.any { it.range.first <= single.range.first && single.range.last <= it.range.last }
            if (!covered) kept += single
        }
        return kept.sortedBy { it.range.first }
    }

    /**
     * `18-09-2026` and `2026-09-18` both contain something that looks like a time range.
     * A match flanked by a date separator followed by more digits is part of a date.
     * Column-level content sampling is the real defence; this removes the obvious cases.
     */
    private fun partOfDate(text: String, range: IntRange): Boolean {
        val after = text.getOrNull(range.last + 1)
        if (after != null && after in DATE_SEPARATORS && text.getOrNull(range.last + 2)?.isDigit() == true) {
            return true
        }
        val before = text.getOrNull(range.first - 1)
        if (before != null && before in DATE_SEPARATORS && text.getOrNull(range.first - 2)?.isDigit() == true) {
            return true
        }
        return false
    }

    private fun token(hour: String, minute: String, marker: String, range: IntRange): TimeToken? {
        if (hour.isEmpty()) return null
        val h = hour.toIntOrNull() ?: return null
        val m = if (minute.isEmpty()) 0 else minute.toIntOrNull() ?: return null
        val meridiem = when (marker.lowercase().replace(".", "").trim()) {
            "am" -> Meridiem.Am
            "pm" -> Meridiem.Pm
            else -> Meridiem.Unstated
        }
        return TimeToken(
            raw = buildString {
                append(hour)
                if (minute.isNotEmpty()) append(':').append(minute)
                if (marker.isNotEmpty()) append(' ').append(marker.trim())
            },
            range = range,
            hour = h,
            minute = m,
            meridiem = meridiem,
            paddedHour = hour.length == 2 && hour[0] == '0',
        )
    }

    private companion object {
        const val DATE_SEPARATORS = "/.-"
        const val MARKER = """\s*([aApP]\.?[mM]\.?)?"""
        const val CLOCK = """(\d{1,2})(?:[:.](\d{2}))?"""

        /** `9:00-10:00`, `10–11 AM`, `9 to 10`. Six groups: clock, clock. */
        val RANGE = Regex("""\b$CLOCK$MARKER\s*(?:[-–—]|to)\s*$CLOCK$MARKER""")

        /** `14:30`, `10.30`, `10 AM`. Bare integers are filtered out by the caller. */
        val SINGLE = Regex("""\b$CLOCK$MARKER""")
    }
}
