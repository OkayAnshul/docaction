# Data model

Two distinct models with a deliberate gap between them:

- **Domain model** — immutable Kotlin in `:domain`, no Android, no persistence annotations.
- **Persistence model** — Room entities in `:core:database`, holding *metadata only*.

They are mapped explicitly. Sharing one set of classes would drag Room annotations into the pure
JVM module and, more importantly, would tempt someone to persist document content because it was
already modelled ([12-privacy-security.md](12-privacy-security.md#privacy)).

---

## Domain model

### Confidence

```kotlin
sealed interface Confident<out T> {
    data class High<T>(val value: T, val source: SourceReference) : Confident<T>
    data class Medium<T>(val value: T, val source: SourceReference, val reason: String) : Confident<T>
    data class Low<T>(val value: T, val source: SourceReference, val reason: String) : Confident<T>
    data class Missing(val reason: String) : Confident<Nothing>
}

val <T> Confident<T>.valueOrNull: T?
    get() = when (this) {
        is Confident.High<T> -> value
        is Confident.Medium<T> -> value
        is Confident.Low<T> -> value
        Missing -> null           // no default. ever.
    }
```

`Missing` is an object-like case with no value field, so there is nothing to accidentally
default. See [ADR-003](05-architecture.md#adr-003--confidence-is-a-type-not-a-number).

### Provenance

```kotlin
sealed interface SourceReference {
    data class PdfSpan(val page: Int, val bounds: BoundingBox) : SourceReference
    data class SheetCell(val sheet: String, val row: Int, val column: Int) : SourceReference
    data class SheetRange(val sheet: String, val from: SheetCell, val to: SheetCell) : SourceReference
    data class ImageRegion(val bounds: BoundingBox) : SourceReference
    data class CsvCell(val line: Int, val column: Int) : SourceReference
    data class Derived(val from: List<SourceReference>, val rule: String) : SourceReference
    data class UserProvided(val field: String, val at: Instant) : SourceReference
}

data class BoundingBox(val left: Float, val top: Float, val right: Float, val bottom: Float)
```

`Derived` is how a value assembled from several places stays traceable — a class whose title came
from a merged cell and whose time came from a column header is `Derived(from = [cellA, headerB],
rule = "grid-cell")`. `UserProvided` is deliberately a *source*, not a flag: a corrected value is
as traceable as an extracted one, and the two can never be confused.

### Normalisation

```kotlin
data class Normalised<T>(
    val raw: String,
    val value: T,
    val rule: NormalisationRule?,   // null when raw parsed cleanly
)

enum class NormalisationRule {
    OcrCharacterSubstitution,   // "1O:OO" → "10:00"
    WhitespaceCollapse,
    CaseNormalisation,          // "OCTOBER" → "October"
    SeparatorNormalisation,     // "18.09" → "18/09"
    AbbreviationExpansion,      // "Sept" → "September"
}
```

Every normalisation keeps its raw input and states the rule applied. A normalised value is never
`High` confidence when `OcrCharacterSubstitution` was involved — substituting `O` for `0` is a
guess, a good one, but a guess.

### Document

```kotlin
data class DocumentSource(
    val uri: String,
    val displayName: String,
    val declaredMimeType: String?,     // what the OS claimed — untrusted
    val sizeBytes: Long,
)

enum class DocumentFormat { Pdf, Image, Xlsx, Csv, PlainText, Unsupported }

data class DocumentContent(
    val format: DocumentFormat,
    val pages: List<PageContent>,
    val warnings: List<ContentWarning>,   // "page 4 had no text layer"
)

data class PageContent(
    val index: Int,
    val widthPt: Float,
    val heightPt: Float,
    val runs: List<TextRun>,
)

/** A positioned piece of text. The atom of everything downstream. */
data class TextRun(
    val text: String,
    val bounds: BoundingBox,
    val confidence: Float?,           // null for PDF text layer — it isn't a guess
    val origin: TextOrigin,           // PdfTextLayer | Ocr | SpreadsheetCell
)
```

`TextRun` is the interface between *format handling* and *understanding*. Everything upstream
produces it; everything downstream consumes only it. That is what makes format extensibility real
rather than aspirational — a new format needs to emit `TextRun`s and nothing else.

Note `confidence: Float?` is nullable: text lifted from a PDF text layer has no confidence
because it is not a recognition result. Storing `1.0f` there would conflate certainty with
absence of measurement.

### Extraction results

```kotlin
data class DateInterpretation(
    val candidates: List<LocalDate>,       // >1 means genuinely ambiguous
    val resolvedBy: ResolutionEvidence?,   // null + >1 candidate = must ask
    val source: SourceReference,
)

sealed interface ResolutionEvidence {
    data class SiblingDate(val at: SourceReference) : ResolutionEvidence      // a day > 12 elsewhere
    data class WeekdayAgreement(val weekday: DayOfWeek) : ResolutionEvidence
    data class DocumentDeclaration(val at: SourceReference) : ResolutionEvidence
    data class UserChoice(val at: Instant) : ResolutionEvidence
}

data class TimeInterpretation(
    val start: LocalTime,
    val end: LocalTime?,
    val meridiemInferred: Boolean,      // true = we decided AM/PM; caps confidence at Medium
    val source: SourceReference,
)
```

Device locale is absent from `ResolutionEvidence` by design — it is a presentation hint for
ordering choices, never a resolution ([ADR-004](05-architecture.md#adr-004--ambiguity-is-preserved-not-resolved)).

### Schedule

```kotlin
data class ScheduleGroup(
    val id: GroupId,
    val label: String,                  // "Semester 5 · Section B"
    val entries: List<ScheduleEntry>,
    val source: SourceReference,
)

data class ScheduleEntry(
    val id: EntryId,
    val title: Confident<String>,
    val date: Confident<LocalDate>,           // dated schedules
    val weekday: Confident<DayOfWeek>,        // recurring schedules
    val startTime: Confident<LocalTime>,
    val endTime: Confident<LocalTime>,
    val location: Confident<String>,
    val instructor: Confident<String>,
    val recurrence: Confident<Recurrence>,
)

data class Recurrence(
    val frequency: Frequency,           // Weekly only in V1
    val byWeekday: Set<DayOfWeek>,
    val until: LocalDate,               // never unbounded — ADR-005
    val exceptions: List<LocalDate>,    // EXDATE; populated later for holidays
)
```

An entry carries both `date` and `weekday`; exactly one is expected to be non-`Missing` depending
on schedule type, and the validator enforces that rather than the type system, because "one of
these two" is awkward to express and easy to state as a rule.

### Actions

```kotlin
sealed interface ActionCandidate {
    val id: CandidateId
    val sources: List<SourceReference>
    val status: CandidateStatus            // Ready | NeedsAttention | Excluded
}

data class CalendarEventCandidate private constructor(
    override val id: CandidateId,
    val title: String,                     // resolved — not Confident
    val start: ZonedDateTime,
    val end: ZonedDateTime,
    val location: String?,
    val recurrence: Recurrence?,
    val reminderMinutes: Int?,
    override val sources: List<SourceReference>,
    override val status: CandidateStatus,
) : ActionCandidate {

    companion object {
        /** The only construction path. Rejects Missing in required positions. */
        fun from(entry: ScheduleEntry, zone: ZoneId, term: TermBounds): Outcome<CalendarEventCandidate>
    }
}
```

The crucial detail: **`CalendarEventCandidate` holds plain resolved values, not `Confident<T>`.**
Confidence exists during extraction and review; by the time something is a candidate, every
required field has been extracted with sufficient confidence or explicitly supplied by the user.
The private constructor plus `from()` is the choke point where that transition is enforced —
one function to audit, and it is impossible to reach an executor without passing through it.

### Outcome

```kotlin
sealed interface Outcome<out T> {
    data class Success<T>(val value: T) : Outcome<T>
    data class Partial<T>(val value: T, val issues: List<Issue>) : Outcome<T>
    data class Failure(val reason: FailureReason) : Outcome<Nothing>
}
```

`Partial` exists because it is the honest description of most real documents: 40 entries read
cleanly, 2 didn't. Collapsing that into success or failure would force the pipeline to lie in one
direction or the other.

`FailureReason` is a closed set mapping 1:1 to a user-facing recovery screen — `Encrypted`,
`Corrupt`, `NoTextLayer`, `Empty`, `TooLarge`, `UnsupportedFormat`, `PermissionRevoked`,
`Timeout`, `Cancelled`, `NothingActionable`. No free-text errors escape the domain, which is what
guarantees [FR-8.1](02-requirements.md#fr-8-failure).

---

## Persistence model

Room, in `:core:database`. **Metadata only.**

```kotlin
@Entity("imports")
data class ImportEntity(
    @PrimaryKey val id: String,
    val displayName: String,        // "Timetable.pdf" — a filename, not content
    val format: String,
    val contentHash: String,        // SHA-256, for re-import detection
    val startedAt: Long,
    val completedAt: Long?,
    val state: String,              // InProgress | AwaitingReview | Committed | Discarded | Failed
    val candidateCount: Int,
    val committedCount: Int,
    val failureReason: String?,
)

@Entity("created_events")
data class CreatedEventEntity(
    @PrimaryKey val id: String,           // == entryId, matches the CUSTOM_APP_URI path
    val importId: String,
    val calendarEventId: Long,            // Calendar Provider _ID — fast path
    val calendarId: Long,
    val customAppUri: String,             // source of truth for undo
    val createdAt: Long,
    val revokedAt: Long?,                 // set on undo; row retained for the audit trail
)

@Entity("resolved_conventions")
data class ResolvedConventionEntity(
    @PrimaryKey val key: String,          // "date_order", "term_end", "preferred_calendar"
    val value: String,
    val updatedAt: Long,
)
```

### What is deliberately not persisted

| Not stored | Why |
|---|---|
| Document bytes | The document stays where the user put it. We hold a URI. |
| Extracted text | Would turn a cleared app into a data breach surface. |
| Event titles, locations, instructor names | This is the sensitive part — where a person is, when. |
| OCR output | Same. |
| Anything from a discarded import | Discard means discard. |

An in-progress import needs its candidates to survive process death ([FR-7.4](02-requirements.md#fr-7-history)).
Those live in an **ephemeral** table that is:
- written only while state is `InProgress` or `AwaitingReview`,
- deleted on commit, discard, or app start if older than 24 hours,
- excluded from backup via `dataExtractionRules`.

The history entry that survives says *"Timetable.pdf · 42 events · 10 Aug"*. It does not say what
the classes were.

### Backup

`backup_rules.xml` / `data_extraction_rules.xml` exclude the ephemeral candidate table and include
only `imports`, `created_events`, and `resolved_conventions`. Cloud backup of a user's class
schedule is exactly the kind of quiet data flow this product exists to avoid.

---

## Identity and hashing

- `contentHash` is SHA-256 over the file bytes, streamed, capped at the first 32 MB for very
  large files (with the size included in the hash input so truncation can't collide).
- Used only for re-import detection ([UC-5](02-requirements.md#uc-5-re-import-of-an-already-imported-document)).
  It never leaves the device and is not an analytics identifier.
- `entryId` is a UUID generated at candidate creation, embedded in `CUSTOM_APP_URI`, and stable
  across the review→commit transition so provenance survives editing.
