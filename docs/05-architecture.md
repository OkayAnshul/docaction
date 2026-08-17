# Architecture

## System architecture

```
┌──────────────────────────────────────────────────────────────┐
│  UI            Compose screens, navigation, share-sheet entry │
├──────────────────────────────────────────────────────────────┤
│  Presentation  ViewModels, UI state, progress, error mapping  │
├──────────────────────────────────────────────────────────────┤
│  Domain        Models, ports, pipeline orchestration          │  ← no Android
├──────────────────────────────────────────────────────────────┤
│  Engine        Extraction: date, time, table, timetable       │  ← no Android
├──────────────────────────────────────────────────────────────┤
│  Infrastructure  PDF/OCR/spreadsheet readers, Room, Calendar  │
└──────────────────────────────────────────────────────────────┘
                              │
                    ┌─────────┴─────────┐
                    │ isolated process  │  untrusted document parsing
                    └───────────────────┘
```

Dependencies point downward only. The Domain and Engine layers are plain Kotlin — they compile
and test on the JVM with no emulator, no Robolectric, and no Android SDK on the classpath.

That constraint is not stylistic. The extraction engine is where correctness lives, it needs
hundreds of fast tests, and every Android dependency added to it makes those tests slower and
more fragile. It is enforced mechanically (see [Dependency rules](#dependency-rules)).

---

## Module architecture

Eight modules. The spec proposed roughly twenty; that is the right *eventual* shape but the wrong
starting shape — module boundaries that don't yet correspond to real dependency pressure cost
build time and refactoring friction while buying nothing. These eight sit on seams that already
exist.

| Module | Type | Contains | May depend on |
|---|---|---|---|
| `:app` | Android app | Compose UI, navigation, DI graph, share-sheet activities, ViewModels | everything |
| `:core:designsystem` | Android lib | Tokens, theme, `ConfidenceBadge`, `ReviewRow`, `StageProgress` | — |
| `:core:database` | Android lib | Room entities, DAOs, migrations | `:domain` |
| `:core:common` | JVM lib | Dispatchers, `Outcome`, logging facade, hashing | — |
| `:domain` | **JVM lib** | Models, `Confident<T>`, `SourceReference`, ports, pipeline orchestration | `:core:common` |
| `:extraction` | **JVM lib** | Date/time/location/table/timetable engines | `:domain`, `:core:common` |
| `:document:pdf` | JVM + Android | `PdfTextSource` implementations, page iteration | `:domain`, `:core:common` |
| `:document:sandbox` | Android lib | Isolated-process parsing service, AIDL | `:document:pdf` |
| `:actions:calendar` | Android lib | Calendar Provider writes, duplicate detection, undo | `:domain` |

Later modules (`:document:image`, `:document:spreadsheet`, `:document:csv`, `:actions:reminder`,
`:core:billing`) attach at the same seams without restructuring.

### Dependency rules

Enforced by a Gradle task in `build-logic`, run in CI and locally:

```
checkModuleBoundaries:
  :domain      must not depend on  com.android.*, androidx.*, kotlinx-coroutines-android
  :extraction  must not depend on  com.android.*, androidx.*
  :document:*  must not depend on  androidx.compose.*
  :actions:*   must not depend on  androidx.compose.*, :extraction
```

A violation fails the build. "Parsers must not know about Compose" is a build failure here, not a
code-review convention — conventions erode, build failures don't.

### The pipeline crosses modules without coupling them

`:domain` declares ports; infrastructure modules implement them; `:app` wires them together.

```kotlin
// :domain — declares what it needs, knows nothing about how
interface DocumentReader {
    suspend fun read(source: DocumentSource, onProgress: (Progress) -> Unit): Outcome<DocumentContent>
}
interface ActionExecutor<in T : ActionCandidate> {
    suspend fun execute(candidates: List<T>, target: ActionTarget): Outcome<ExecutionReport>
}
interface AmbiguityResolver {
    suspend fun resolve(region: AmbiguousRegion): Outcome<StructuredHypothesis>
}
```

`:document:pdf` implements `DocumentReader`. `:actions:calendar` implements `ActionExecutor`.
`AmbiguityResolver` has exactly one V1 implementation — `NoOpResolver`, which always returns
`Outcome.Unavailable`. The pipeline is fully functional with it, which is the point: AI is a
plug-in, never a dependency.

---

## Decision records

### ADR-001 — PDF text extraction uses a three-tier strategy

**Status:** accepted · 2026-08-10

**Context.** The pipeline needs text *with positional bounds* from PDFs. Text order alone is
useless for table reconstruction ([08-extraction.md](08-extraction.md#table-reconstruction)).
Three options were evaluated against the installed SDK and current library releases.

**Evidence gathered 2026-08-10:**

- `androidx.pdf` is at `1.0.0-alpha19` (2026-07-01), requires minSdk 28, and exposes **no
  text-extraction API** — only viewing, search, and selection bounds. Rejected.
- The framework does have real text extraction: `PdfRenderer.Page.getTextContents()` returns
  `List<PdfPageTextContent>`, and `PdfPageTextContent` exposes `getText()` and
  `getBounds(): List<RectF>`. Verified in
  `~/Android/Sdk/sources/android-36/android/graphics/pdf/PdfRenderer.java:594` and
  `content/PdfPageTextContent.java:92,106`. **But** it is annotated
  `@FlaggedApi(FLAG_ENABLE_PDF_VIEWER)` — API 35+, or S+ only where the PDF SDK extension ≥ 13
  has been delivered. It cannot be the base path at minSdk 26.
- `PdfBox-Android` (`com.tom-roush:pdfbox-android`) is Apache-2.0 and works on all supported API
  levels, but its most recent release is `2.0.27.0`, published **2023-01-02** — 3.5 years stale
  as of writing.

**Decision.** Three tiers behind one `PdfTextSource` port:

| Tier | Availability | Mechanism | Output |
|---|---|---|---|
| 1 | API 35+, or SDK-ext ≥ 13 | `PdfRenderer.Page.getTextContents()` | text + `RectF` bounds |
| 2 | all supported devices | `PdfBox-Android`, `PDFTextStripper` subclassed to capture `TextPosition` | text + per-run x/y/w/h |
| 3 | no text layer | `PdfRenderer` → bitmap @ 300 dpi → ML Kit OCR | text + boxes + confidence |

Tier 2 is the workhorse. `PDFTextStripper.getText()` alone is insufficient — we override
`writeString(String, List<TextPosition>)` to capture glyph geometry, which is what feeds column
clustering.

**Consequences.** We carry a stale third-party parser. Mitigated by ADR-002. Tier 1 becomes the
default path over time as the API 35+ install base grows, and is measurably faster; tier 2 remains
the compatibility floor.

---

### ADR-002 — Untrusted document parsing runs in an isolated process

**Status:** accepted, **not implemented** · 2026-08-10, status corrected 2026-08-18

> ⚠️ **`:document:sandbox` contains no source.** The module exists with a build file and
> nothing else; every parser — PdfBox, the XLSX reader, ML Kit — runs in the UI process
> today. Several documents stated the opposite as fact, and this note exists because a
> security claim that is only aspirational is worse than an absent one: it stops people
> looking. The decision below still stands as the intended design; nothing about it has been
> built. Until it is, a parser bug **is** an app compromise and a parser hang **is** an ANR.

**Context.** Every input is attacker-controlled: PDFs arrive by WhatsApp, spreadsheets by email.
ADR-001 commits us to a PDF parser that has not shipped a security fix since January 2023. Later,
XLSX parsing adds ZIP and XML attack surface. Parsing this in the UI process means a parser bug
is an app compromise, and a parser hang is an ANR.

**Decision.** `:document:sandbox` hosts a service declared:

```xml
<service android:name=".SandboxParsingService"
         android:isolatedProcess="true"
         android:exported="false"
         android:process=":sandbox" />
```

`isolatedProcess` gives the parser its own UID with no permissions and no filesystem access. Bytes
go in over the binder; structured text runs with bounds come back. Nothing else crosses.

**Consequences.**
- A parser crash kills a throwaway process, not the app. Recovery is a clean "this file appears
  damaged" ([02-requirements.md § FR-8](02-requirements.md#fr-8-failure)).
- Timeouts become enforceable by process kill rather than cooperative cancellation — a parser
  stuck in a native loop is still killable.
- Memory is capped by the child process, so a decompression bomb OOMs the sandbox, not the UI.
- Cost: an AIDL boundary and a size limit on the binder transaction, so payloads are chunked.

This is the one architectural item that may land after the first working slice; the module seam
exists from the start either way so the retrofit is a wiring change, not a rewrite.

---

### ADR-003 — Confidence is a type, not a number

**Status:** accepted · 2026-08-10

**Context.** The requirement "never fabricate missing data" is usually implemented as a
convention: a nullable field plus a `confidence: Float`, plus developer discipline to check both.
Discipline fails. One `?: LocalTime.of(9, 0)` written on a deadline permanently breaks the
product's central promise, and it would pass code review because it looks like a sensible default.

**Decision.**

```kotlin
sealed interface Confident<out T> {
    data class High<T>(val value: T, val source: SourceReference) : Confident<T>
    data class Medium<T>(val value: T, val source: SourceReference, val reason: String) : Confident<T>
    data class Low<T>(val value: T, val source: SourceReference, val reason: String) : Confident<T>
    data class Missing(val reason: String) : Confident<Nothing>
}
```

`Missing` carries no value — there is no field to accidentally default. Extracting a value
requires an exhaustive `when`, so every consumer must decide what to do about absence. The
`ActionCandidate` constructor is private and reachable only through a builder that rejects
`Missing` in required positions.

**Consequences.** Hallucination prevention becomes a compile-time property. More verbose call
sites — accepted, and it is exactly the verbosity we want at the boundary where a wrong value
becomes a wrong calendar entry. See [09-confidence.md](09-confidence.md).

---

### ADR-004 — Ambiguity is preserved, not resolved

**Status:** accepted · 2026-08-10

**Context.** `03/04/2026` is 3 April or 4 March. Locale gives a plausible default. Using it is the
highest-severity failure mode available to this product: it produces a confident, well-formed,
*wrong* calendar entry, and the user has no way to notice.

**Decision.** The date engine returns `DateInterpretation(candidates: List<LocalDate>,
resolvedBy: Evidence?)`. Multiple candidates with `resolvedBy == null` cannot become an action.
Resolution comes only from recorded evidence:

1. Another date in the same document with a day component > 12
2. An explicit format declaration in the document
3. Weekday agreement — the document says "Monday 03/04/2026" and only one reading is a Monday
4. The user, asked once and applied document-wide

Device locale is **not** evidence. It is a hint used only to order the options presented.

**Consequences.** One extra question on some documents. Correctness on all of them.

---

### ADR-005 — Recurrence via RRULE, never expanded instances

**Status:** accepted · 2026-08-10

**Context.** A weekly class over a 15-week term is one thing that happens repeatedly, not 15
things. Writing 15 rows produces a calendar the user cannot edit (change the room → edit 15
events) and cannot cleanly delete. "It added 60 events and I had to delete them one by one" is a
recurring complaint across the entire import category ([01-product.md § Research](01-product.md#research-findings-that-shaped-the-design)).

**Decision.** One `Events` row with `RRULE=FREQ=WEEKLY;BYDAY=MO;UNTIL=<term end>`. A recurrence
with no derivable end is never written as unbounded — the user is asked, and may decline
recurrence entirely in favour of dated events.

**Consequences.** Requires a term end date, hence a disambiguation step. Worth it. Alternate-week
and holiday-exception handling is deferred; when it lands it uses `EXDATE`, which this model
already supports.

---

### ADR-006 — Provenance is written into the calendar, not only into our database

**Status:** accepted · 2026-08-10

**Context.** Undo and duplicate detection both need to answer "did *we* create this event?". The
obvious approach stores created event IDs in Room. That breaks if the app's data is cleared, the
app is reinstalled, or the user restores a backup — and then undo either does nothing or, worse,
falls back to deleting by time range and destroys the user's own events.

**Evidence.** `CalendarContract.java:1692–1723` lists the Events columns writable by an ordinary
app (not only by a sync adapter). That list includes `CUSTOM_APP_PACKAGE`, `CUSTOM_APP_URI`, and
`UID_2445`. The `SYNC_DATA*` and `_SYNC_ID` columns are sync-adapter-only, and
`ExtendedProperties` is likewise unavailable to us — but the three above are enough.

**Decision.** Every created event carries:

```
CUSTOM_APP_PACKAGE = "com.okayanshul.docaction"
CUSTOM_APP_URI     = "docaction://import/{importId}/{entryId}"
UID_2445           = <stable uuid>
```

Room mirrors the created `_ID`s as a fast path. The calendar tags are the source of truth.

**Consequences.**
- Undo deletes exactly `WHERE customAppUri = ?`. Never a time-range delete. This is the single
  most important safety property in [11-actions.md](11-actions.md).
- Duplicate detection survives a data wipe.
- Provenance is visible to other calendar apps, which is honest rather than a leak — it identifies
  the app, not the user.

---

### ADR-007 — minSdk 26

**Status:** accepted · 2026-08-10

**Context.** The template shipped minSdk 24. The date/time engine is the heart of the product and
is written against `java.time`, which is API 26+ natively and requires core library desugaring
below that.

**Decision.** minSdk 26. Coverage is ~98% of active devices in 2026. Core library desugaring stays
enabled for other APIs.

**Consequences.** ~2% reach given up. Framework PDF text (ADR-001 tier 1) still isn't reachable at
this floor — that decision is independent and unaffected.

---

### ADR-008 — AI is a port with no V1 implementation

**Status:** accepted · 2026-08-10

**Context.** Cloud LLMs would extract better from genuinely weird documents. They also add
per-document cost, a network dependency, a privacy exposure on documents that reveal a person's
physical location for four months, and a class of failure — confident fabrication — that this
product is specifically built to eliminate.

**Decision.** `AmbiguityResolver` exists as a domain port. V1 ships `NoOpResolver`. If a cloud
implementation is ever added it operates under fixed constraints:

```
AI → schema validation → source verification → confidence evaluation → user confirmation → action
```

- Only ambiguous *regions* are ever sent, never whole documents.
- Output is parsed into the same `Confident<T>` types as everything else; it can never be `High`.
- Any value not corroborated by extracted source text is discarded.
- Off by default, opt-in per document, with an explicit statement before transmission.
- **No AI output can reach an executor without passing through user confirmation.** Structural,
  via the same builder that enforces ADR-003.

**Consequences.** V1 is fully offline and free to run. Some documents fail that a cloud model
would handle — the honest failure path plus rescue mode ([02-requirements.md § UC-4](02-requirements.md#uc-4-rescue-mode-later))
covers that better than a confident wrong answer would.

---

### ADR-009 — Custom streaming OOXML reader instead of Apache POI

**Status:** accepted · 2026-08-10 · *applies when XLSX lands*

**Context.** Apache POI is the default answer for XLSX and a poor one on Android: it assumes a
StAX implementation the platform doesn't provide (requiring an aalto shim), it carries a large
method count and dex footprint, and `XSSFWorkbook` loads an entire workbook into memory —
directly contradicting the memory-safety requirement.

**Decision.** Write a focused streaming reader over `java.util.zip` + **SAX**. It reads
`workbook.xml`, `sharedStrings.xml`, and each `worksheets/sheetN.xml` as a stream, emitting cells
as events.

SAX rather than the `XmlPullParser` originally named here: SAX ships on **both** the JVM and
Android, which keeps `:document:spreadsheet` a pure Kotlin module. That turned out to matter more
than expected — it means the reader is unit-testable against real workbooks with no emulator, and
the first real institutional export was debugged entirely in JVM tests.

Scope is deliberately narrow — cell values, positions, merges, and enough number-format handling
to recognise dates. No formula evaluation, no charts, no writing.

**Consequences.** ~600 lines to own and test, against a multi-megabyte dependency we'd have to
fight. Full control over memory ceilings, ZIP-bomb limits, and entity-expansion hardening, all of
which POI would make us configure indirectly if at all.

---

### ADR-011 — Spreadsheets build the grid directly; only PDFs infer it

**Status:** accepted · 2026-08-10

**Context.** The first implementation ran spreadsheets through the same path as PDFs: synthesise
positioned text from row/column indices, then let `TableBuilder` recover the columns
geometrically. It reuses code, and it is wrong.

Tested against a real 335-section institutional export, it **silently lost three period columns**.
Columns `P1`, `P7` and `P10` are populated only in the header row for most sections, and the
gutter heuristic — "a band crossed by at most a quarter of lines is empty space" — classified them
as gaps. Their headings were absorbed into neighbouring columns, which then held two period times
each, became ambiguous, and were skipped. 23 classes became 18, with no error anywhere.

**Decision.** `SheetGridAdapter.toGrid` constructs the extraction `Grid` straight from sheet
coordinates. Column C is column C, empty or not. `TableBuilder` is used only where boundaries
genuinely have to be inferred, which is PDFs and images.

**Consequences.** The spreadsheet path is exact, faster, and has no tolerances to tune. Everything
downstream — timetable reconstruction, confidence, the candidate builder — is still shared
verbatim, which was the real reason for the common `Grid` type.

The same investigation improved the PDF path: gutter detection now separates *spanning* runs from
ordinary cell text, so a wide merged header may sit over a gutter but a normal-width run never
can. A column with content in only one line is a column.

---

## Architecture review

The spec's §100 questions, answered honestly.

| Question | Answer |
|---|---|
| Can the extraction engine be tested without Android UI? | Yes. `:domain` and `:extraction` are JVM modules; `checkModuleBoundaries` fails the build if that changes. |
| Can another document format be added without rewriting the app? | Yes. Implement `DocumentReader`, register it in the format-detection table. The pipeline downstream is format-agnostic — it consumes positioned text runs. |
| Can OCR be replaced? | Yes. ML Kit sits behind an `OcrEngine` port returning our own types, not ML Kit types. |
| Can AI be removed? | It isn't there. `NoOpResolver` is the shipping implementation (ADR-008). |
| Can Calendar be replaced by another action? | Yes. `ActionExecutor<T>` is generic; reminders attach at the same seam. |
| Can processing be cancelled safely? | Partly. Cooperative cancellation via structured concurrency works; the process kill for uncooperative native code depends on ADR-002, which is not implemented. Partial state is never persisted as complete. |
| Can huge documents be processed without OOM? | Partly. Page-at-a-time, bounded render dimensions and streaming spreadsheet reads all hold; the memory ceiling depends on the sandbox process, which is not implemented, so a decompression bomb OOMs the app rather than a child process. |
| Can every generated event be traced to its source? | Yes. `SourceReference` is a required constructor parameter on every `Confident` value that has one — an untraceable value is unrepresentable. |
| Can every user correction be represented? | Yes. `SourceReference.UserProvided` is a distinct case, so corrections are never confused with extractions and are never overwritten by re-derivation. |
| Can we guarantee AI cannot directly execute actions? | Yes. Executors accept only `ActionCandidate`, whose builder requires confirmed non-`Missing` fields; the AI path cannot produce `High` confidence and cannot bypass the builder. |

---

## Technology decisions

| Concern | Choice | Note |
|---|---|---|
| Language | Kotlin 2.2.10 | Already configured |
| UI | Compose, Material 3 | compose-bom `2026.06.01` (template pinned 2026.02.01) |
| Async | Coroutines + Flow | Structured concurrency is the cancellation model |
| DI | Hilt `2.60.1` | `hilt-navigation-compose 1.3.0` — see the compileSdk note below |
| Persistence | Room `2.8.4` | Metadata only — never document content |
| Preferences | DataStore Preferences | Settings, resolved conventions |
| Deferred work | WorkManager | Only for genuinely deferred user-initiated tasks; no polling |
| Dates | `java.time` | Native at minSdk 26 (ADR-007) |
| PDF | `PdfBox-Android 2.0.27.0` + framework tier 1 | ADR-001 |
| OCR | ML Kit Text Recognition v2 | Exposes boxes, corner points, rotation, and per-element confidence |
| Billing | `billing-ktx 9.1.0` | Play requires v8+ for updates from 2026-08-31 |
| Build | AGP 9.2.1 / Gradle 9.4.1 / JDK 21 toolchain | Already configured |
| compileSdk | 36.1 | Android 16 QPR1 — the minor level is required by androidx.core and lifecycle |
| targetSdk | 36 | Play requirement for new apps from 2026-08-31 |

### ADR-010 — AndroidX pinned to the compileSdk 36.1 line

**Status:** accepted · 2026-08-10

`androidx.core:core-ktx 1.19.0`, `lifecycle 2.11.0`, and `hilt-navigation-compose 1.4.0` all
declare an AAR-metadata requirement of **compileSdk 37**. Adopting them means compiling against
an Android 17 platform that this project does not need — `targetSdk 36` is what Play requires,
and none of the newer artifacts carry a feature this product uses.

Pinned instead: `core-ktx 1.18.0`, `lifecycle 2.10.0`, `activity-compose 1.12.4`,
`hilt-navigation-compose 1.3.0`. Revisit when there is a reason to move to API 37 beyond
"it is newer" — a compileSdk bump is a real change, not a version-catalog refresh.

### Build notes

AGP 9 ships built-in Kotlin support, which rejects plugins that register Kotlin source sets
directly — KSP (Room, Hilt) still does. `android.disallowKotlinSourceSets=false` in
`gradle.properties` is the documented suppression; remove it once KSP registers its output
through `android.sourceSets`.
