# Extraction engines

Pure Kotlin in `:extraction`. No Android on the classpath, no I/O, no clock except one that is
injected. Every engine here is a function from data to data, which is what makes hundreds of
fast tests possible.

---

## Date engine

### Supported forms

| Pattern | Example | Notes |
|---|---|---|
| Numeric, 4-digit year | `18/09/2026`, `18-09-2026`, `18.09.2026` | Order may be ambiguous |
| Numeric, 2-digit year | `18/09/26` | Year windowed to ±50 years around today |
| Numeric, no year | `18/09`, `18.09` | Year must come from context or be asked |
| ISO | `2026-09-18` | Unambiguous by definition |
| Month name | `Sep 18`, `September 18`, `18 September`, `18th Sept 2026` | Abbreviations, ordinals |
| Weekday only | `Monday`, `Mon` | Recurring schedules; no date |
| Weekday + date | `Monday, 18 September` | Weekday is corroborating evidence |
| Ranges | `18–20 September`, `18/09 – 20/09` | Produces multiple dates |

Supported locales in V1: English month names and abbreviations, plus the common Indian-English
academic forms. Other scripts route to the honest "we couldn't read this" path rather than
producing partial nonsense.

### Ambiguity — the core algorithm

```
parse("03/04/2026")
  → candidates = [2026-04-03, 2026-03-04]      // both readings kept
  → resolvedBy = null
```

Resolution is attempted in this order, and the winning evidence is recorded:

1. **Sibling evidence.** Any other numeric date in the same document with a first component > 12
   proves the document's order. `13/04/2026` elsewhere → the document is DD/MM → `03/04` is 3 April.
   Strongest available evidence, and common in practice because most documents contain several dates.
2. **Weekday agreement.** `Monday 03/04/2026` — if only one reading falls on a Monday, that is the
   reading. Extremely reliable when present.
3. **Document declaration.** An explicit `(DD/MM/YYYY)` note in a header.
4. **Sequence coherence.** In an ordered list of dates, one reading produces a monotonic sequence
   and the other doesn't. Applied conservatively — only with ≥ 4 dates and a strict ordering.
5. **The user.** Asked once, applied document-wide, stored as a convention for next time.

**Device locale is not on this list.** It orders the options we present; it never picks one. A
device set to en-US processing an Indian college timetable would confidently produce dates months
wrong, and the user would have no signal that anything happened. This is the single highest-severity
failure mode available to the product ([ADR-004](05-architecture.md#adr-004--ambiguity-is-preserved-not-resolved)).

### Missing year

Sources, in order: an explicit year elsewhere in the document; an academic-session marker
(`2026-27`, `Odd Semester 2026`); the document's own creation metadata; the user.

If we fall back to "the current year", that is recorded as an assumption and caps the field at
`Medium`, so it surfaces in review rather than passing silently.

### Rejection

`32/09/2026`, `2026-02-30`, `00/01/2026` are `Invalid`. They are flagged with the raw text and
never coerced — not to the 30th, not to 2 October, not to anything. A document containing an
impossible date is a document we have misread or that contains a typo, and both cases need a human.

---

## Time engine

### Supported forms

| Pattern | Example | Result |
|---|---|---|
| 24-hour | `14:30`, `09:00` | Unambiguous |
| 12-hour with marker | `10 AM`, `2:30 pm`, `10a.m.` | Unambiguous |
| Bare | `10:00`, `10.30`, `10` | **Meridiem unknown** |
| Range | `10–11 AM`, `9:00–10:00`, `9-10`, `10:00 to 11:00` | Start + end |
| Compact | `1000-1100` | Recognised only inside a column of similar forms |

### The AM/PM rule

Never invent a meridiem. It may be *inferred* only from:

1. **24-hour evidence** — a value > 12 anywhere in the same column proves 24-hour notation.
2. **Column monotonicity** — a time column reading `8, 9, 10, 11, 12, 1, 2, 3` wraps at 12 and is
   an academic day. `1` is 13:00. Applied only when the column is otherwise strictly increasing
   and the wrap is single.
3. **Explicit markers on siblings** — one `AM` in the column resolves the block it belongs to.
4. **Plausibility bounds** — a schedule entry at 03:00 is possible but not plausible; this never
   resolves a meridiem on its own, it only flags.

Any inferred meridiem sets `meridiemInferred = true`, which caps confidence at `Medium` and makes
the entry visible in review. Column monotonicity is reliable and is why a typical timetable still
reaches "40 ready, 2 need review" rather than flagging every row.

Where nothing resolves it, the time is `Low` with the reason *"couldn't tell if this is morning or
evening"* and requires the user. It is never guessed from "classes are usually in the morning".

### End times

A start with no end is `endTime = Missing`. It is **not** start + 60 minutes.

Inference is permitted only from structure: the next entry in the same column starts at 10:00, so
the 09:00 entry ends at 10:00. That is `Derived` provenance with `Medium` confidence, and the
review row shows `09:00 – 10:00` with a check badge. Without structural evidence, the user is
asked or the event is created with the platform's default duration only after they confirm it.

---

## Location engine

Extracts room, building, campus, and address candidates — and, importantly, does not over-claim.

| Input | Interpretation |
|---|---|
| `K10`, `LT-3`, `Room 204` | Room identifier. **Not** a geographic location. |
| `C25-A107` | Building-plus-room. Scores higher than a bare code — see below. |
| `Block C, 2nd Floor` | Building-relative |
| `Main Campus`, `North Campus` | Campus |
| A full street address | Address — the only case where a map action is offered |

`K10` is stored as `EVENT_LOCATION` text so the user sees it, but it is never turned into a
geo-intent or geocoded. Handing a room number to a maps app produces a confident, absurd result
somewhere in another country, which is exactly the class of failure this product is built to avoid.

**Course codes and room codes overlap.** `IND4` has precisely the shape of `K10`, and a cell
reading `IND4 / C25-B001` will otherwise swap subject and room — observed on a real document.
So room-likeness is scored rather than tested: a building-plus-room code (`C25-B001`) or an
explicitly named room (`Lab 2`) outranks a bare letters-and-digits token, and the strongest
candidate in the cell takes the room slot. Ties go to the later token, since rooms follow
subjects more often than they precede them. Every room pattern requires at least one digit,
which is what keeps pure-letter subject codes like `DSD` or `AFL` out of the running entirely.

---

## Table reconstruction

Input: `List<TextRun>` with bounds. Output: a grid of cells, each mapped to its source runs.

**Text order is not reading order.** PDF content streams emit text in whatever order the producer
wrote it; OCR emits in detection order. A two-column timetable read in stream order interleaves
Monday and Tuesday into nonsense. Geometry is the only reliable signal.

### Algorithm

**1. Normalise geometry.** Convert everything to a common coordinate space, y-down, origin
top-left. Compute the median run height `h` — every tolerance below is a fraction of `h`, never a
fixed pixel value. This is what makes the same code work on a 72-dpi PDF and a 300-dpi scan.

**2. Cluster into lines.** Sort by vertical centre; group runs whose centres are within `0.6h`.
Superscripts and subscripts merge into their base line; a genuinely new line does not.

**3. Find column boundaries.** Project all run x-intervals onto the x-axis and accumulate
coverage. Sustained low-coverage bands that persist across most lines are column gutters. A gap
appearing in one line is a word space; a gap appearing in 80% of lines is a column boundary.

**4. Assign cells.** Each run lands in the column whose span contains its horizontal centre. Runs
straddling a boundary indicate either a merged cell or a wrong boundary — detected by checking
whether the straddling run's width exceeds the column width, which distinguishes the two.

**5. Detect orientation.**
- Weekday names along the **top row** → column-oriented (weekdays are columns, times are rows).
- Weekday names in the **left column** → row-oriented.
- Time patterns in the left column and weekdays on top → the classic grid.
- Neither → not a timetable; route to free-text entity extraction.

**6. Identify headers by content, not position.** A header row is one whose cells are
predominantly weekday names, time patterns, or short labels that repeat as column semantics. Row 1
is frequently a college name, a logo caption, or an empty spacer.

**7. Segment regions.** Horizontal whitespace bands wider than `2h`, or a full-width line of text
that is not tabular, split the page into regions. This is how `Section A` / `Section B` stacked on
one page become two schedules instead of one confused one.

### Merged cells

In a spreadsheet, merges are declared and read directly. In a PDF or image, a merge is inferred
from a run spanning multiple column boundaries, or from an empty cell directly beneath a
value-bearing cell in a column where every other row is populated. Inferred merges are `Medium`
confidence — they are a guess about layout, and layout guesses show up in review.

### Failure

When column detection produces inconsistent counts across lines, or no stable boundaries emerge,
the correct output is *"this layout isn't one we can read reliably"*, routing to rescue mode
([02-requirements.md § UC-4](02-requirements.md#uc-4-rescue-mode-later)). Forcing a grid onto
non-grid content produces plausible, wrong data — the worst possible outcome.

---

## Timetable reconstruction

Turns a grid into `ScheduleGroup`s.

### Row-oriented

```
Monday   | 09:00 | Data Structures | K10
```
Each row is an entry; columns are mapped to fields by header content.

### Column-oriented (the common academic grid)

```
        Monday        Tuesday      Wednesday
09:00   DSA / K10     OS / K11     DBMS / K10
10:00   OS  / K11     DSA / K10    Java / K12
```

The row header supplies the time, the column header supplies the weekday, and the cell holds
subject and room. Cell content is split on common separators (`/`, newline, `-`, `(`), and the
room is identified by pattern rather than position, because `DSA / K10` and `K10 / DSA` both occur.

Empty cells are free periods and are simply absent — not entries with missing data.

### Period columns (weekdays down, times across)

```
Section | Day       | P1 (8:00 AM-9:00 AM) | P2 (9:00 AM-10:00 AM) | …
CS1     | Monday    |                      | AFL / C25-A107        | …
CS1     | Tuesday   | DSD / C25-B001       | PS / C25-B001         | …
```

The transpose of the classic grid: the weekday comes from a column, the time from the
**column heading**. Institutional exports use this shape when one sheet carries many
sections, because it compresses each section into five rows.

Two details this layout forces:

- **A heading holding two period times means two columns were merged.** We cannot say which
  period a class in that column belongs to, so those columns are skipped and reported —
  never split down the middle and guessed at.
- Every heading is one clock, so the period times resolve together
  ([the AM/PM rule](#the-ampm-rule) applied across all headings at once).

### Section- and sheet-segmented

Vertically stacked sections on one page, or one section per sheet. Each becomes a
`ScheduleGroup` with a label taken from the heading text above it. The user chooses; we never
import all of them.

### Field mapping

Column semantics are decided by content sampling, not header text alone — a column where 80% of
cells match a time pattern is the time column regardless of whether it is labelled "Time",
"Period", or nothing at all. Header text raises confidence; it does not override the content.

---

## Recurrence inference

A weekly timetable is a recurrence, not 42 events ([ADR-005](05-architecture.md#adr-005--recurrence-via-rrule-never-expanded-instances)).

**Detection.** Entries sharing subject, weekday, start, end, and location collapse into one
recurring entry. In a weekly grid this is the default reading: each cell *is* a weekly occurrence.

**Bounds.** `UNTIL` comes from, in order: an explicit end date in the document; an academic session
marker resolved against a known term calendar; the user. There is no fourth option — an unbounded
weekly recurrence is never written.

**Start date.** The first occurrence of that weekday on or after the term start (or today, if the
term has begun). Never "today" for a Monday class created on a Thursday.

### Cases handled by asking, not guessing

| Case | Behaviour |
|---|---|
| Alternate / odd-even weeks | Detected from labels (`Week A`, `Odd`), surfaced as a question. Never inferred from an entry appearing in half the rows. |
| Holidays | `EXDATE` support exists in the model; no holiday calendar is bundled in V1. We do not silently skip dates. |
| Lab weeks, one-off sessions | Emitted as single dated events, not folded into a recurrence. |
| Saturday classes | Supported. `BYDAY` includes whatever the document contains. |
| Different rooms on different weeks | Not representable as one recurrence — split into separate events, and the review says why. |

The consistent rule: recurrence is only inferred where the document *states* a repeating pattern.
Where the pattern is irregular, the honest output is separate events, and where the irregularity
is signalled but not specified, the honest output is a question.
