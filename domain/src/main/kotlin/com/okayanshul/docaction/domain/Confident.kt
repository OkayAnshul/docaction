package com.okayanshul.docaction.domain

/**
 * Confidence attached to the value itself, not stored beside it.
 *
 * [Missing] carries no value, so there is no field to accidentally default. Reading a
 * value requires an exhaustive `when`, which forces every consumer to decide what
 * absence means in its context. This is what makes "never fabricate missing data" a
 * property of the type system rather than of developer discipline.
 *
 * See docs/05-architecture.md ADR-003 and docs/09-confidence.md.
 */
sealed interface Confident<out T : Any> {

    /** Read directly and corroborated; nothing was transformed in a way that could change meaning. */
    data class High<out T : Any>(val value: T, val source: SourceReference) : Confident<T>

    /** Read reliably, but something was inferred, repaired, or derived. [reason] is user-facing. */
    data class Medium<out T : Any>(val value: T, val source: SourceReference, val reason: String) : Confident<T>

    /** A decision was made that could be wrong. Requires explicit resolution before use. */
    data class Low<out T : Any>(val value: T, val source: SourceReference, val reason: String) : Confident<T>

    /** Not present in the document. Deliberately holds no value. */
    data class Missing(val reason: String) : Confident<Nothing>
}

val <T : Any> Confident<T>.valueOrNull: T?
    get() = when (this) {
        is Confident.High -> value
        is Confident.Medium -> value
        is Confident.Low -> value
        is Confident.Missing -> null
    }

val Confident<*>.sourceOrNull: SourceReference?
    get() = when (this) {
        is Confident.High -> source
        is Confident.Medium -> source
        is Confident.Low -> source
        is Confident.Missing -> null
    }

/** User-facing explanation of why a field needs attention; null when it doesn't. */
val Confident<*>.reasonOrNull: String?
    get() = when (this) {
        is Confident.High -> null
        is Confident.Medium -> reason
        is Confident.Low -> reason
        is Confident.Missing -> reason
    }

/** The four states surfaced in the UI. Never rendered as a percentage. */
enum class ConfidenceState { Ready, Check, Missing, Invalid }

val Confident<*>.state: ConfidenceState
    get() = when (this) {
        is Confident.High -> ConfidenceState.Ready
        is Confident.Medium -> ConfidenceState.Ready
        is Confident.Low -> ConfidenceState.Check
        is Confident.Missing -> ConfidenceState.Missing
    }

/** True when this field can be used without asking the user first. */
val Confident<*>.isUsable: Boolean
    get() = this is Confident.High || this is Confident.Medium

fun <T : Any, R : Any> Confident<T>.map(transform: (T) -> R): Confident<R> = when (this) {
    is Confident.High -> Confident.High(transform(value), source)
    is Confident.Medium -> Confident.Medium(transform(value), source, reason)
    is Confident.Low -> Confident.Low(transform(value), source, reason)
    is Confident.Missing -> this
}

/**
 * Lowers confidence by one level, keeping the value. Used when a field contradicts its
 * column's pattern. [High] and [Medium] both cap at [Medium] rather than dropping to
 * [Low], because a single contradiction is a hint, not a decision.
 */
fun <T : Any> Confident<T>.demote(reason: String): Confident<T> = when (this) {
    is Confident.High -> Confident.Medium(value, source, reason)
    is Confident.Medium -> Confident.Low(value, source, reason)
    is Confident.Low -> this
    is Confident.Missing -> this
}

/**
 * Caps confidence at [Confident.Medium]. Applied when a transformation was applied that
 * could have changed meaning — OCR character substitution, an inferred meridiem, a value
 * derived from structure rather than read. These caps are absolute: no amount of other
 * positive signal lifts such a value to [Confident.High], because the transformation
 * itself is the uncertainty and nothing else in the document speaks to it.
 */
fun <T : Any> Confident<T>.capAtMedium(reason: String): Confident<T> = when (this) {
    is Confident.High -> Confident.Medium(value, source, reason)
    else -> this
}
