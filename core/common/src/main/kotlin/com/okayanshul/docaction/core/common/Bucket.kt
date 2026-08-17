package com.okayanshul.docaction.core.common

/**
 * Analytics counts are bucketed, never exact. An exact count plus a timestamp is
 * closer to an identifier than it looks. See docs/12-privacy-security.md § Analytics.
 */
fun bucketCount(value: Int): String = when {
    value <= 0 -> "0"
    value == 1 -> "1"
    value <= 10 -> "2-10"
    value <= 50 -> "11-50"
    else -> "50+"
}

fun bucketDurationMillis(value: Long): String = when {
    value < 500 -> "<0.5s"
    value < 2_000 -> "0.5-2s"
    value < 10_000 -> "2-10s"
    value < 60_000 -> "10-60s"
    else -> ">60s"
}
