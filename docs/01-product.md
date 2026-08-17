# Product

## Vision

Information gets trapped inside documents. A university emails a timetable PDF. A coaching centre
posts an exam schedule as a photo in a WhatsApp group. An employer shares shifts in an XLSX. In
every case the information is *already structured* — someone laid it out in rows and columns — and
in every case the recipient retypes it by hand into their calendar, or doesn't, and misses things.

DocAction closes that gap.

> Give it a document. Get the actions.

The product is not a document reader, not a scanner, and not an assistant. It is a machine that
converts structured-but-trapped information into native Android actions: calendar events,
recurring schedules, reminders.

**The feeling we are selling:** *"I gave it a messy document and it did the annoying work for me."*

**The feeling we are avoiding:** *"Another generic AI document scanner."*

The difference between those two is entirely a matter of whether the user can trust the output
without checking every row. That is the whole product problem.

---

## Market

### The shape of the demand

The need is universal but *bursty*. A student needs this three times a year — start of semester,
exam season, and when the timetable gets revised. That has consequences:

- **Retention will look terrible** measured as DAU/MAU, and that is fine. This is a utility, like
  a tax app. Judge it on task completion rate and reinstall rate, not daily engagement.
- **Subscriptions are a poor fit.** Nobody pays monthly for something they use three times a year.
  This drives the pricing decision in [15-business.md](15-business.md).
- **Acquisition is seasonal.** August–September and January–February in the Indian academic
  calendar. Marketing effort outside those windows is largely wasted.

### Where the demand concentrates

India is the strongest initial market, for reasons that are structural rather than sentimental:

- Enormous student population with formally-issued timetables.
- Institutional documents are overwhelmingly distributed as PDF and XLSX attachments, not as
  calendar feeds or portal integrations. Western universities increasingly publish `.ics` feeds,
  which removes the problem entirely; most Indian institutions do not.
- Android share dominant.
- Sensitivity to recurring costs — a ₹99–₹299 one-time purchase converts where a subscription
  does not.

The same document-shaped problem exists globally (conference schedules, shift rosters, travel
itineraries), but the *density* of the problem is highest where institutions email spreadsheets.

### Honest assessment of market size

This is a **niche utility with a large addressable population and low willingness to pay**. It is
a good indie product and a bad venture-scale product. Plan accordingly: near-zero infrastructure
cost, one-time purchase, no team, no burn. See [15-business.md § Cost architecture](15-business.md#cost-architecture).

---

## Competitive landscape

Verified 2026-08-10. Competitor behaviour changes; re-check before making claims in marketing.

### Adjacent, not competing

| Product | What it does | Why it isn't this |
|---|---|---|
| **Google Lens** | OCR + search on anything | Extracts text. Stops there. Will offer to create *one* event from a poster; has no concept of a timetable as a structure. |
| **Microsoft Lens / Adobe Scan / CamScanner** | Document capture → PDF/OCR | The output is a *file*, not an action. They solve "get it into a document"; we solve "get it out of one". |
| **ReadEra and PDF readers** | Reading | No extraction intent at all. |
| **Timetable / TimeTune / school planner apps** | Manual schedule entry with a nice UI | **These are the real competitor for the user's time.** They ask the user to type in 42 classes. Our entire value proposition is not doing that. Their reviews are full of "great app but took me an hour to set up." |
| **Google Calendar import** | `.ics` / CSV import | Requires the source to already be machine-readable. That is exactly the case where the user has no problem. |

### Directly competing

| Product | Platform | Model | Assessment |
|---|---|---|---|
| **pdftocal.com** | Web | Cloud AI | Upload a PDF, get events. Works, but requires uploading a document to a server, requires a browser, requires desktop-ish interaction, and the calendar write is an `.ics` download rather than a native calendar insert. |
| **PDF to Calendar Converter** | iOS | Cloud AI | Same shape. Not on Android. |
| **Nori (heynori.com)** | Web/SaaS | Cloud AI | Enterprise-adjacent, not student-priced. |

### The gap

Every direct competitor is **cloud-AI-first, web-first, and does not write to the device calendar
natively**. None of them is an offline Android app.

That gap is our position, and it is defensible for three reasons:

1. **Privacy is a real objection here, not a marketing angle.** A timetable identifies a person's
   physical location at specific times for the next four months. An exam schedule with a roll
   number is educational-record data. Users are right to hesitate before uploading these, and
   cloud-first competitors cannot remove that hesitation.
2. **Cloud AI has per-document cost.** Competitors must either meter usage or subscribe. We can
   charge once because on-device extraction costs us nothing per document. See
   [15-business.md § Cost architecture](15-business.md#cost-architecture).
3. **Native calendar write is materially better than `.ics` download** on mobile — one tap versus
   a download, a file manager, an import dialog, and a duplicate mess.

### What we would lose to a well-funded competitor

Cloud LLMs will out-extract our deterministic engine on genuinely weird documents. We should
expect to lose on raw extraction breadth. We win on trust, privacy, price, and platform fit — and
we compensate for extraction breadth with the [rescue-mode](02-requirements.md#use-cases) path,
which converts a hard document into an easy one by letting the user crop the relevant region.

**We must never compete on "our AI is smarter."** We will lose that argument and it is not the
argument our users care about.

---

## Users

### Primary: the student with a semester timetable

Amrita, 20, second-year engineering. The department WhatsApp group gets `TT_ODD_SEM_2026.xlsx`
three days before term. Four sheets, one per semester; each sheet has three sections stacked
vertically with merged header cells and a college logo image floating over row 2.

She needs *her* section's schedule, in her phone's calendar, with class reminders. Today she
either types it in (~25 minutes, error-prone) or she screenshots it and squints at the image all
semester.

What she actually cares about, in order:
1. That it picked the right *section*. Getting section C's timetable is worse than getting nothing.
2. That it doesn't spam her calendar with 42 separate one-off events.
3. That it takes less time than typing it in.

What she does not care about: OCR confidence percentages, which model was used, file formats.

### Secondary: the exam candidate

Same person, different document, different stakes. An exam schedule is 6–10 dates, and **a wrong
date is catastrophic in a way a wrong class time is not**. This use case demands the most
conservative behaviour in the product: an ambiguous date on an exam schedule must always be
confirmed, never inferred.

### Tertiary: the shift worker / conference attendee / traveller

Same machinery, different vocabulary. Deliberately deferred past V1 so the timetable case can be
made excellent first.

### Anti-user

Someone who wants a chatbot to answer questions about a document. We are not building that, and
trying to serve them would compromise the product for everyone else.

---

## Research findings that shaped the design

Drawn from reviews and complaint patterns across timetable apps, scanner apps, and calendar
import tools (2026-08-10).

| Observed complaint | Design response |
|---|---|
| "Great app, but setting up my timetable took an hour" (timetable apps) | This is the entire product thesis. Time-to-first-imported-schedule is the metric that matters. |
| "It added 60 events and I had to delete them one by one" (import tools) | Recurring events, not repeated events ([11-actions.md](11-actions.md#recurrence)). Surgical undo. Duplicate detection *before* writing. |
| "Imported the wrong times / off by hours" (calendar sync tools) | Explicit timezone handling; never infer AM/PM ([08-extraction.md](08-extraction.md#time-engine)). |
| "It says it scanned my document but the text is garbage" (OCR apps) | Low OCR confidence must produce an honest "this is hard to read" with a crop offer, not garbage output. |
| "Why does a scanner app need my contacts / all files?" (scanner apps) | Minimal permissions, requested at the moment of use, explained in-context ([12-privacy-security.md](12-privacy-security.md)). |
| "Free version is useless / paywalled after one scan" | The user must complete one full successful import before ever seeing a purchase prompt ([15-business.md](15-business.md#monetization)). |
| Duplicate calendar events are a *recurring, widely-reported* failure across the entire calendar ecosystem | Duplicate detection is a first-class feature, not a nicety. |

---

## Positioning

**One line:**
> Turn schedules and documents into calendar events — on your device, reviewed before anything changes.

**The three claims, in priority order:**

1. **It saves you a boring half hour.** (The reason anyone downloads it.)
2. **It shows you what it found before it touches your calendar.** (The reason anyone trusts it.)
3. **Your documents never leave your phone.** (The reason anyone with a sensitive document uses it.)

**What we will not claim:**
- Not "AI-powered." The phrase is now a negative signal in this category and invites the exact
  scepticism we are trying to defuse.
- Not "supports all PDFs" or "understands any spreadsheet." We state precisely what is supported
  ([10-formats.md](10-formats.md)). Overclaiming here converts a 5-star user into a 1-star review.
- Not "100% accurate." We claim *reviewed*, which is both true and more reassuring.

**Tone:** calm, specific, competent. The product should sound like a good tool, not an excited
startup.

---

## Principles

These are decision rules, not values. When a design question arises, resolve it against these in
order.

### 1. Automatic when confident. Guided when uncertain. Never silently wrong.

The governing principle. Every ambiguity has exactly three legitimate resolutions: extract it
confidently, ask the user, or decline. Guessing is not on the list.

### 2. Absence is a value.

If the document does not contain an end time, the end time is *missing*. It is not 60 minutes
after the start time because that is usually right. Missing data is represented, surfaced, and
either confirmed by the user or left out. See [09-confidence.md](09-confidence.md#hallucination-prevention).

### 3. Nothing changes without confirmation.

The calendar is the user's, and it is consequential. No write happens without an explicit,
informed confirmation showing what will change. Destructive operations require a second one.

### 4. Every claim traces to a source.

Any extracted value can answer "where did you get that?" with a page and a region. This is not a
debugging feature — it is how the user calibrates trust in the other 41 rows they didn't check.

### 5. Deterministic first.

If a rule can decide it, a rule decides it. AI is a fallback for genuine structural ambiguity, is
optional, is off by default, and can never execute an action directly.

### 6. The document stays on the device.

No account, no upload, no backend, by default. Any deviation is opt-in, per-document, and stated
plainly before it happens.

### 7. Failure is a state, not an error.

"I couldn't read this" is a legitimate, well-designed outcome with a path forward — crop it,
screenshot it, try another file. It is not a red toast with a stack trace.

### 8. The user's correction wins, permanently and immediately.

When a user fixes a value or picks a different section, the system regenerates from that
correction. It never asks them to start over, and it never quietly re-derives what they overrode.

---

## What this product is not

Explicitly out of scope, permanently or until proven otherwise:

- A chatbot or document Q&A tool
- A PDF reader, editor, or annotator
- A general OCR utility
- A note-taking app
- A calendar app (we write to the user's calendar; we don't replace it)
- A task manager
- Cloud document storage or sync
- A collaboration platform

Each of these has been considered and rejected because it dilutes the single sentence the product
must be able to finish: *"It's the app that turns your timetable into calendar events."*
