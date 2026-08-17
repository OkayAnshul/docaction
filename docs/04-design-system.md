# Design system

Material 3 foundations, distinct identity. The goal is a product that reads as *considered* —
Apple-grade restraint in typography and spacing — while behaving unmistakably like an Android
app. No Cupertino mimicry: we use M3 components, M3 motion, Android navigation patterns, and the
system share sheet.

Everything below is a **token**. No raw `16.dp` or `Color(0xFF...)` appears in feature code.

---

## Colour

### Identity

The brand hue is a deep ink-blue — serious, document-adjacent, and crucially *not* the purple or
electric-teal that signals "AI startup". The accent is a warm amber used almost exclusively for
the attention state, which keeps it meaningful.

```kotlin
// Brand seed
val InkBlue      = Color(0xFF1B3A5C)   // primary seed
val Amber        = Color(0xFF9A6400)   // attention / warning role
```

### Semantic confidence roles — the important part

Confidence colour is a **separate token set** from M3's error/warning roles, because these states
are not errors. A low-confidence field is not a failure; it is a question. Using `error` red for
it would tell the user something went wrong, which is both false and alarming.

```kotlin
@Immutable
data class ConfidenceColors(
    val readyFg: Color,      val readyBg: Color,      // ✓  calm, recessive
    val checkFg: Color,      val checkBg: Color,      // ⚠  amber, noticeable
    val missingFg: Color,    val missingBg: Color,    // ?  neutral, not alarming
    val invalidFg: Color,    val invalidBg: Color,    // ✕  error role — genuinely wrong data
)
```

| State | Light fg | Dark fg | Glyph | Meaning |
|---|---|---|---|---|
| Ready | `#2E6B4F` | `#7FD4A8` | `✓` | Extracted with high confidence |
| Check | `#8A5A00` | `#F2C066` | `⚠` | Needs a human decision |
| Missing | `#5A6572` | `#A8B2BE` | `?` | Not present in the document |
| Invalid | `#B3261E` | `#F2B8B5` | `✕` | Present but impossible (`32 September`) |

All four pairs verified ≥ 4.5:1 against their surface in both themes.

**Rule:** every one of these states must be identifiable with colour removed. The greyscale test
is part of the design review checklist, not a nice-to-have — see [03-ux.md § Accessibility](03-ux.md#accessibility).

### Dynamic colour

Supported and **on by default** on Android 12+, applied to surfaces, primary, and containers.
The confidence roles are **excluded** from dynamic theming and remain fixed. A user's wallpaper
must never be able to make "needs attention" look like "ready", and the semantics of these four
states are worth more than the visual cohesion we'd gain by theming them.

### Surfaces

Flat, layered by tone rather than shadow. Elevation overlays only; drop shadows reserved for
genuinely floating elements (FAB, bottom bar over scrolled content). The review list uses no
cards — 42 elevated cards is visual noise and destroys the "quiet by default" principle.

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

### `ReviewRow`

```kotlin
@Composable
fun ReviewRow(
    time: String,                   // tabular, "09:00 – 10:00"
    subject: String,
    detail: String?,                // room / location
    state: ConfidenceState,
    reason: String?,                // one line, plain language, only when state != Ready
    selected: Boolean,
    actions: List<RowAction>,       // inline, only when state != Ready
    onToggle: () -> Unit,
    onEdit: () -> Unit,
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
spreadsheet cell rather than a visual region.

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

Light, dark, system — user-selectable, defaulting to system.

```kotlin
@Composable
fun DocActionTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,       // user-toggleable in Settings
    content: @Composable () -> Unit,
)
```

Provided via `CompositionLocal`:
- `MaterialTheme` (M3 colour, our typography, our shapes)
- `LocalConfidenceColors` — never dynamic
- `LocalSpace`, `LocalRadius`

Dark theme is a true dark (surface `#101418`), not an inverted grey. Elevation is expressed by
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
