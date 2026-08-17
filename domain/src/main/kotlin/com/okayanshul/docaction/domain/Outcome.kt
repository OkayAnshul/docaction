package com.okayanshul.docaction.domain

/**
 * Result of a pipeline stage.
 *
 * [Partial] exists because it is the honest description of most real documents: 40
 * entries read cleanly, 2 didn't. Collapsing that into success-or-failure would force
 * the pipeline to lie in one direction or the other.
 */
sealed interface Outcome<out T> {

    data class Success<out T>(val value: T) : Outcome<T>

    data class Partial<out T>(val value: T, val issues: List<Issue>) : Outcome<T>

    data class Failure(val reason: FailureReason) : Outcome<Nothing>
}

val <T> Outcome<T>.valueOrNull: T?
    get() = when (this) {
        is Outcome.Success -> value
        is Outcome.Partial -> value
        is Outcome.Failure -> null
    }

inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Success -> Outcome.Success(transform(value))
    is Outcome.Partial -> Outcome.Partial(transform(value), issues)
    is Outcome.Failure -> this
}

/** A non-fatal problem worth telling the user about, e.g. "page 4 had no text layer". */
data class Issue(val kind: IssueKind, val detail: String, val source: SourceReference? = null)

enum class IssueKind {
    PageSkipped,
    PageTimedOut,
    NoTextOnPage,
    SheetSkipped,
    LowQualityRegion,
    AmbiguousStructure,
}

/**
 * The closed set of ways processing can fail. Each maps to exactly one recovery screen
 * (docs/13-performance.md § Error handling). No free-text error escapes the domain,
 * which is what guarantees the user never sees a stack trace — and also what stops
 * parser exception messages, which routinely quote document content, from reaching
 * crash reports.
 */
enum class FailureReason {
    Empty,
    TooLarge,
    UnsupportedFormat,
    Corrupt,
    Encrypted,
    NoTextLayer,
    PermissionRevoked,
    Timeout,
    Cancelled,
    NothingActionable,
    StorageUnavailable,
    ProcessingUnavailable,
}
