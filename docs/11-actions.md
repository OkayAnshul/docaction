# Actions

Actions are where the app stops being a document tool and starts changing something the user
cares about. Everything before this point is reversible because nothing has happened. Everything
here is consequential.

---

## Action engine

```kotlin
interface ActionExecutor<in T : ActionCandidate> {
    suspend fun preview(candidates: List<T>, target: ActionTarget): Outcome<ActionPreview>
    suspend fun execute(candidates: List<T>, target: ActionTarget): Outcome<ExecutionReport>
    suspend fun revert(importId: ImportId): Outcome<RevertReport>
}
```

Every executor must provide all four capabilities — preview, validation, execution, and revert.
An action type that cannot be previewed or cannot be reverted does not ship, because the product's
central promise ("review before anything changes", "undo") is only as good as its weakest action.

V1 ships `CalendarEventExecutor`. `ReminderExecutor` attaches at the same seam later, as do
location, contact, and URL actions.

`ExecutionReport` distinguishes written, skipped, and failed with per-item reasons. There is no
boolean success — [partial failure is a first-class outcome](#partial-failure).

---

## Calendar integration

### Permission

`WRITE_CALENDAR` and `READ_CALENDAR`, requested **at the confirm step**, never at launch
([FR-6.10](02-requirements.md#fr-6-actions)). The rationale shown in context:

> DocAction needs calendar access to add the events you selected. It only adds what you confirm.

`READ_CALENDAR` is needed for duplicate detection and for listing writable calendars — worth
explaining, because a user who understands why a permission is needed grants it, and one who
doesn't uninstalls.

**If denied:** the flow does not dead-end. The single-event path uses `Intent.ACTION_INSERT`,
which needs no permission and hands the event to the user's calendar app pre-filled. It doesn't
scale to 42 events, and the UI says so honestly rather than pretending.

We do not re-prompt after a denial. A permission rationale shown twice is a nag; shown three
times it is a 1-star review.

### Choosing the destination

Writable calendars come from `CalendarContract.Calendars` where
`CALENDAR_ACCESS_LEVEL >= CAL_ACCESS_CONTRIBUTOR` and the calendar is visible and syncable.

The user **explicitly selects one**. If exactly one exists it is pre-selected but still shown; if
several exist there is no default. There is no "first writable calendar" fallback — silently
writing 42 events into a work account the user forgot was on the device is a genuine harm and an
entirely avoidable one.

The choice is remembered as a convention and pre-selected next time, still visible.

### Writing

```kotlin
ContentValues().apply {
    put(Events.CALENDAR_ID, calendarId)
    put(Events.TITLE, candidate.title)
    put(Events.DTSTART, candidate.start.toInstant().toEpochMilli())
    put(Events.EVENT_TIMEZONE, candidate.zone.id)          // always explicit
    put(Events.EVENT_LOCATION, candidate.location)
    put(Events.RRULE, candidate.recurrence?.toRRule())
    put(Events.DURATION, candidate.recurrence?.let { candidate.duration.toIso8601() })
    put(Events.DTEND, if (candidate.recurrence == null) endMillis else null)

    // Provenance — ADR-006
    put(Events.CUSTOM_APP_PACKAGE, "com.okayanshul.docaction")
    put(Events.CUSTOM_APP_URI, "docaction://import/$importId/${candidate.id}")
    put(Events.UID_2445, candidate.uid)
}
```

Two details that are easy to get wrong:

- **A recurring event uses `DURATION`, not `DTEND`.** The Calendar Provider rejects a recurring
  event that specifies `DTEND`. This is a common source of silent write failures.
- **`EVENT_TIMEZONE` is always set explicitly** to an IANA id. Omitting it lets the provider guess,
  and a timetable that shifts by hours after travel or a DST transition is the "wrong times"
  complaint that recurs across the whole calendar-import category.

### Batching

`ContentResolver.applyBatch` in chunks of **150 operations**.

The Binder transaction buffer is roughly 1 MB per process. A 42-event timetable with reminders and
long location strings will exceed it in a single batch, and the failure mode is a
`TransactionTooLargeException` partway through — leaving the calendar in a half-written state.
Chunking bounds each transaction and makes partial failure recoverable rather than mysterious.

Each chunk is atomic. Between chunks, progress is reported and cancellation is honoured (already-
written chunks stay written and are reported as such — see [partial failure](#partial-failure)).

### Recurrence

```
RRULE:FREQ=WEEKLY;BYDAY=MO;UNTIL=20261205T000000Z
```

One event, not 15. [ADR-005](05-architecture.md#adr-005--recurrence-via-rrule-never-expanded-instances)
covers why; the practical consequences:

- The user can edit the room once and it applies to the whole series.
- Deleting is one action, not fifteen.
- The calendar UI shows it as a class that repeats, which is what it is.

`UNTIL` is always present. `EXDATE` is supported by the model for holiday exclusions but is not
populated in V1 — we do not ship a holiday calendar, and silently skipping dates would be worse
than including them.

### Reminders

Written as `CalendarContract.Reminders` rows in the same batch. Default 10 minutes before, shown
and changeable in the confirm step. One reminder per event; we do not add a second "helpful" one.

---

## Duplicate detection

Runs **before** the write, during confirm. "It added 60 duplicate events" is one of the most
common complaints across the entire import category
([01-product.md § Research](01-product.md#research-findings-that-shaped-the-design)).

### Three signals

1. **Our own provenance.** Query `Events` where
   `CUSTOM_APP_PACKAGE = 'com.okayanshul.docaction'` and the `CUSTOM_APP_URI` import id matches a
   previous import of the same content hash. Exact, cheap, and survives an app data wipe
   ([ADR-006](05-architecture.md#adr-006--provenance-is-written-into-the-calendar-not-only-into-our-database)).
2. **Content match on existing events.** Query `Instances` over the candidate's date range;
   compare normalised title, start time, and duration. Catches events the user created by hand or
   imported through another tool.
3. **Import-level match.** Same content hash imported before → warn about the whole import at once
   rather than 42 times.

Title comparison is normalised (case, whitespace, punctuation) with a similarity threshold —
"Data Structures" and "DATA STRUCTURES (DSA)" should match. The threshold errs toward *reporting*
a possible duplicate, because a false positive costs one glance and a false negative costs a
polluted calendar.

### Presentation

```
  3 similar events already exist

  Data Structures · Mon 09:00
  Already in Personal · added by you

  [ Skip these 3 ]   [ Replace them ]   [ Add anyway ]
```

**Skip** is the default. **Replace** deletes the existing event and writes ours — and is only
offered when the existing event was created by us. We never offer to replace an event the user
created by hand; that is destructive and not ours to propose.

---

## Undo

### What it does

Deletes exactly the events created by one import:

```sql
DELETE FROM Events
WHERE customAppUri LIKE 'docaction://import/{importId}/%'
```

Room mirrors the created `_ID`s as a fast path, but the `CUSTOM_APP_URI` is the source of truth —
so undo works after an app data wipe, a reinstall, or a backup restore.

### What it never does

**Never a time-range delete.** Not `DELETE WHERE dtstart BETWEEN x AND y`. That is the obvious
implementation, it is what breaks when the tracking database is lost, and it would delete the
user's own events. If provenance cannot identify an event, undo does not touch it and says so.

### Availability

- Snackbar immediately after the write, for the session.
- Permanently from the History detail screen. A user who realises three days later that they
  imported the wrong section needs this, and that is a realistic scenario given the section-choice
  failure mode.

### Honest reporting

If some events were already deleted externally, undo removes what remains and reports the
discrepancy: *"38 events removed. 4 were already deleted."* It does not claim to have removed 42.

### Editing after import

If the user edits an imported event in their calendar app, our provenance tag survives (we don't
own the row, but the columns persist). Undo would still remove it. So: when undo detects that an
event's `DIRTY` flag is set or its content differs from what we wrote, it lists those separately
and asks before removing them.

---

## Calendar safety rules

Non-negotiable, and each maps to a test in [14-testing.md](14-testing.md).

| Rule | Enforcement |
|---|---|
| Never write without explicit confirmation | Execution is unreachable except from the confirm screen |
| Never write to a calendar the user didn't choose | No default fallback in calendar selection |
| Never modify a pre-existing event | Replace is offered only for events we created |
| Never delete without provenance | Undo filters on `CUSTOM_APP_URI`; no time-range deletes exist in the codebase |
| Never report success on failure | `ExecutionReport` has no boolean; verification reads back |
| Never leave a half-written state unreported | Chunk boundaries are reported; partial results are surfaced |
| Always set an explicit timezone | Enforced in the candidate builder |
| Always bound a recurrence | Enforced in the candidate builder |

---

## Partial failure

The realistic outcome on a flaky provider, a revoked permission mid-write, or a deleted
destination calendar.

```
  Added 38 of 42 events

  4 couldn't be added because the calendar
  stopped responding.

  [ Retry the remaining 4 ]   [ Undo everything ]   [ Done ]
```

Rules:
- The written events stay written and are tracked, so undo remains complete.
- Retry writes only the missing ones — no duplicates.
- The failure reason is translated, never a provider exception string.
- This is never rendered as a success screen with a small footnote.

---

## Verification

After every write, read back what was created:

```kotlin
resolver.query(
    Events.CONTENT_URI,
    arrayOf(Events._ID, Events.RRULE, Events.CUSTOM_APP_URI),
    "${Events.CUSTOM_APP_PACKAGE} = ? AND ${Events.CUSTOM_APP_URI} LIKE ?",
    arrayOf(PACKAGE, "docaction://import/$importId/%"),
    null,
)
```

Assert the row count matches, and that `RRULE` is present on every candidate that carried a
recurrence. A recurring event silently written as a single event is a real failure mode — it
produces one class instead of fifteen — and it is invisible without this check.

The success screen's count comes from **this query**, not from the number of rows we attempted.

---

## Reminders (later)

Deadline and payment extractions become notifications rather than calendar events, since they are
points in time rather than blocks.

- Backed by `AlarmManager` for exact user-visible times, with the `SCHEDULE_EXACT_ALARM` /
  `USE_EXACT_ALARM` implications assessed against Play policy before shipping — inexact alarms are
  likely sufficient for "your fee is due tomorrow" and avoid the policy question entirely.
- Lead time chosen by the user: same day, one day before, one week before.
- Same provenance model, so undo works identically.
- `POST_NOTIFICATIONS` requested at the point of use, like every other permission here.
