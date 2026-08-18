# The document pipeline

One directed sequence, thirteen stages, each independently testable. A stage takes a typed input
and produces a typed `Outcome`; no stage reaches backwards, and no stage knows about the UI.

```
INPUT ▸ VALIDATE ▸ DETECT ▸ METADATA ▸ EXTRACT ▸ STRUCTURE ▸ ENTITIES
      ▸ NORMALISE ▸ SCORE ▸ VALIDATE ▸ INFER ▸ REVIEW ▸ EXECUTE ▸ VERIFY
```

Orchestration lives in `:domain` as a plain suspend function over ports. It runs on the JVM in
tests with fake readers, and on Android with real ones.

```kotlin
suspend fun DocumentPipeline.run(
    source: DocumentSource,
    hints: ExtractionHints,           // user-supplied "this is a timetable", crop region
    onProgress: (StageProgress) -> Unit,
): Outcome<ReviewSet>
```

Everything up to REVIEW is pure computation with no side effects. Nothing outside the app changes
until EXECUTE, and EXECUTE is only reachable through explicit user confirmation.

---

## Stage 1 — Input

Receives a `DocumentSource` from SAF, the share sheet, the photo picker, or camera.

- Share-sheet URIs carry grants that can be revoked at any moment; the URI is not a stable handle.
- Take a persistable permission where the grant allows it, and re-check readability immediately
  before use rather than assuming the grant from selection time still holds.
- `displayName` and `sizeBytes` come from `DocumentsContract` metadata; both are advisory.

**Fails with:** `PermissionRevoked`, `Empty`.

## Stage 2 — Validate

Cheap gates before anything expensive.

| Check | Limit | Failure |
|---|---|---|
| Readable | opens | `PermissionRevoked` |
| Non-empty | > 0 bytes | `Empty` |
| Size ceiling | 100 MB | `TooLarge` |
| Free storage | enough for one rendered page | `TooLarge` |

The size ceiling is a product decision, not a technical one: above it, the honest answer is "this
is too large to process safely — select specific pages" rather than a five-minute progress bar.

## Stage 3 — Format detection

**Never trusts the extension, and never trusts the OS-declared MIME type.** A file named
`schedule.pdf` shared from a chat app may be an XLSX, an image, or corrupt.

Order of evidence:

1. **File signature.** `%PDF-` at offset 0; `PK\x03\x04` for OOXML/ZIP; JPEG/PNG/WebP/HEIF magic
   bytes. Read the first 512 bytes only.
2. **Container inspection** for ZIP-family files — the presence of `xl/workbook.xml` distinguishes
   XLSX from any other ZIP. A ZIP that is not an XLSX is not "an XLSX we failed to read"; it is
   unsupported, and saying so is more useful.
3. **Parser probe.** The definitive test is whether a parser can open it. A file with a valid
   `%PDF-` header and a shredded xref table is `Corrupt`, not `Pdf`.
4. Extension and declared MIME are used **only** to order which probe is tried first.

Encryption is detected here, not later: an encrypted PDF opens far enough to report `Encrypted`,
and we stop. We do not attempt password entry, dictionary attacks, or "try empty password"
bypasses.

**Fails with:** `UnsupportedFormat`, `Corrupt`, `Encrypted`.

## Stage 4 — Metadata

Page count, page dimensions, sheet names, image dimensions and EXIF orientation. Cheap, and it is
what makes progress reporting honest — `Page 12 of 84` requires knowing 84 before starting.

Where a count genuinely cannot be known up front, the stage records that and the UI uses an
indeterminate indicator ([03-ux.md § Processing](03-ux.md#processing)).

## Stage 5 — Content extraction

Format-specific, behind `DocumentReader`. Produces `List<PageContent>` of positioned `TextRun`s.

**Invariants for every implementation:**

- One page/sheet at a time. Never materialise the whole document.
- Cooperatively cancellable at page boundaries; a PDF parser stuck in native code is killable with its process (ADR-002). The spreadsheet readers are still cooperative-only.
- A per-page timeout, and a whole-document timeout.
- Bounded allocation — render dimensions clamped regardless of what the document declares.
- Runs in the isolated process ([ADR-002](05-architecture.md#adr-002--untrusted-document-parsing-runs-in-an-isolated-process)).

Per-page fallback is a real requirement, not a nicety: a scanned appendix inside a text PDF must
OCR just those pages, and the result is `Partial` with a warning, not a whole-document failure.

Details per format in [10-formats.md](10-formats.md).

**Fails with:** `Corrupt`, `NoTextLayer`, `Timeout`, `Cancelled`.

## Stage 6 — Structure detection

The first stage that *understands* anything. Input is positioned text runs; output is a grid.

1. **Line clustering** — group runs whose vertical centres agree within a tolerance derived from
   the median run height. Fixed pixel tolerances break on a 300-dpi scan.
2. **Column clustering** — project run x-positions and find gaps. Consistent gaps across many
   lines indicate real columns; a single wide line does not.
3. **Grid inference** — decide whether the page holds a table, and whether it is row-oriented
   (`Monday | 9:00 | DSA | K10`) or column-oriented (weekdays across the top, times down the side).
4. **Header identification** — a header row/column is identified by *content*, not position:
   weekday names, time patterns, or a distinct formatting signature. Assuming row 1 is the header
   is wrong often enough to be worth never doing.
5. **Region segmentation** — multiple tables on one page are separated by whitespace bands and
   heading text.

Full algorithm in [08-extraction.md § Table reconstruction](08-extraction.md#table-reconstruction).

Output can legitimately be "no table here", which is not a failure — it routes to entity
extraction over free text, which handles exam schedules and deadline notices.

## Stage 7 — Entity extraction

Pull `DateInterpretation`, `TimeInterpretation`, subject, location, and instructor from grid cells
or free text. Every entity carries its `SourceReference` from the moment it is created — the
reference is a constructor parameter, so an untraceable entity cannot be built.

## Stage 8 — Normalisation

OCR repair, whitespace, case, abbreviations. Every transformation records `raw`, `value`, and
`rule` ([06-data-model.md § Normalisation](06-data-model.md#normalisation)).

**Normalisation must never change meaning.** `1O:OO → 10:00` is repair. `10 → 10:00` is invention
and belongs in a different category entirely — it is not performed here, and if a time is
incomplete it stays `Missing`.

## Stage 9 — Confidence scoring

Per field, never per document. Inputs: extraction method, OCR confidence, whether normalisation
was applied, structural corroboration (does this cell agree with its column?), and internal
consistency.

Rules in [09-confidence.md](09-confidence.md#confidence). Two that matter here:

- A field derived through `OcrCharacterSubstitution` cannot be `High`.
- A time whose AM/PM was inferred cannot be `High`.

## Stage 10 — Validation

Reject the impossible, flag the suspicious.

| Class | Examples | Result |
|---|---|---|
| Impossible | `32 September`, `25:90`, `Feb 30` | `Invalid` — flagged, never coerced |
| Contradictory | end before start, negative duration | `Invalid` |
| Suspicious | class at 03:00, 9-hour class, date 5 years out | `NeedsAttention` |
| Structural | a weekday column with one entry among 40 | `NeedsAttention` |

The distinction between reject and flag matters: rejecting a suspicious-but-possible value would
silently drop a legitimate 7 AM lab. Flagging an impossible one and asking would be pretending we
might use it. Neither error is acceptable in the other's place.

## Stage 11 — Action inference

Group entries into schedules; infer weekly recurrence where a subject+time+weekday repeats;
attempt `CalendarEventCandidate.from()`.

Anything with a `Missing` required field or unresolved ambiguity **does not become a candidate**.
It becomes an *unresolved item* carried into review with a specific question attached. That is the
mechanism behind "2 items need your attention" — those two are not weak candidates, they are
questions.

Recurrence inference and its bounds: [08-extraction.md § Recurrence](08-extraction.md#recurrence-inference).

## Stage 12 — User review

The pipeline stops here and hands a `ReviewSet` to the UI. Everything so far is reversible because
nothing has happened.

Corrections re-enter at the stage they invalidate, not at the start:

| Correction | Re-enters at |
|---|---|
| Edit one field | Stage 10 (validate) for that entry |
| Resolve date-order ambiguity | Stage 8 (normalise) for all dates |
| Choose a different section | Stage 7 (entities) for that region |
| Change term end | Stage 11 (inference) for recurrence |

This is what makes [principle 8](01-product.md#principles) real — a correction never costs the
user their other work.

## Stage 13 — Execution

Only reachable from an explicit confirmation. Chunked, transactional per chunk, with a report.
Details in [11-actions.md](11-actions.md).

## Stage 14 — Verification

Read back what was written. Confirm the row count and that recurrence survived. If the provider
accepted fewer rows than we sent, the user is told exactly that.

**A failed or partial write is never reported as success.** This is the last line of defence
against the product's worst outcome: a user who believes their exam schedule is in their calendar
when it is not.

---

## Progress reporting

Each stage emits `StageProgress(stage, index, total, determinate)`. The UI renders only stages
that will actually run — a text PDF never shows an OCR line ([03-ux.md § Processing](03-ux.md#processing)).

Progress is real. If the total is unknown, `determinate = false` and the UI shows an
indeterminate indicator rather than a fabricated percentage.

## Cancellation

Cooperative via structured concurrency; every stage checks for cancellation at its natural
boundary (page, sheet, row batch). Uncooperative native work was to be bounded by the sandbox
process timeout, which kills rather than asks — that process does not exist yet (ADR-002), so a
parser stuck in a native loop is currently not killable.

On cancel: partial results are discarded, temporary files are deleted, the import record is marked
`Discarded`, and the user is told plainly that nothing was changed.

## Testability

Every stage is a pure function of its input plus injected ports.

| Stage | Test approach |
|---|---|
| 1–4 | Fake `DocumentSource`, byte fixtures including hostile ones |
| 5 | Real parsers against the corpus; JVM, no emulator |
| 6 | Synthetic `TextRun` grids — layout logic tested without any real document |
| 7–11 | Pure functions; table-driven and property-based |
| 12 | Compose UI tests over a fixed `ReviewSet` |
| 13–14 | Instrumented against the real Calendar Provider ([14-testing.md](14-testing.md)) |

Stage 6 being testable from synthetic geometry is what makes table reconstruction tractable —
we can construct the pathological layout directly instead of hunting for a PDF that exhibits it.
