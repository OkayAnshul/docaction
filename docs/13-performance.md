# Performance, memory, battery, errors

## Performance

### Targets are budgets, not measurements

No number here is a claim about how the app performs. They are budgets to build against and then
verify — every one gets replaced with a measured p50/p95 on real hardware before launch.

| Operation | Budget (mid-range device) |
|---|---|
| Cold start to interactive Home | < 800 ms |
| Import sheet open | < 100 ms |
| Format detection | < 200 ms |
| Text extraction, 1-page text PDF | < 500 ms |
| Text extraction, 10-page text PDF | < 2 s |
| OCR, one 2048px image | < 3 s |
| Table reconstruction, typical timetable | < 300 ms |
| Full pipeline, typical 1-page timetable PDF | < 2 s |
| Review screen first frame after extraction | < 200 ms |
| Calendar write, 42 events | < 3 s |
| Scroll through 42-row review | 0 dropped frames |

"Mid-range" means the reference device in [14-testing.md § Device coverage](14-testing.md#device-coverage),
not a flagship. Benchmarking on a fast phone produces numbers that are true and useless.

### Where the time actually goes

Measured priorities, in the order they will matter:

1. **OCR** dominates whenever it runs. The correct optimisation is *not running it* — the tier
   system exists so a text PDF never touches OCR ([10-formats.md](10-formats.md#pdf)).
2. **Page rendering** for OCR input. Resolution is the lever: 300 dpi is enough, 600 dpi doubles
   the time for no accuracy gain.
3. **PDF parsing** with PdfBox is slower than the framework path; tier 1 is a meaningful speedup
   where available.
4. **Table reconstruction** is O(n log n) over runs and is not a bottleneck at realistic sizes.
5. **Calendar writes** are provider-bound; batching is what matters, not our code.

### Perceived performance

- The pipeline streams progress; the user sees stage changes rather than a frozen screen.
- The review screen renders incrementally as candidates arrive — the first weekday group appears
  before the last is parsed.
- Cancellation is instant, always. A cancel button that takes two seconds to respond is worse than
  no cancel button.

### Compose specifics

- `LazyColumn` with stable keys on `EntryId`. Without keys, editing one row recomposes the list.
- Review row state is hoisted; rows are `@Immutable` data holders so recomposition is skippable.
- Baseline Profiles generated for the import flow — the highest-value startup optimisation
  available and cheap to produce.
- `derivedStateOf` for the filter/selection counts, which otherwise recompute on every scroll.
- No `Modifier.animateContentSize` on list rows; it causes measure-pass churn on long lists.

---

## Memory

### The rule

Never allocate based on a number the document told us.

Every OOM in an app like this traces to trusting a declared dimension: an image's width, a PDF
page's size, a spreadsheet's row count. All of these are attacker-controlled
([12-privacy-security.md](12-privacy-security.md#input-limits)).

### Controls

| Area | Control |
|---|---|
| Image decode | `inJustDecodeBounds` first, always; `inSampleSize` computed; long edge capped at 3072 px |
| Bitmap config | `RGB_565` for OCR input — half of `ARGB_8888`, and alpha is irrelevant |
| PDF pages | One page in memory at a time; bitmap recycled before the next |
| Render size | Clamped to 4096×4096 regardless of page dimensions |
| Spreadsheets | Streamed; shared strings resolved on demand, never held whole |
| Text runs | Per page, released after the page's structure is extracted |
| Review candidates | Held in memory (42 small objects is nothing); the source bitmaps are not |
| Source view | Page rendered on demand when the sheet opens, released when it closes |

### The sandbox as a memory boundary

The parsing process has its own heap. A decompression bomb exhausts *that* process and is killed;
the UI process is untouched and reports a clean failure. This turns the worst-case memory
scenario from "the app dies" into "this file appears damaged"
([ADR-002](05-architecture.md#adr-002--untrusted-document-parsing-runs-in-an-isolated-process)).

### Leak discipline

- Bitmaps recycled explicitly after use; never held by a ViewModel.
- Cursors closed via `use {}` without exception.
- Coroutine scopes tied to the correct lifecycle; the pipeline runs in a ViewModel scope, not a
  global one.
- LeakCanary in debug builds.

---

## Battery

### Doing nothing is the strategy

The app is active only while the user is looking at it. There is no background work in V1.

**Not done, deliberately:**
- No polling.
- No foreground services.
- No background OCR.
- No gallery or screenshot monitoring.
- No periodic sync.
- No wake locks.

### Screenshot detection — the one worth explaining

An obvious feature idea: watch for screenshots and offer to extract from them. It is rejected for
V1 because a correct implementation requires either a persistent observer (battery cost, and it
sees *every* image the user saves) or broad media access (a permission we otherwise don't need,
and a Play policy exposure).

If it is ever built, it must be opt-in, scoped strictly to the screenshots directory, use
`ContentObserver` rather than polling, and be visibly disableable. The V1 answer is
**Share → DocAction**, which achieves the same outcome with zero battery cost and zero permissions.

### WorkManager

Present in the dependency set, used for nothing in V1. It earns its place only when a genuinely
deferrable user-initiated task exists — for instance, a large document the user chose to process
later. Using it for anything the user is waiting on would be worse than a foreground coroutine.

---

## Error handling

### The translation layer

Domain failures are a closed enum ([06-data-model.md](06-data-model.md#outcome)). Each maps to
exactly one user-facing screen. Nothing else reaches the user.

| `FailureReason` | Headline | Cause line | Actions |
|---|---|---|---|
| `Encrypted` | This document is password protected | We can't open protected files. | Try another file · Learn more |
| `Corrupt` | This file appears to be damaged | It may not have downloaded fully. | Try another file · Open in another app |
| `NoTextLayer` | This PDF doesn't contain selectable text | It's probably a scan or a photo. | Read it as an image · Choose pages · Try another file |
| `Empty` | This file is empty | There's nothing in it to read. | Try another file |
| `TooLarge` | This file is too large | We can only handle files up to 100 MB. | Choose specific pages · Try another file |
| `UnsupportedFormat` | We can't read this kind of file | We support PDF, images, XLSX and CSV. | Try another file |
| `PermissionRevoked` | We can't open this file any more | The app that shared it may have withdrawn access. | Choose it again |
| `Timeout` | This is taking too long | The document is unusually complex. | Try specific pages · Screenshot the part you need |
| `Cancelled` | Cancelled | Nothing was changed. | — |
| `NothingActionable` | We couldn't find anything actionable | No dates or times we could recognise. | Crop the relevant area · Tell us what to look for · Try another file |

### Rules

**Never shown:** exception class names, stack traces, "null", error codes, the words "failed" or
"invalid input", or any raw parser message.

**Always present:** a plain statement of what happened, a cause when we honestly know one, and at
least one concrete next action.

**Tone:** *this document is difficult*. Not *you did something wrong*, and not *our software
broke*. The first is usually true, the second is unkind, and the third destroys confidence in the
results the app does produce.

### Third-party exceptions

Stripped at the sandbox boundary. Type and stack trace are retained for crash reporting; the
message is dropped, because parser exception messages routinely quote document content
([12-privacy-security.md](12-privacy-security.md#crash-reporting)).

### Recovery paths always exist

Every failure screen offers a route forward. The universal fallback is rescue mode — crop the part
you need — which converts almost any unreadable document into a readable image. A failure screen
with only a "Close" button is a bug.

### Interruption

Process death mid-extraction leaves a durable record. On next launch:

> **Your previous document wasn't finished.**
> Timetable.pdf · started 10 minutes ago
> [ Resume ] [ Discard ]

Resume restarts extraction from the stored source reference. It does not attempt to resume
mid-parse — parser state is not checkpointable, and pretending otherwise would produce partial
results presented as complete.

Discard deletes the ephemeral candidate data immediately.

### Timeouts

| Scope | Limit | On expiry |
|---|---|---|
| Per page | 15 s | Skip the page, warn, continue |
| Per sheet | 30 s | Skip the sheet, warn, continue |
| Whole document | 5 min | Stop, offer page/range selection |
| Calendar batch chunk | 30 s | Report partial, offer retry |

Per-page and per-sheet timeouts degrade rather than fail — one pathological page in an 80-page
document should cost that page, not the document. The result is `Partial` with a named warning.
