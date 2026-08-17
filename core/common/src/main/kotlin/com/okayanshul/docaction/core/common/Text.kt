package com.okayanshul.docaction.core.common

/**
 * Text helpers shared by the extraction engines. Deliberately conservative: nothing
 * here changes meaning, only whitespace and case, so callers can use these without
 * recording a normalisation rule.
 */
object Text {

    private val whitespace = Regex("\\s+")

    /** Collapses runs of whitespace and trims. Never changes characters. */
    fun collapseWhitespace(value: String): String = value.replace(whitespace, " ").trim()

    /**
     * Lowercases, collapses whitespace, and drops punctuation — for comparing two
     * strings that should be considered "the same subject", e.g. duplicate detection
     * between "Data Structures" and "DATA STRUCTURES (DSA)".
     */
    fun comparisonKey(value: String): String =
        collapseWhitespace(value).lowercase().filter { it.isLetterOrDigit() || it == ' ' }.trim()

    /**
     * Similarity in 0..1 using normalised Levenshtein distance over [comparisonKey].
     * Used only to *suggest* duplicates; a match is never acted on without the user.
     */
    fun similarity(a: String, b: String): Double {
        val left = comparisonKey(a)
        val right = comparisonKey(b)
        if (left == right) return 1.0
        if (left.isEmpty() || right.isEmpty()) return 0.0
        val distance = levenshtein(left, right)
        return 1.0 - distance.toDouble() / maxOf(left.length, right.length)
    }

    private fun levenshtein(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, substitution)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }
}
