# Privacy and security

The documents this app handles are more sensitive than they look. A semester timetable states
where a specific person will physically be, at named times, for four months. An exam schedule
carries a roll number and is an educational record. A payment notice carries financial detail.

Treat every document as if it contains all of that, because some of them do.

---

## Offline-first

### What that means concretely

| | V1 |
|---|---|
| Account required | No |
| Backend | None |
| Network permission | Not requested |
| Works in airplane mode | Entirely |
| Documents leave the device | Never |

`INTERNET` is not in the manifest. This is worth stating precisely because it is verifiable — a
user or reviewer can check the permission list and confirm that the app *cannot* upload their
document, regardless of what the marketing says. That is a stronger guarantee than a privacy
policy, and it is the reason to keep it true for as long as possible.

> **This was false in the built app until 2026-08-18.** The permission was not in *this*
> manifest, which is what everyone had checked — but ML Kit pulls in
> `com.google.android.datatransport:transport-backend-cct`, which declares `INTERNET` for
> usage telemetry, and WorkManager adds `ACCESS_NETWORK_STATE` to schedule it. Manifest
> merging put both into the shipped package, so the Play listing would have read "full
> network access" and a user following the invitation above would have found the claim
> wrong.
>
> Both are now removed with `tools:node="remove"`, and
> `ManifestWiringTest.theAppCannotReachTheNetwork` asserts against the *merged* manifest so a
> future dependency cannot reintroduce them quietly. Text recognition is unaffected: it is
> entirely on-device, and only the optional logging wanted the network.
>
> The general lesson is the reason the assertion exists: reading your own manifest tells you
> what you asked for, not what you ship.

Adding the permission later (for billing, which requires it) is a visible change, and the release
that does so must be accompanied by an in-app explanation of what it is and is not for.

### If cloud AI is ever added

It isn't in V1 ([ADR-008](05-architecture.md#adr-008--ai-is-a-port-with-no-v1-implementation)).
If it ever is:

- Off by default, opt-in per document — not a settings toggle the user forgets they enabled.
- An unmissable statement before the first transmission: *"This part of your document will be sent
  to an online service to be read."*
- Only the ambiguous region, never the whole document.
- Never automatic. Never silent. Never a "smart" default.

---

## Privacy

### Data flows

```
Document ──▶ parsing ──▶ extraction ──▶ review ──▶ Calendar Provider
   │        (PDF parses in the isolated process; see below)       │
   └── stays at its original URI                    ends up in the user's own calendar
```

Nothing branches off this diagram. There is no analytics branch carrying content, no crash-report
branch carrying text, no cache holding extracted personal data past the session.

### What is stored, and what is not

| Stored | Not stored |
|---|---|
| Filename (`Timetable.pdf`) | Document bytes |
| Format, size, page count | Extracted text |
| Content hash (SHA-256, local only) | Event titles, rooms, instructor names |
| Counts and timestamps | OCR output |
| Created calendar event ids and provenance URIs | Anything from a discarded import |
| User conventions (preferred calendar, date order) | Any identifier that could follow the user |

The history entry reads *"Timetable.pdf · 42 events · 10 Aug"*. It does not record what the classes
were. [06-data-model.md § Persistence](06-data-model.md#persistence-model) has the schema.

### The in-progress exception

Candidates must survive process death ([FR-7.4](02-requirements.md#fr-7-history)), which means
extracted content is briefly persisted. Bounded by:

- Written only while an import is `InProgress` or `AwaitingReview`.
- Deleted on commit, on discard, and on app start if older than 24 hours.
- Excluded from cloud backup in `data_extraction_rules.xml`.
- Stored in app-private internal storage, never external, never a content provider.

### Analytics

Anonymous product events only. No user id, no device fingerprint, no content.

**Allowed:**
```
document_import_started      { format }
document_import_completed    { format, candidate_count_bucket, duration_bucket }
extraction_failed            { format, reason }        ← reason is a closed enum
review_opened
review_item_corrected        { field }                 ← which field, never the value
action_confirmed             { count_bucket, had_recurrence }
duplicate_detected           { count_bucket }
undo_used
rescue_mode_used
```

**Forbidden, permanently:**
```
✗ event titles, locations, instructor names
✗ filenames
✗ any raw or normalised extracted string
✗ document hashes
✗ free-text error messages (they leak content through exception strings)
✗ anything enabling cross-session identification
```

Counts are bucketed (`1`, `2–10`, `11–50`, `50+`) rather than exact, because an exact count
combined with a timestamp is closer to an identifier than it looks.

Analytics is opt-out in Settings and the toggle is honoured immediately, not at next launch.

### Crash reporting

Same discipline, harder to enforce, because exception messages leak content by default —
`ParseException: unexpected token at "MATH-301 Prof. Sharma K10"` is a content leak sitting in a
crash report.

Mitigations:
- Domain failures are a closed enum ([06-data-model.md](06-data-model.md#outcome)); no free-text
  error crosses out of the domain.
- Third-party exception messages are stripped at the sandbox boundary — type and stack frames
  are kept, the message is dropped, and nothing structured crosses at all.
- The document URI never enters a crash report.
- Crash reporting is opt-in.

### Permissions

| Permission | When | Why |
|---|---|---|
| `READ_CALENDAR` | Confirm step | List writable calendars; detect duplicates |
| `WRITE_CALENDAR` | Confirm step | Create the confirmed events |
| `POST_NOTIFICATIONS` | First reminder *(later)* | Deliver the reminder |

**Not requested, ever:** storage (SAF and the Photo Picker make it unnecessary),
`MANAGE_EXTERNAL_STORAGE`, `QUERY_ALL_PACKAGES`, location, contacts, camera until the camera path
ships, and `INTERNET` in V1.

Not requesting storage costs extra implementation work and is worth it — "why does a scanner app
want all my files?" is a top-five complaint in this category and a permission the app genuinely
does not need.

---

## Security

### Threat model

**Adversary:** whoever produced the document. It arrived by WhatsApp, email, or a shared drive.
Assume it is hostile.

**Assets:** the user's other documents, their calendar, the app's data, and device stability.

**Attack surface:** the PDF parser, the ZIP and XML parsers, the image decoder, and content URIs.

### The parsers are the risk

[ADR-001](05-architecture.md#adr-001--pdf-text-extraction-uses-a-three-tier-strategy) commits us to
`PdfBox-Android 2.0.27.0`, last released **2023-01-02**. Upstream Apache PDFBox has shipped 2.0.3x
releases since, including security fixes that this port has not picked up. XLSX adds a ZIP parser
and an XML parser.

This is a real, accepted risk. The mitigation below is architectural rather than hopeful, and
as of 2026-08-18 it is built for the parser that carries most of the risk.

### Isolated process — built for PDF

> **Holds today.** PdfBox runs in the isolated service declared below, so a crash in it kills a
> throwaway process, a hang in native code is killable, and a decompression bomb exhausts that
> process's heap rather than the one holding the user's review. Text runs come back as bytes
> that `DocumentCodec` reads with bounded allocations — deliberately hand-written, because a
> reflective deserialiser would let a compromised parser choose what gets constructed on this
> side of the boundary.
>
> **Does not hold yet.** The XLSX and CSV readers are still in the UI process.
>
> Also enforced and tested: the input limits below, no network permission anywhere in the
> merged manifest, and documents never leaving the device.

```xml
<service android:name=".SandboxParsingService"
         android:isolatedProcess="true"
         android:exported="false"
         android:process=":sandbox" />
```

`isolatedProcess` runs the parser under its own UID with **no permissions and no filesystem
access**. A remote-code-execution bug in the PDF parser lands in a process that can do nothing:
no calendar, no network (there is none anyway), no access to app-private storage, no ability to
read the user's other files.

Additional properties this buys:

| Property | Mechanism | Status |
|---|---|---|
| Crash containment | Parser segfault kills a throwaway process; the app reports "file appears damaged" | **not built** — a segfault takes the app |
| Enforceable timeouts | Process kill, so native code stuck in a loop is still stoppable | **not built** — cooperative cancellation only |
| Memory ceiling | A decompression bomb OOMs the sandbox, not the UI | **not built** — bounded instead by the input limits below |
| Clean cancellation | Kill and move on | **not built** |

The cost is an AIDL boundary and chunked payloads (the Binder buffer is ~1 MB). Worth it.

### Input limits

Every limit exists because its absence is exploitable, not because it seemed tidy.

| Limit | Value | Prevents |
|---|---|---|
| File size | 100 MB | Resource exhaustion |
| PDF pages | 100 before asking | Time exhaustion |
| Bitmap dimensions | 4096×4096 after clamping | Decode bomb (a 30000² PNG is 3.6 GB at ARGB_8888) |
| Image decode | bounds-first, always | Same |
| ZIP compression ratio | 100:1 | ZIP bomb |
| ZIP uncompressed total | 200 MB | ZIP bomb |
| ZIP entry count | 10 000 | Entry-count bomb |
| ZIP entry names | reject `..`, absolute paths, backslashes | Path traversal |
| XML entity expansion | disabled | Billion laughs |
| Cells per sheet | 1 000 000 | Pathological workbook |
| Per-page timeout | 15 s | Algorithmic complexity attack |
| Whole-document timeout | 5 min | Same |

### Content URIs

- Never construct a filesystem path from a URI or from a ZIP entry name.
- Always open through `ContentResolver`; never `File(uri.path)`.
- Re-check readability immediately before use — grants are revocable between selection and
  processing, and this is a normal occurrence, not an edge case.
- Take persistable permissions only where the flow needs them beyond the session.
- Never re-share a received URI onward.

### Temporary files

Minimised. Where needed (rendered page bitmaps for the source view), they live in app-private
cache, are named with random ids rather than the document's name, are deleted at the end of the
import, and are excluded from backup.

### No WebView

The app has no WebView. Nothing here needs one, and adding one would introduce an entire class of
vulnerabilities (JS bridges, content-URI handling, mixed content) for no benefit. If HTML rendering
is ever needed, it goes through a sanitised native renderer or nothing.

### No dynamic code

No reflection-based plugin loading, no `DexClassLoader`, no downloaded executable content. A
document can never cause code to run.

### Dependency hygiene

- Dependency-verification metadata in Gradle so a swapped artifact fails the build.
- Vulnerability scanning in CI.
- `PdfBox-Android` is tracked in the [risk register](15-business.md#risk-register) with an
  explicit re-evaluation trigger: if a CVE lands that affects our parse path, tier 1 becomes the
  default and tier 2 is disabled on devices below API 35, degrading to OCR.

---

## Play policy compliance

| Policy | Status |
|---|---|
| Target API 36 by 2026-08-31 | Already `targetSdk 36` |
| Data safety declaration | "No data collected" if analytics is off; otherwise anonymous app-activity only. Declaration must match reality exactly. |
| Permissions declaration | Only calendar; each justified in-listing |
| No `MANAGE_EXTERNAL_STORAGE` | Not requested — this policy is aggressively enforced and SAF makes it unnecessary |
| Photo/video permissions policy | Not requested — Photo Picker instead |
| Exact alarms | Assessed before reminders ship; inexact alarms likely sufficient |
| Billing v8+ by 2026-08-31 | `billing-ktx 9.1.0` when monetization lands |
| Families policy | Not targeting children; no ads, no cross-app tracking |

The data safety form is the one most commonly filled in optimistically and then contradicted by
the app's actual behaviour. Ours is easy to get right precisely because there is so little to
declare — and it must be re-verified against the code on every release that touches analytics.
