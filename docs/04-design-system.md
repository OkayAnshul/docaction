# Design system

Material 3 foundations, distinct identity. The goal is a product that reads as *considered* —
Apple-grade restraint in typography and spacing — while behaving unmistakably like an Android
app. No Cupertino mimicry: we use M3 components, M3 motion, Android navigation patterns, and the
system share sheet.

Everything below is a **token**. No raw `16.dp` or `Color(0xFF...)` appears in feature code.

---

## Colour

The system is called **Ink**, and it replaced a deep blue-and-amber palette on 2026-08-18.

### Identity

Documents are ink on paper, and so is the interface. The chrome is achromatic — text is ink,
surfaces are paper, and the primary button is an ink-filled block the way a stamp is — and
**all chroma is reserved for meaning**.

That is the whole idea, and it is doing real work rather than being a style. Nothing is
coloured to look nice, so colour is trustworthy when it appears: if something is green it is
because it is ready, not because green was available. It is also what makes "the interface gets
quieter as confidence rises" structural instead of aspirational. On a screen whose only colours
are the four confidence states, forty ready rows recede on their own and the two that need a
decision are the only colour present.

The palette it replaced used a blue primary. That was defensible and it was also spending the
user's attention on a button that always looks the same.

### Tokens

Every value was computed against its surfaces, not chosen by eye. `ContrastTest` recomputes them
with the WCAG relative-luminance formula on every build, so a palette nudged two shades lighter
fails rather than ships.

| Role | Light | Dark | Worst case |
|---|---|---|---|
| `surface` (paper) | `#FBFAF8` | `#0E1013` | — |
| `surfaceSunken` | `#F2F0EC` | `#08090B` | — |
| `surfaceRaised` | `#FFFFFF` | `#171A1F` | — |
| `ink` — primary text, primary fill | `#16181B` | `#F2F1EE` | 15.44 AAA |
| `inkMuted` — secondary text | `#5B6167` | `#A2A8AF` | 5.51 AA |
| `inkFaint` — captions, timestamps | `#676C72` | `#868C93` | 4.65 AA |
| `accent` — focus, selection, links, active nav | `#2A46C0` | `#8FA5FF` | 6.74 AA |
| `border` — control outlines | `#8D887F` | `#686E77` | 3.10 |
| `hairline` — row separators | `#E4E1DB` | `#272B31` | *deliberately below 3:1* |

`border` and `hairline` are separate on purpose. A control outline has to clear 3:1 to satisfy
WCAG 1.4.11 or the user cannot find the edge of a text field; a row separator has to stay *under*
it, because a separator that reads as a border starts carrying meaning it was never given, and a
list of forty rows turns into forty boxes. Both directions are asserted.

**Dynamic colour retints the accent only.** An achromatic identity and wallpaper-tinted surfaces
are incompatible — washing the page in colour breaks the premise on every screen at once — so the
user's palette lands on the selection and the focus ring while paper stays paper. Material's
dynamic primary is tone 40 in light and tone 80 in dark, so an accent taken from it clears the
3:1 a focus indicator needs whatever the wallpaper is.

### Semantic confidence roles — the important part

A **separate token set** from Material's error and warning roles, because these are not errors. A
low-confidence field is a question, not a failure, and painting it error-red would tell the user
something went wrong, which is both false and alarming.

| State | Glyph | Light | Dark | Worst case |
|---|---|---|---|---|
| Ready | `✓` | `#1F6B4A` | `#6FD3A3` | 5.66 AA |
| Check | `⚠` | `#8A5300` | `#F0C069` | 5.56 AA |
| Missing | `?` | `#676C72` | `#868C93` | 4.65 AA |
| Invalid | `✕` | `#B3261E` | `#F2B8B5` | 5.74 AA |

Excluded from dynamic theming. A user's wallpaper must never be able to make "needs attention"
look like "ready".

**Ready, Check and Invalid sit at near-identical relative luminance** — 0.1130, 0.1159, 0.1106 —
and that is deliberate rather than incidental. No state is allowed to shout louder than another.
The consequence is that the three are genuinely indistinguishable in greyscale, so the glyph and
the row's own words carry the entire meaning and a colour-blind user loses nothing. That is the
strongest available reading of NFR-7, and it is a property to preserve rather than a flaw to
correct — `readyCheckAndInvalidCarryEqualVisualWeight` fails if someone "fixes" it.

### Surfaces

Flat, layered by tone rather than shadow. Elevation overlays only; drop shadows reserved for
genuinely floating elements (bottom bar over scrolled content). The review list uses no cards —
42 elevated cards is visual noise and destroys the "quiet by default" principle.

---

## Typography

One family, deliberately restrained scale. Only six roles are used in the whole app; if a
seventh is needed, that's a signal the hierarchy is wrong.

| Token | Size / line | Weight | Used for |
|---|---|---|---|
| `display` | 32 / 40 | 600 | Result headlines — "42 classes found" |
| `title` | 22 / 28 | 600 | Screen titles |
| `subject` | 17 / 24 | 500 | Review row subject — the class name |
| `body` | 15 / 22 | 400 | Body text, explanations |
| `meta` | 13 / 18 | 400 | Times, rooms, timestamps, source refs |
| `label` | 13 / 16 | 600 | Buttons, chips, group headers (uppercase, +0.5 tracking) |

Numerals are **tabular** everywhere a time or count appears (`FontFeatureSetting("tnum")`). A
column of times that doesn't align is the single cheapest way to look unpolished, and this list
is nothing but columns of times.

Rules:
- Never more than three type roles visible in one component.
- Times use `meta` weight but tabular figures, so `09:00` and `11:00` align exactly.
- No italic anywhere. No letter-spacing changes except `label`.

---

## Spacing

4dp base grid. Six steps, named by intent rather than size, so usage stays consistent:

```kotlin
object Space {
    val hairline = 2.dp    // icon-to-text nudges
    val tight    = 4.dp    // within a line
    val snug     = 8.dp    // between related lines
    val default  = 16.dp   // standard gap, screen horizontal padding
    val section  = 24.dp   // between logical groups
    val major    = 40.dp   // above a screen headline, around empty states
}
```

Screen horizontal padding is always `default` (16dp). Content never touches the edge; nothing
is ever indented to a value not in this list.

---

## Shape

```kotlin
object Radius {
    val sm  = 8.dp    // chips, badges, inline controls
    val md  = 12.dp   // buttons, text fields, list row highlight
    val lg  = 20.dp   // sheets, dialogs, the primary CTA
    val xl  = 28.dp   // the Add Document CTA only
}
```

The primary CTA gets `xl` and is the only element in the app with that radius — it reads as *the*
button without needing colour or size gimmicks.

---

## Components

### `ConfidenceBadge`

The most important component in the app. Appears on every review row.

```kotlin
@Composable
fun ConfidenceBadge(
    state: ConfidenceState,        // Ready | Check | Missing | Invalid
    modifier: Modifier = Modifier,
)
```

- Glyph + colour + (in dense contexts) nothing else; the accompanying reason text lives in the row.
- Carries a `contentDescription` naming the state in words — "ready", "needs attention",
  "missing", "invalid". Never announces as an unlabelled icon.
- 20dp glyph in a 48dp touch target when interactive.

### `EventRow`

Named `ReviewRow` here until 2026-08-18; the code has always called it `EventRow`, and it is
used on more than the review screen.

```kotlin
@Composable
fun EventRow(
    title: String,
    time: String,                   // tabular, "09:00 – 10:00"
    detail: String?,                // room / location
    state: Confidence,
    selected: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
    reason: String? = null,         // one line, plain language, only when state != Ready
    actions: List<RowAction> = emptyList(), // inline, only when state != Ready
)
```

Ready rows render as three lines with a trailing ✓ and no container. Attention rows gain a tinted
container at `Radius.md`, the reason line, and inline action buttons. **The visual weight
difference between the two is the core of the review screen's design** — it is what lets a user
scan 42 rows and land on the 2 that matter.

### `StageProgress`

Renders the real pipeline stages. Takes a list of `(label, status)` where status is
pending/active/done/skipped. Skipped stages are not rendered. Active stage pulses subtly; the
whole component is a polite live region.

### `SourceSheet`

Bottom sheet rendering the document region with the source rectangle highlighted. Takes a
`SourceReference` and a page bitmap provider. Falls back to a text excerpt when the source is a
spreadsheet cell rather than a visual region, and says so plainly for a value that was assumed
or that the user supplied — there is no page to point at for either.

Lives in `:app` rather than `:core:designsystem`, because rendering a page needs the document.
Listed here because it is part of the system's vocabulary, not because it ships in that module.

### Buttons

| Role | Component | Usage |
|---|---|---|
| Primary | Filled, `Radius.lg`, full width in flows | One per screen, states the outcome ("Add 40 events") |
| Secondary | Tonal | Alternative paths ("Review timetable") |
| Tertiary | Text | Dismissals, "Try another file" |
| Destructive | Filled with `error` role | Only for delete/replace, always after confirmation |

Button labels are **verb + object + count** wherever a count exists. "Continue" is only
acceptable when the outcome is stated adjacently.

### Empty states

Composed from `EmptyState(headline, body, action)`. Always a real sentence, never "No data".
Illustration budget: none in V1. A well-set sentence outperforms a generic vector illustration
and costs nothing to maintain.

---

## Theming

Light, dark, system — following the system setting.

```kotlin
@Composable
fun DocActionTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColour: Boolean = true,      // retints the accent only
    content: @Composable () -> Unit,
)
```

Provided via `CompositionLocal`:
- `MaterialTheme` (M3 roles mapped from Ink, our typography, our shapes)
- `LocalInkColours` — the roles Material has no slot for: `inkFaint`, `border`, `hairline`, `accent`
- `LocalConfidenceColours` — never dynamic
- `LocalSpace`, `LocalRadius`

Dark theme is a true dark on a warm axis (surface `#0E1013`), not an inverted grey — so paper
stays paper rather than becoming blue-grey. Elevation is expressed by
tonal lift, and the confidence tokens are re-tuned for dark rather than reused, because the amber
that reads as "attention" on white reads as "highlighted" on black.

---

## What this system deliberately excludes

| Excluded | Why |
|---|---|
| Gradients | Nothing here is a hero surface. Gradients date fast and read as decoration. |
| Glassmorphism / blur | Costs GPU, hurts contrast, adds nothing to a list of times. |
| Sparkle / AI iconography | Actively counterproductive — see [01-product.md § Positioning](01-product.md#positioning). |
| Cards for list items | 42 cards is noise. Containers are reserved for exceptions. |
| Custom fonts | Platform font is excellent, free, and already loaded. |
| Illustration set | Unearned maintenance cost pre-launch. |
| A colour for "processing" | Progress is communicated by the stage list, not by hue. |

Every one of these was a real option. They are listed so the exclusion is a decision on record
rather than an oversight someone later "fixes".
