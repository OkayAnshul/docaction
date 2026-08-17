# Requirements

Requirement IDs are stable and referenced from tests. `MUST` / `SHOULD` / `MAY` carry RFC 2119
weight. Anything marked **V1** is in the shipping scope; **later** is designed-for but not built.

---

## Functional requirements

### FR-1 Input

| ID | Requirement | Scope |
|---|---|---|
| FR-1.1 | The app MUST accept documents via the Storage Access Framework (`ACTION_OPEN_DOCUMENT`). | V1 |
| FR-1.2 | The app MUST accept documents shared to it via `ACTION_SEND` and `ACTION_SEND_MULTIPLE` from other apps (Files, Gmail, Drive, WhatsApp, Chrome, gallery). | V1 |
| FR-1.3 | The app MUST accept images from the Android Photo Picker without requiring storage permission. | V1 (picker), later (OCR path) |
| FR-1.4 | The app MUST accept camera capture. | later |
| FR-1.5 | The app MUST accept pasted plain text. | later |
| FR-1.6 | The app SHOULD accept drag-and-drop on large-screen devices. | later |
| FR-1.7 | The app MUST determine format from file content (signature) and parser compatibility, never from extension alone. | V1 |
| FR-1.8 | The app MUST handle a URI whose permission has been revoked between selection and processing, with a clear message. | V1 |

### FR-2 Extraction

| ID | Requirement | Scope |
|---|---|---|
| FR-2.1 | The app MUST extract a text layer from text-based PDFs without invoking OCR. | V1 |
| FR-2.2 | Extracted text MUST carry positional bounds (page + rectangle) for every run. | V1 |
| FR-2.3 | The app MUST render and OCR PDF pages that lack a usable text layer. | later |
| FR-2.4 | The app MUST process PDFs page-by-page, never loading the whole document into memory. | V1 |
| FR-2.5 | OCR output MUST preserve blocks, lines, elements, bounding boxes, and per-element confidence. | later |
| FR-2.6 | The app MUST reconstruct tabular structure from positional data, not from text order. | V1 |
| FR-2.7 | The app MUST support row-oriented and column-oriented (grid) timetable layouts. | V1 |
| FR-2.8 | The app MUST detect and offer a choice between multiple schedules in one document. | later (needed for XLSX) |
| FR-2.9 | The app MUST support XLSX with multiple sheets, merged cells, hidden rows/sheets, and non-header-first layouts. | later |
| FR-2.10 | The app MUST support CSV with delimiter and encoding detection. | later |

### FR-3 Dates and times

| ID | Requirement | Scope |
|---|---|---|
| FR-3.1 | The date engine MUST parse: `18/09/2026`, `18-09-2026`, `18.09.2026`, `Sep 18`, `September 18`, `18 September`, `18 Sept 2026`, `2026-09-18`, bare weekday names. | V1 |
| FR-3.2 | Where a numeric date is ambiguous between DD/MM and MM/DD, the engine MUST retain **all** candidate interpretations and MUST NOT select one silently. | V1 |
| FR-3.3 | The engine MAY resolve ambiguity using document-internal evidence (another date in the same document with a component > 12, an explicit format declaration, weekday agreement) and MUST record which evidence resolved it. | V1 |
| FR-3.4 | Where ambiguity is unresolved, the app MUST ask the user, once per document, and apply the answer document-wide. | V1 |
| FR-3.5 | The engine MUST reject impossible values (`32 September`, `25:90`, `13:75`, `Feb 30`) and MUST NOT coerce them into valid-looking values. | V1 |
| FR-3.6 | The time engine MUST parse `10:00`, `10 AM`, `10.30`, `14:30`, `10–11 AM`, `9:00–10:00`, `9-10`. | V1 |
| FR-3.7 | The engine MUST NOT infer AM/PM unless it is derivable from an explicit marker, 24-hour notation, or an unambiguous ordered sequence within the same table column. | V1 |
| FR-3.8 | A missing year MUST be resolved from document context or asked, never assumed to be the current year without recording that assumption. | V1 |
| FR-3.9 | All events MUST be written with an explicit IANA timezone. | V1 |

### FR-4 Confidence and validation

| ID | Requirement | Scope |
|---|---|---|
| FR-4.1 | Every extracted field MUST carry an independent confidence level. A single document-level score is not sufficient. | V1 |
| FR-4.2 | Confidence levels MUST be High, Medium, Low, or Missing. | V1 |
| FR-4.3 | A field that is Missing MUST NOT be populated by inference. | V1 |
| FR-4.4 | The system MUST distinguish extracted / inferred / user-provided / unknown provenance for every field. | V1 |
| FR-4.5 | An action candidate MUST NOT be constructible from a Missing required field. This MUST be enforced structurally, not by convention. | V1 |
| FR-4.6 | Every field MUST carry a source reference identifying its document location. | V1 |
| FR-4.7 | Normalisation (e.g. `1O:OO` → `10:00`) MUST retain the raw value, the normalised value, and the reason. | V1 |

### FR-5 Review

| ID | Requirement | Scope |
|---|---|---|
| FR-5.1 | The app MUST present all candidates for review before any calendar write. | V1 |
| FR-5.2 | Items requiring attention MUST be visually and semantically distinguishable, and filterable. | V1 |
| FR-5.3 | The user MUST be able to edit any field with native pickers. | V1 |
| FR-5.4 | The user MUST be able to view the source region for any candidate. | V1 |
| FR-5.5 | The user MUST be able to select, deselect, and bulk-act on candidates without opening each one. | V1 |
| FR-5.6 | The app MUST NOT require the user to individually confirm every candidate. | V1 |
| FR-5.7 | Low-confidence candidates MUST require explicit resolution before they can be included. | V1 |

### FR-6 Actions

| ID | Requirement | Scope |
|---|---|---|
| FR-6.1 | The app MUST write calendar events via the Calendar Provider after explicit confirmation. | V1 |
| FR-6.2 | The user MUST explicitly select the destination calendar. No automatic fallback. | V1 |
| FR-6.3 | Weekly-repeating entries MUST be written as one recurring event with an `RRULE`, not as repeated instances. | V1 |
| FR-6.4 | A recurrence MUST have an end condition. If none is derivable, the app MUST ask. | V1 |
| FR-6.5 | The app MUST detect likely duplicates before writing and offer skip / replace / add-anyway. | V1 |
| FR-6.6 | The app MUST tag every created event with its own provenance markers so it can identify them later. | V1 |
| FR-6.7 | The app MUST offer undo after an import, and undo MUST remove only events created by that import. | V1 |
| FR-6.8 | The app MUST NOT modify or delete pre-existing events without a distinct, explicit destructive confirmation. | V1 |
| FR-6.9 | The app MUST create reminders/notifications for deadline-type extractions. | later |
| FR-6.10 | Calendar permission MUST be requested at the point of use with an in-context explanation, not at launch. | V1 |

### FR-7 History

| ID | Requirement | Scope |
|---|---|---|
| FR-7.1 | The app MUST record each import: source name, timestamp, count, outcome. | V1 |
| FR-7.2 | The app MUST NOT persist document content or extracted personal text beyond what is needed for an in-progress or reviewable import. | V1 |
| FR-7.3 | Deleting a history entry MUST NOT delete calendar events without a separate explicit confirmation. | V1 |
| FR-7.4 | The app MUST offer to resume or discard an import interrupted by process death. | V1 |

### FR-8 Failure

| ID | Requirement | Scope |
|---|---|---|
| FR-8.1 | The app MUST NOT surface technical error text to the user. | V1 |
| FR-8.2 | Every failure MUST offer at least one concrete next action. | V1 |
| FR-8.3 | Password-protected documents MUST be identified as such. The app MUST NOT attempt to bypass protection. | V1 |
| FR-8.4 | A PDF with no text layer MUST be identified as such and offered the OCR/screenshot path. | V1 |
| FR-8.5 | Every long-running operation MUST be cancellable and MUST have a timeout. | V1 |
| FR-8.6 | Finding nothing actionable MUST be presented as a neutral outcome with next steps, not as an error. | V1 |

---

## Non-functional requirements

| ID | Requirement |
|---|---|
| NFR-1 | Core extraction MUST function with no network connectivity. |
| NFR-2 | No account, sign-in, or backend MUST be required for any V1 feature. |
| NFR-3 | Document content MUST NOT be transmitted off-device without explicit per-document consent. |
| NFR-4 | Document content MUST NOT appear in logs, analytics, or crash reports. |
| NFR-5 | The app MUST NOT crash or OOM on a malformed, hostile, or oversized input; it MUST fail cleanly. |
| NFR-6 | The app MUST meet WCAG 2.2 AA for contrast and be fully operable with TalkBack. |
| NFR-7 | Confidence state MUST NOT be conveyed by colour alone. |
| NFR-8 | The app MUST NOT perform background processing without a user-initiated deferred task. |
| NFR-9 | The extraction engine MUST be testable on the JVM with no Android dependency. |
| NFR-10 | The app MUST target the current Play-required API level (36 as of 2026-08-31). |

---

## Use cases

### UC-1 Semester timetable → recurring calendar (primary)

**Actor:** student. **Trigger:** receives a timetable file.

1. Shares the file to DocAction (or opens it in-app).
2. App detects format, extracts, reconstructs the weekly grid.
3. If multiple schedules exist, app asks which one. *(later)*
4. App asks for the term end date (recurrence bound) if not present in the document.
5. Review screen: entries grouped by weekday, confidence flagged.
6. User resolves flagged items, deselects anything unwanted.
7. Confirm: destination calendar, event count, date range, recurrence summary.
8. Write. Success with undo.

**Success:** one recurring event per distinct class, correct times, correct end date.
**Most likely failure:** wrong section selected. Mitigated by making section choice explicit and
by making re-selection regenerate without restarting (FR-8 of the principles).

### UC-2 Exam schedule → dated events

Same flow, no recurrence. Higher stakes on date correctness — every ambiguous date is confirmed,
none inferred. Small N (6–10), so per-item review is acceptable here where it isn't for UC-1.

### UC-3 Deadline → reminder *(later)*

Single date, no time. User chooses notification lead time (same day / 1 day / 1 week before).

### UC-4 Rescue mode *(later)*

Triggered when automatic extraction fails or produces low confidence.

1. App states plainly that it could not read the document reliably.
2. Offers: screenshot, choose image, crop region.
3. Asks what the user is looking for (timetable / exam schedule / deadlines / events / other) —
   this hint constrains the extraction and measurably improves accuracy.
4. Processes only the selected region.

This is the pressure-release valve for the entire product. Without it, every unsupported layout
is a dead end and a 1-star review. With it, a hard document becomes an easy one.

### UC-5 Re-import of an already-imported document

Detected by content hash and by the provenance tags on existing calendar events. User is told
before anything is written and chooses skip / replace / add anyway.

---

## User flows

### Primary flow

```
Share sheet / in-app picker
        │
        ▼
  URI validation ──── revoked/unreadable ──▶ "Couldn't open this file" + retry paths
        │
        ▼
  Format detection ── unsupported ─────────▶ "Can't read this format" + supported list
        │             encrypted ───────────▶ "Password protected" + guidance
        │             corrupt ─────────────▶ "File appears damaged" + retry paths
        ▼
  Extraction (cancellable, staged progress)
        │
        ├─ no text layer ────────────────▶ "No selectable text" + OCR / screenshot offer
        ├─ nothing actionable ───────────▶ "Couldn't find anything actionable" + rescue
        └─ candidates found
                │
                ▼
        Ambiguity resolution (date format, term end, section) — only what is genuinely needed
                │
                ▼
        Review  ◀────────────── edit / regenerate loop
                │
                ▼
        Confirm (calendar, count, range, recurrence)
                │
                ▼
        Permission request (in-context, first time only)
                │
                ▼
        Duplicate check ──── duplicates found ──▶ skip / replace / add anyway
                │
                ▼
        Batch write (chunked, progress, cancellable)
                │
                ├─ partial failure ──▶ honest report: N written, M failed, why, retry
                └─ success ──────────▶ "N events added" + Undo + Open Calendar
```

### Interruption flow

Process death during extraction leaves a durable in-progress record. On next launch:
*"Your previous document wasn't finished."* → Resume / Discard. Resume restarts extraction from
the persisted source reference; it does not attempt to resume mid-parse.

---

## Edge cases

Every row here has a corresponding test in [14-testing.md](14-testing.md). This list is the
specification for defensive behaviour, not a wish list.

### Dates and times

| Case | Required behaviour |
|---|---|
| `03/04/2026` with no disambiguating evidence | Retain both candidates; ask once; apply document-wide |
| `13/04/2026` | Unambiguous DD/MM (13 > 12); MAY resolve the whole document's convention |
| Missing year | Resolve from document context or ask; never assume silently |
| `32/09/2026` | Reject as invalid; flag; do not "fix" to 30/09 or 02/10 |
| `25:90` | Reject; flag |
| `9-10` in a column with `8-9`, `10-11` | Time range, inherits the column's AM/PM only if that is unambiguous across the column |
| Class at `12:00` | Noon, not midnight. Explicitly tested. |
| Event crossing midnight | Ends next day; must not produce a negative duration |
| Event ends before it starts | Reject as invalid; flag for review |
| Zero-duration event | Flag; do not silently pad to 60 minutes |
| Two events at the same time | Both retained; overlap surfaced in review, not auto-resolved |
| DST transition inside the recurrence range | Wall-clock time preserved (this is what the Calendar Provider does with a named timezone; verified by test) |
| Document in a different timezone than the device | Use the document's timezone if stated; otherwise device timezone, disclosed in the confirm step |

### Documents

| Case | Required behaviour |
|---|---|
| Empty file (0 bytes) | "This file is empty" |
| File renamed `.pdf` but actually XLSX | Detected by signature; processed correctly or reported honestly |
| Password-protected PDF | Identified; no bypass attempted |
| PDF with no text layer | Identified; OCR/screenshot offered |
| PDF with a *partial* text layer (mixed scanned/text pages) | Per-page decision, not per-document |
| 500-page PDF | Page-by-page, progress shown, cancellable, memory bounded |
| PDF with 100k-pixel-wide page | Render dimensions clamped; no unbounded allocation |
| Corrupt / truncated file | "File appears damaged"; parser failure contained in the sandbox process |
| ZIP bomb disguised as XLSX | Rejected on compression-ratio and uncompressed-size limits before extraction |
| XML entity-expansion attack in XLSX | Parser configured with entity expansion disabled |
| Path traversal in ZIP entry names (`../../`) | Entry names validated; never used to construct a filesystem path |
| Document with 4 timetables | All detected; user chooses; never all imported blindly |
| Document with 0 recognisable structure | "Couldn't find anything actionable" + rescue |

### Platform

| Case | Required behaviour |
|---|---|
| URI permission revoked between pick and process | Clear message; re-pick offered |
| Process death mid-extraction | Resume / discard offer |
| Device rotation mid-extraction | Work continues; no restart |
| Calendar permission denied | Explain consequence; offer the no-permission single-event `ACTION_INSERT` path; never nag |
| Calendar permission revoked after a previous grant | Detected at use; re-requested with explanation |
| No writable calendar on device | "No calendar available to write to" + guidance to add an account |
| Destination calendar deleted between review and write | Detected; write aborted; user re-selects |
| Calendar events deleted externally, then undo pressed | Undo removes what still exists; reports the discrepancy honestly |
| Low memory during extraction | Bounded allocation; graceful degradation to per-page processing |
| Low storage | Fail before starting, with a clear message |
| Airplane mode | Everything in V1 works |
| Batch write fails partway | Report exactly what was written; offer retry for the remainder; never claim success |
