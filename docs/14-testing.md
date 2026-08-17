# Testing

## Strategy

The product's value is correctness. A wrong calendar event is worse than no calendar event,
because the user acts on it. Testing is therefore weighted toward the extraction engine, where
correctness lives, rather than toward UI coverage percentages.

### The shape

```
        ┌─────────────────┐
        │  Manual / real  │   real documents, real devices, exploratory
        ├─────────────────┤
        │  Instrumented   │   Calendar Provider, permissions, SAF, share sheet
        ├─────────────────┤
        │   UI (Compose)  │   review screen, error states, a11y
        ├─────────────────┤
        │   Integration   │   full pipeline over the corpus, JVM
        ├─────────────────┤
        │      Unit       │   ← the bulk. date, time, table, confidence, validation
        └─────────────────┘
```

The unit layer is large and fast because `:domain` and `:extraction` are pure JVM modules with no
Android on the classpath ([05-architecture.md](05-architecture.md#dependency-rules)). Hundreds of
tests run in seconds, which is what makes TDD viable for the parsing logic.

### The invariants

These are the properties the product exists to guarantee. Each has dedicated tests, and a failure
in any of them blocks release regardless of what else passes.

| # | Invariant |
|---|---|
| I-1 | An invalid date never becomes a valid event without explicit user confirmation |
| I-2 | Every action candidate has at least one source reference |
| I-3 | A `Missing` field never becomes a value |
| I-4 | Re-importing the same document never blindly creates duplicates |
| I-5 | Cancelling processing never leaves persisted state marked complete |
| I-6 | A failed calendar operation is never reported as success |
| I-7 | No action executes without user authorisation |
| I-8 | Undo removes only events created by that import |
| I-9 | An ambiguous date with no resolving evidence never becomes an event |
| I-10 | A recurring entry produces one event with an `RRULE`, not N events |

---

## Test matrix

### Unit — date engine

Table-driven over every supported form, plus:

| Case | Expected |
|---|---|
| `03/04/2026` alone | 2 candidates, `resolvedBy = null` |
| `03/04/2026` with `13/04/2026` present | 1 candidate, `resolvedBy = SiblingDate` |
| `Monday 03/04/2026` where only one reading is a Monday | 1 candidate, `resolvedBy = WeekdayAgreement` |
| `32/09/2026` | `Invalid`, raw retained, no coercion |
| `2026-02-30` | `Invalid` |
| `29/02/2026` (not a leap year) | `Invalid` |
| `29/02/2028` | Valid |
| `18/09` (no year) | Year from context, else asked; never silently current-year at `High` |
| `18/09/26` | Windowed to 2026 |
| `Sept 18` / `18th September` | Parsed |
| Device locale en-US, document is DD/MM | Locale does **not** resolve it |

That last row is a regression test for the highest-severity failure mode in the product
([ADR-004](05-architecture.md#adr-004--ambiguity-is-preserved-not-resolved)).

### Unit — time engine

| Case | Expected |
|---|---|
| `14:30` | 14:30, no inference |
| `10` bare, no column context | `meridiemInferred` impossible → `Low` |
| Column `8,9,10,11,12,1,2,3` | Single wrap detected → `1` = 13:00, `Medium` |
| Column containing `14:00` | 24-hour proven for the column |
| `12:00` | **Noon**, not midnight |
| `00:00` | Midnight |
| `23:00–01:00` | Crosses midnight, positive duration |
| `11:00–10:00` | `Invalid` |
| `10:00–10:00` | `Invalid` (zero duration) |
| `9-10` in a time column | Range |
| `9-10` in a room column | Not a time |
| Start with no end, next entry at 10:00 | `Derived`, `Medium` |
| Start with no end, no structure | `Missing` — **never** start + 60 min |

### Unit — table reconstruction

Built from **synthetic `TextRun` geometry**, not from real files. This is what makes pathological
layouts testable — we can construct the exact case instead of hunting for a document that has it.

| Case | Expected |
|---|---|
| Clean 5×8 grid | Correct cells |
| Column-oriented (weekdays on top) | Orientation detected |
| Row-oriented (weekdays in column 1) | Orientation detected |
| Row 1 is a college name, row 2 is the header | Header found at row 2 |
| Merged cell spanning 2 columns | Detected, `Medium` |
| Two tables separated by a whitespace band | Two regions |
| Runs emitted in non-visual order | Correct reconstruction (geometry, not order) |
| Rotated page, corrected coordinates | Correct reconstruction |
| Inconsistent column counts | "Layout not readable" → rescue, **not** a forced grid |
| 300-dpi vs 72-dpi versions of the same layout | Identical output (tolerances relative to median height) |

### Unit — confidence and validation

| Case | Expected |
|---|---|
| PDF text layer value | `High` |
| OCR at 0.9 | `High` |
| OCR at 0.7 | `Medium` |
| OCR at 0.4 | `Low` |
| OCR at 0.95 **with** character substitution | `Medium` — cap applies |
| Value contradicting its column pattern | Demoted one level |
| User-corrected value | `High`, `UserProvided` |
| User-corrected, then date format changed | Correction **survives** |

### Property-based

| Property |
|---|
| For any generated `ScheduleEntry` with a `Missing` required field, `CalendarEventCandidate.from()` returns `Failure` |
| For any candidate, `sources` is non-empty |
| For any parsed date, `candidates.all { it.isValidCalendarDate() }` |
| For any time range, `end > start` or the range is `Invalid` |
| For any run list, permuting the input order produces identical table output |
| For any document, running the pipeline twice yields identical candidates |
| For any cancellation point, no import record ends in `Committed` |

The permutation property is the strongest single test of the table engine — it directly encodes
"text order is not reading order".

### Integration — pipeline

Full pipeline over the corpus on the JVM with fake ports. Each corpus document has a checked-in
expectation file; a diff is a test failure. This is the regression net that makes engine changes
safe.

### UI tests

| Area | Assertions |
|---|---|
| Review list | 42 rows render; filter isolates the 2 unresolved; selection count updates |
| Editing | Time picker applies; row moves from ⚠ to ✓; counter decrements |
| Confirm | Calendar must be chosen; count matches selection |
| Error states | Each `FailureReason` renders its screen with at least one action |
| Empty state | Renders on first run |
| Accessibility | Every interactive element has a content description; confidence states announce in words; 48dp targets; 200% font scale doesn't truncate |
| Greyscale | Review list rendered without colour — all four confidence states remain distinguishable |

### Instrumented — the parts that must not be mocked

Mocked calendar tests prove nothing about the Calendar Provider, which is where the consequential
bugs are.

| Test | Assertion |
|---|---|
| Write 42 events | 42 rows exist with our `CUSTOM_APP_PACKAGE` |
| Recurring write | Exactly 1 row with `RRULE`, and `Instances` expands to the expected count |
| Recurring write uses `DURATION` not `DTEND` | Provider accepts it |
| Timezone | `EVENT_TIMEZONE` set; instances correct across a DST boundary |
| Batch > 150 | Chunked; no `TransactionTooLargeException` |
| Undo | Our rows gone; **a pre-created control event still exists** |
| Undo after app data wipe | Still works (provenance is in the calendar) |
| Duplicate detection | Second import of the same file is detected before writing |
| Permission denied | No write attempted; single-event path offered |
| Permission revoked mid-flow | Detected; honest failure |
| Destination calendar deleted mid-flow | Write aborted; user re-selects |
| Partial write failure | Report matches reality; retry writes only the remainder |
| SAF | `ACTION_OPEN_DOCUMENT` returns a usable URI |
| Share sheet | `ACTION_SEND` with a PDF enters the flow |
| Revoked URI | Clean failure |
| Process death mid-extraction | Resume/discard offered |

The control-event assertion in the undo test is the single most important instrumented test in the
suite. It is what proves undo cannot destroy the user's own data.

---

## Regression corpus

Real documents, checked into `:extraction/src/test/resources/corpus/`, each with an expectation
file.

### Composition

**PDF**
- Clean single-page timetable, text layer
- Multi-page exam schedule
- Column-oriented grid timetable
- Row-oriented list timetable
- Landscape timetable
- Scanned timetable (no text layer)
- Mixed: text pages plus a scanned appendix
- Multi-column academic notice
- 100+ page document
- Truncated / corrupt
- Password protected
- Zero-byte
- PDF with a text layer containing only a watermark

**Images** *(with the image path)*
- Screenshot of a timetable
- Camera photo, straight
- Camera photo, angled and shadowed
- Rotated (EXIF)
- Low resolution
- Cropped region
- Poster with mixed content
- Handwritten (expected: honest failure)
- Very high resolution (memory test)

**Spreadsheets** *(with the XLSX path)*
- Simple single table
- Multiple sections stacked on one sheet
- Multiple semesters across sheets
- Merged cells throughout
- Hidden rows and a hidden sheet
- Formula-derived cells
- Formatting-heavy
- 100k+ rows
- ZIP bomb (expected: rejected)
- Corrupt ZIP

**CSV** *(with the CSV path)*
- Comma, semicolon, tab delimited
- Quoted fields with embedded newlines
- UTF-8 with BOM, and Windows-1252
- Ragged rows

### Sourcing and privacy

Real institutional documents, **anonymised before check-in** — names replaced, roll numbers
removed, institution identifiers genericised. The layout pathologies are the point, and they
survive anonymisation. Documents whose structure cannot be preserved through anonymisation are
excluded rather than checked in.

### Expectations

Each document has `<name>.expected.json` holding candidate count, per-field values and confidence
levels, and the unresolved-item count. A diff fails the test. Changing an expectation requires
stating why in the commit, which keeps the corpus from silently drifting to match bugs.

### The 20–50 document gate

Before the vertical slice is considered done, it runs against at least **10 real timetable PDFs**;
before V1 ships, at least **50 documents across formats**. Not synthetic — documents that real
institutions actually sent to real students. Everything else in this plan is a hypothesis until
that happens.

---

## Device coverage

| Tier | Reference | Why |
|---|---|---|
| **Low** | 3 GB RAM, entry-level SoC, Android 9 | The honest floor. If OCR is unusable here, we have a problem the target market will feel. |
| **Mid** | 6 GB RAM, Android 13 | **The benchmark reference.** All performance budgets are stated against this. |
| **High** | 12 GB RAM, Android 16 | Tier-1 PDF path; upper bound |
| **Tablet** | Large screen, Android 14+ | Adaptive layout, drag and drop |
| **Emulator** | `emulator-5554`, API 36, GMS | Development and CI |

Also verified: minSdk 26 boundary behaviour, API 35 (tier-1 PDF becomes available), and API 36
(current target). Foldables get a basic configuration-change pass; no dedicated layout in V1.

### CI

| Stage | Runs |
|---|---|
| Pre-merge | `:domain:test :extraction:test :document:pdf:test`, `checkModuleBoundaries`, lint, dependency verification |
| Nightly | Full corpus regression; instrumented suite on emulator API 26/31/36 |
| Pre-release | Manual device pass on low/mid tiers; accessibility scan; greyscale review; data-safety declaration re-verified against code |

---

## Security testing

Each input from [12-privacy-security.md § Input limits](12-privacy-security.md#input-limits) has a
hostile fixture and an expected clean rejection.

| Attack | Expected |
|---|---|
| Malformed PDF (fuzzed xref) | Clean `Corrupt`; sandbox contains any crash |
| PDF with a decompression bomb stream | Rejected on limits |
| 30000×30000 PNG | Rejected at bounds-decode; no allocation |
| ZIP bomb as XLSX | Rejected on compression ratio |
| XLSX with billion-laughs XML | Rejected; no entity expansion |
| ZIP entry named `../../databases/app.db` | Rejected; never used as a path |
| Corrupt ZIP central directory | Clean `Corrupt` |
| Revoked content URI | Clean `PermissionRevoked` |
| URI pointing at our own app-private files | Rejected |
| Symlinked / unusual `content://` authority | Handled through `ContentResolver` only |

Fuzzing: a corpus-driven fuzz run over the PDF and XLSX readers in the sandbox process, asserting
that **no input causes a crash in the host process** — a sandbox crash is an acceptable outcome
and proves the boundary works.
