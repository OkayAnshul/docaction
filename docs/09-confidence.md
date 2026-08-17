# Confidence, validation, and trust

> Automatic when confident. Guided when uncertain. Never silently wrong.

This is the document that defines what that sentence means mechanically. If you read one file in
this set, read this one.

---

## Confidence

### Four levels, per field

| Level | Meaning | UI | Can become an action |
|---|---|---|---|
| **High** | Read directly, corroborated, no transformation that could change meaning | Plain row, `✓` | Yes, silently |
| **Medium** | Read reliably but something was inferred, repaired, or derived | Plain row, `✓`, reason available on tap | Yes, silently |
| **Low** | Read, but a decision was made that could be wrong | Attention row, `⚠`, reason shown inline | Only after explicit resolution |
| **Missing** | Not present in the document | Attention row, `?` | Never — see below |

**Confidence is per field, never per document.** A single global score cannot express "the date is
certain but the room is a guess", which is the actual state of most extractions. A document-level
score would either flag everything (useless) or flag nothing (dangerous).

### What determines the level

| Signal | Effect |
|---|---|
| Source is a PDF text layer or a spreadsheet cell | Baseline `High` |
| Source is OCR with element confidence ≥ 0.85 | Baseline `High` |
| Source is OCR with element confidence 0.60–0.85 | Baseline `Medium` |
| Source is OCR below 0.60 | Baseline `Low` |
| `OcrCharacterSubstitution` was applied | **Caps at `Medium`** |
| Meridiem was inferred | **Caps at `Medium`** |
| Value was derived from structure rather than read | **Caps at `Medium`** |
| Date ambiguity resolved by evidence | `High` if by weekday agreement or sibling date; `Medium` if by sequence coherence |
| Date ambiguity unresolved | `Low`, and blocks candidate creation |
| Value contradicts its column's pattern | **Demoted one level** |
| Value corroborated by a second independent signal | May promote `Medium` → `High` |
| User provided or confirmed it | `High`, with `UserProvided` source |

The caps are the important entries. They are absolute — no accumulation of other positive signals
can lift an OCR-repaired value to `High`, because the repair itself is the uncertainty and nothing
else in the document speaks to it.

### Not shown as a number

The user sees `✓`, `⚠`, `?`. Never `93.4%`. A percentage invites the user to calibrate against a
scale they have no basis for, and it implies a precision the score does not have. The internal
score is a routing mechanism, not information the user can act on.

---

## Hallucination prevention

The requirement: the system must never invent a date, time, location, title, person, or
recurrence **and present it as something the document said**. Not "should try not to" — must
not, structurally.

### What changed, and why this sentence used to be shorter

It read "must never invent … must not, structurally", full stop, and the code no longer
matched it. The engine now fills two specific gaps: a start time with no end gets one hour,
and a bare date with no time becomes an all-day event. Across the 151-document corpus that is
181 `all-day-from-date-only` and 52 `assumed-duration` assumptions — a third of everything
produced. A document saying "Submit by 30 April" produced nothing at all before, which was
not honesty so much as uselessness.

Inference is therefore permitted, under three conditions that are enforced by types rather
than by care:

1. **It is recorded.** The candidate carries an `Assumption` naming the rule, so nothing is
   filled in anonymously.
2. **It is visible.** An assumption forces `NeedsAttention`, and the row reads *"End time
   assumed (1 hour) · tap to change"*. `accept()` couples the two so a candidate cannot carry
   an assumption and look settled.
3. **It never claims provenance.** The source becomes `SourceReference.Assumed`, which Source
   View refuses to place on a page — because there is no page to point at.

The distinction that survives is not "never guess" but **never guess silently**. An assumed
end time is labelled as assumed and is one tap from being corrected; a fabricated one is
indistinguishable from a read one, and that remains forbidden. `Confident.Missing` still
carries no value to fall through to, and the choke point below is still the only way a
candidate is built.

Strict behaviour is still available and still tested: `InferencePolicy.Strict` disables both
rules, and `CalendarEventCandidateTest` pins it so the guarantee cannot be lost by accident.

### Absence is a value

```kotlin
// The type has no value field to default.
data class Missing(val reason: String) : Confident<Nothing>
```

There is no `endTime ?: startTime.plusHours(1)` available to write, because `Missing` carries no
`endTime` to fall through. Reading a value requires an exhaustive `when`, so every consumer is
forced to decide what absence means in its context. This is [ADR-003](05-architecture.md#adr-003--confidence-is-a-type-not-a-number)
and it is the load-bearing decision of the entire product.

### The single choke point

```kotlin
CalendarEventCandidate.from(entry, zone, term): Outcome<CalendarEventCandidate>
```

Private constructor, one factory. It rejects:

- any required field that is `Missing`
- any field that is `Low` without an accompanying `UserProvided` override
- any `DateInterpretation` with multiple candidates and no `resolvedBy`
- any `Recurrence` without an `until`
- any entry failing validation

**One function to audit.** Every guarantee in this document reduces to "is `from()` correct", which
is a tractable review and a heavily tested one, rather than a property distributed across the
codebase and dependent on everyone remembering it.

### Four provenance states, never conflated

| State | Representation | Meaning |
|---|---|---|
| **Extracted** | `SourceReference.PdfSpan` / `SheetCell` / `ImageRegion` | Read from the document |
| **Inferred** | `SourceReference.Derived(from, rule)` | Computed from other extracted values, with the rule named |
| **User-provided** | `SourceReference.UserProvided(field, at)` | The user supplied or corrected it |
| **Unknown** | `Confident.Missing(reason)` | Not present |

`Derived` names its inputs and its rule, so "where did this end time come from?" answers *"the next
class in this column starts at 10:00"* rather than *"we assumed an hour"*. If the honest answer
would be the latter, the value is `Missing` instead.

### What the AI path may never do

Detailed in [AI policy](#ai-policy). The short version: it can propose structure, it can never
produce a value that wasn't in the document, it can never reach `High`, and its output passes
through the same `from()` choke point as everything else.

---

## Validation

Runs after scoring, before inference. Two distinct outcomes with different consequences.

### Reject — impossible

| Check | Examples |
|---|---|
| Calendar-valid date | `32 September`, `Feb 30`, `2026-13-01` |
| Clock-valid time | `25:90`, `13:75`, `-1:00` |
| Ordered interval | end before start |
| Positive duration | zero-length event |
| Range sanity | year outside ±10 years |

Rejected values are marked `Invalid`, retain their raw text, and are surfaced with the original
string shown. **They are never coerced.** `32/09` does not become 30/09 or 02/10 — either would be
a confident wrong answer, and the document has told us something is wrong that a human needs to see.

### Flag — possible but suspicious

| Check | Examples |
|---|---|
| Plausibility | class at 03:00; 9-hour class; 5-minute class |
| Density | one entry alone in a weekday column among 40 |
| Consistency | one entry's duration differs from all its siblings |
| Coverage | 6 weekday columns detected but only 3 populated |
| Duplication | the same subject twice in one time slot |

Flagged values remain usable. They become `NeedsAttention`, appear under the review filter, and
carry a plain-language reason. A 7 AM lab is real; dropping it would be a worse error than
mentioning it.

### The distinction matters

Rejecting the suspicious would silently delete legitimate data. Flagging the impossible would
imply we might use it. Each error is unacceptable in the other's place, which is why these are two
mechanisms and not one threshold.

---

## Human in the loop

### Ask only what is necessary, and only once

Every question costs the user attention and costs us the perception of competence. So:

- **Document-wide answers are asked once.** Date order is one question, not 42.
- **Questions are batched** into the disambiguation step before review, not scattered through it.
- **Questions that only matter for some entries** are deferred into review, attached to those
  entries, rather than blocking the whole flow up front.
- **Answers are remembered** as conventions where they generalise — preferred calendar, typical
  term end, date order for a recurring document source.

### Questions state consequences

Not *"Select date format"* but *"The document writes dates like 03/04/2026. We can't tell which
part is the month."* with both readings shown as concrete dates and a link to see it in context.
The user is being asked to make a judgment; they need the evidence.

### Corrections propagate

A correction re-enters the pipeline at the stage it invalidates, not at the beginning
([07-pipeline.md § Stage 12](07-pipeline.md#stage-12--user-review)). Choosing a different section
regenerates that section's entries; it does not discard the term end the user just set.

A `UserProvided` value is **never overwritten by re-derivation**. If the user sets an end time and
then changes the date format, the end time survives. Corrections are facts, and the system does
not argue with them.

### Bulk over serial

Reviewing 42 items one at a time is a worse experience than typing them in, which would defeat the
product. So: bulk selection, a "needs attention" filter that isolates the 2 items that matter, and
a clearly-labelled path to accept the ready ones without inspecting each. See
[03-ux.md § Review](03-ux.md#review--the-screen-the-product-lives-or-dies-on).

---

## AI policy

### Position

AI is **optional, off, and absent in V1**. The product is fully useful without it. This is a
product decision, not a technical limitation — see
[ADR-008](05-architecture.md#adr-008--ai-is-a-port-with-no-v1-implementation).

Deterministic methods handle: parsing, table geometry, date and time interpretation, validation,
recurrence, and every action. That is the entire pipeline.

### What AI could ever be for

Only genuine *structural* ambiguity: an unusual layout that geometry can't segment, a cell whose
content mixes subject and room in an unrecognised way, a document whose organisation is
idiosyncratic. Classification and segmentation — never value production.

### Constraints if it is ever implemented

```
AI ▸ schema validation ▸ source verification ▸ confidence evaluation ▸ user confirmation ▸ action
```

| Constraint | Rationale |
|---|---|
| Only ambiguous regions are sent, never whole documents | Minimises exposure and cost |
| Output must satisfy a strict schema | Free-form text cannot enter the pipeline |
| **Every value must be found verbatim in the extracted source text** | This is the anti-hallucination gate. A model-produced value with no textual anchor is discarded, not down-weighted. |
| AI-sourced values cap at `Medium` | They can never pass silently |
| Off by default, opt-in per document | Explicit statement before any transmission |
| Results cached by region hash | Cost control |
| No free-form output reaches an executor | Enforced by `from()`, same as everything else |

The source-verification gate is what makes this safe rather than merely careful. A model asked to
interpret a table can rearrange the table's contents; it cannot introduce a date that does not
appear in the text we extracted, because we check.

### Why not just use a cloud LLM for everything

It would extract better on weird documents. It would also: cost per document, require a network,
require uploading a document that reveals where a person physically is for the next four months,
and introduce confident fabrication — the one failure mode this product is built to eliminate.

The trade is deliberate. We accept a narrower set of readable documents in exchange for a much
higher trustworthiness on the ones we do read, plus rescue mode as the escape hatch for the rest.
A user who is told "I can't read this, crop the part you need" is better served than one who is
handed a plausible, wrong timetable.
