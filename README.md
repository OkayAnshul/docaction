# DocAction

**Turn a timetable into calendar events, entirely on your phone.**

A university emails a timetable PDF. A coaching centre posts an exam schedule as a photo in a
WhatsApp group. An employer shares shifts in a spreadsheet. In every case the information is
*already structured* — someone laid it out in rows and columns — and in every case the recipient
retypes it by hand, or doesn't, and misses something.

DocAction reads the document, shows you what it found, and writes the events you approve.

- **On-device.** No account, no backend, and **no `INTERNET` permission** — the platform refuses
  the connection, so it is not a promise we are asking you to take on trust.
- **Deterministic.** Rules, not a language model. The same document gives the same answer, and
  the answer can be traced to the page it came from.
- **Reviewed.** Nothing reaches your calendar until you have seen it and said yes.

> Automatic when confident. Guided when uncertain. Never silently wrong.

---

## The idea worth stealing

Most extractors have one number: a confidence score. That cannot express *"the date is certain
but the room is a guess"*, which is the actual state of nearly every real extraction — so it
either flags everything (useless) or flags nothing (dangerous).

Here, **confidence is a type**, per field:

```kotlin
sealed interface Confident<out T : Any> {
    data class High<out T : Any>(val value: T, val source: SourceReference) : Confident<T>
    data class Medium<out T : Any>(val value: T, val source: SourceReference, val reason: String) : Confident<T>
    data class Low<out T : Any>(val value: T, val source: SourceReference, val reason: String) : Confident<T>
    data class Missing(val reason: String) : Confident<Nothing>   // deliberately holds no value
}
```

`Missing` has no `value` field, so `endTime ?: startTime.plusHours(1)` is not something you can
write — there is nothing to fall through to. Reading a value requires an exhaustive `when`, so
every consumer is forced to decide what absence means in its context.

Every field also carries a `SourceReference`: a page and a rectangle, a spreadsheet cell, a
derivation naming its rule, or `UserProvided`. An untraceable value is unrepresentable, and a
user's correction can never be confused with something we read or silently overwritten by
re-derivation.

One function turns entries into calendar events, and it is the only one that can:

```kotlin
CalendarEventCandidate.from(entry, zone, term): Result   // private constructor
```

It refuses any required field that is `Missing`, any `Low` field without an explicit override,
any ambiguous date that nothing resolved, and any recurrence without an end. **One function to
audit** rather than a property distributed across the codebase and dependent on everyone
remembering it.

---

## What it does

| | |
|---|---|
| **Reads** | PDF (text layer, with OCR fallback), XLSX, CSV, plain text, photos and screenshots |
| **Understands** | weekly period grids, weekday-per-row schedules, dated tables, staff rosters, prose containing dates |
| **Writes** | one recurring event with an `RRULE`, never 42 copies; all-day items with correct UTC boundaries; reminders |
| **Asks** | when a date is genuinely ambiguous, when a document holds several schedules, when many rows share the same gap |
| **Undoes** | by provenance — `CUSTOM_APP_URI`, never a time-range delete |

### Getting in

Share sheet, "open with", in-app picker, the Android Photo Picker, the camera (delegated to
whichever camera app you already have — no `CAMERA` permission), or a block of shared text.

---

## Two decisions that shaped it

**A flag is only worth something if it is rare.** Across the corpus, 252 of 369 extracted events
arrived flagged as needing attention — 68% of every review screen — from exactly two causes: a
date with no time of day, and a start with no end. At that density the warning is wallpaper, and
a user who learns to scroll past amber rows will scroll past the one that mattered.

The fix was not to hide the assumptions. It was to notice that 175 rows were asking the *same
question*, ask it once before the list, and apply the answer to all of them. That is 252 → 0,
measured end-to-end by a test rather than asserted. Anyone who wants to go row by row still can.

**Never guess silently.** The engine may fill a gap — a bill saying "submit by 30 April" produced
nothing at all under the original invent-nothing rule, which was honesty in form and uselessness
in practice. But every filled gap creates an `Assumption`, which forces the row to be flagged and
attaches a `SourceReference.Assumed` so the source sheet says *"we assumed this"* rather than
pointing at a page that says no such thing. There is no path that fills a gap and leaves the row
looking certain.

---

## Architecture

Sixteen Gradle modules. The extraction engine is **pure JVM** with no Android on its classpath,
which is what makes a 151-document regression suite runnable in seconds instead of on an emulator.

```
:app ────────────── Compose UI, the import state machine, ViewModels
:domain ─────────── Confident<T>, SourceReference, the candidate choke point. No Android.
:extraction ─────── tables, dates, times, timetables, prose. No Android.
:document:pdf ───── PdfBox + framework tiers          :document:image ── ML Kit OCR
:document:spreadsheet, :document:csv, :document:text  (pure JVM)
:document:sandbox ─ isolated-process parsing service
:actions:calendar ─ Calendar Provider writes, duplicate detection, undo
:actions:reminder ─ alarms that survive reboot and timezone changes
:core:designsystem, :core:database, :core:settings, :core:common
:corpus ─────────── golden-file harness. Never shipped.
```

Readers produce positioned `TextRun`s and nothing else; the extraction engine consumes those and
nothing else. That seam is what makes adding a format an extension point rather than an
aspiration.

### Untrusted parsing runs somewhere it cannot hurt you

`PdfBox-Android`'s last upstream release was January 2023, and every input is attacker-controlled
— PDFs arrive by WhatsApp. So PDF parsing happens in a service declared `isolatedProcess="true"`:
its own UID, no permissions, no filesystem, no way to reach anything but the app that bound it.

A parser crash kills a throwaway process. A parser stuck in native code is killable. A
decompression bomb exhausts *that* heap. Text runs come back as bytes read by a hand-written
codec with bounded allocations — deliberately not a reflective deserialiser, which would let a
compromised parser choose what gets constructed on the other side.

<details>
<summary>A trap worth knowing about, if you ever build one of these</summary>

The obvious way to hand a file to a process that cannot open files is to pass the descriptor and
let it read `/proc/self/fd/N`. The path exists, `stat` works, everything *looks* right — but
opening it is a **fresh open** of the underlying file, subject to the usual checks, and SELinux
does not let an isolated process open an app's data files. Every document came back
`FileNotFoundException`, which the reader faithfully translated to "this file appears damaged".

That was the sandbox working exactly as designed, and it presented as a parser bug. The
descriptor itself is readable; only re-opening the path is not, so the bytes are streamed off the
descriptor instead.
</details>

---

## Testing

```bash
./gradlew test                  # JVM: domain, extraction, corpus, design system, screens
./gradlew connectedAndroidTest  # calendar writes, Room migrations, PDF, OCR, the sandbox
./gradlew assembleDebug
```

**1,971 JVM tests and 83 instrumented tests**, the latter on a real API 36 device.

The centre of gravity is `:corpus` — 151 real documents (university timetables, exam schedules,
bus and rail timetables, gym schedules, council bin calendars, court cause lists, bills,
adversarial files) with checked-in golden output. A change that alters what the engine produces
shows up as a diff, and the summary pins the whole corpus in six numbers so a regression is one
line of review rather than 151.

Some tests exist because prose is cheap and code is not:

- `ContrastTest` recomputes WCAG relative luminance for every colour token against every surface,
  so a palette nudged two shades lighter fails the build instead of shipping.
- `ManifestWiringTest` asserts against the **merged** manifest on a device — reading your own
  manifest tells you what you asked for, not what you ship. It is how the `INTERNET` permission
  described below was found.
- `CorpusAttentionTest` measures what fraction of a review screen is shouting, which no
  golden-file diff would ever catch.
- `TimetableStoreTest` covers the code path that used to destroy stored timetables. Its headline
  case was verified to go red against the original behaviour.

---

## Status

Version `0.1.0`. Honest about what works:

**Works, and is tested:** PDF/XLSX/CSV/text/image extraction · weekly and dated schedules ·
review, edit, confirm · recurring and all-day calendar writes · duplicate detection ·
provenance-based undo · import history · manual event creation · timetable editing with
write-through · reminders that survive reboot · the isolated PDF sandbox.

**Not built yet:** calendar conflict detection (overlapping events) · XLSX and CSV still parse in
the UI process, not the sandbox · two-way timetable diff against the calendar · bulk manual entry.

**Found along the way:** the app was shipping with the `INTERNET` permission. It was not in this
project's manifest — ML Kit pulls in `transport-backend-cct`, which declares it for usage
telemetry, and manifest merging put it in the package. The Play listing would have read "full
network access" while the app invited users to verify the opposite. Removed, and pinned by a test
that reads the merged manifest.

---

## Docs

`docs/` is the design record, and it is written to be read — decisions with their reasoning,
including the ones that were reversed and why.

| | |
|---|---|
| [`01-product.md`](docs/01-product.md) | who it is for, and what it deliberately is not |
| [`03-ux.md`](docs/03-ux.md) | information architecture, screen specs, accessibility |
| [`04-design-system.md`](docs/04-design-system.md) | the "Ink" palette, with verified contrast |
| [`05-architecture.md`](docs/05-architecture.md) | module rules and the ADRs |
| [`09-confidence.md`](docs/09-confidence.md) | the confidence model. If you read one file, read this |
| [`12-privacy-security.md`](docs/12-privacy-security.md) | threat model, what is stored, what is not |
| [`14-testing.md`](docs/14-testing.md) | the corpus, and what each suite is for |

---

## Building

Android Studio, or:

```bash
git clone git@github.com:OkayAnshul/docaction.git
cd docaction
./gradlew assembleDebug
```

Kotlin 2.2.10 · AGP 9.2.1 · Compose BOM 2026.06.01 · Room 2.8.4 · minSdk 26 · targetSdk 36.

No API keys, no `local.properties` beyond your SDK path, no service accounts. It builds offline
because it runs offline.

---

## Licence

Not yet chosen — all rights reserved for now. Ask if you want to use any of it.
