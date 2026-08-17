# Format handling

Every format handler implements `DocumentReader` and emits positioned `TextRun`s
([06-data-model.md](06-data-model.md#document)). Downstream stages know nothing about formats.

Parsing is **intended** to run in the isolated sandbox process ([ADR-002](05-architecture.md#adr-002--untrusted-document-parsing-runs-in-an-isolated-process)) — it does not yet.
`:document:sandbox` has no source in it, so every parser currently runs in the UI process. This
line previously stated the opposite as fact.

---

## PDF

### Three tiers

[ADR-001](05-architecture.md#adr-001--pdf-text-extraction-uses-a-three-tier-strategy) has the full
evidence. Summary:

| Tier | When | Mechanism | Output |
|---|---|---|---|
| 1 | API 35+, or S+ with PDF SDK extension ≥ 13 | `PdfRenderer.Page.getTextContents()` | text + `RectF` bounds |
| 2 | all supported devices | `PdfBox-Android 2.0.27.0`, `PDFTextStripper` subclassed | text + per-run x/y/w/h |
| 3 | no usable text layer | `PdfRenderer` → bitmap @ 300 dpi → OCR | text + boxes + confidence |

Tier selection is per **page**, not per document. A text PDF with three scanned appendix pages
uses tier 2 for most of it and tier 3 for those three, producing `Partial` with a warning rather
than a whole-document failure.

### Getting coordinates out of PdfBox

`PDFTextStripper.getText()` returns a string — useless for table reconstruction, which needs
geometry ([08-extraction.md § Table reconstruction](08-extraction.md#table-reconstruction)). The
positions come from overriding the write hook:

```kotlin
class PositionedTextStripper : PDFTextStripper() {
    val runs = mutableListOf<TextRun>()

    override fun writeString(text: String, positions: List<TextPosition>) {
        if (positions.isEmpty()) return
        runs += TextRun(
            text = text,
            bounds = positions.bounds(),      // union of glyph boxes
            confidence = null,                // text layer: not a recognition result
            origin = TextOrigin.PdfTextLayer,
        )
    }
}
```

`TextPosition` gives `xDirAdj`, `yDirAdj`, `widthDirAdj`, `heightDir`, and font size per glyph.
The `DirAdj` variants are already rotation-corrected, which matters for landscape timetables.

### Detecting "no usable text layer"

Not simply "zero characters". A scanned page often carries a few stray characters from a header
stamp or a watermark. The test is a **density heuristic**: characters per page area, relative to
what a text page of that size would carry. Below the threshold, the page is treated as scanned.

The threshold is deliberately conservative — falsely routing a text page to OCR costs time;
falsely treating a scanned page as text produces four characters of garbage and an empty result.

### Memory and safety

| Control | Value |
|---|---|
| Pages loaded at once | 1 |
| Render resolution | 300 dpi, clamped so no bitmap exceeds 4096×4096 |
| Bitmap config | `RGB_565` for OCR input (ML Kit doesn't need alpha; halves the allocation) |
| Per-page timeout | 15 s |
| Whole-document timeout | 5 min |
| Page limit before asking | 100 — beyond that, offer page selection |
| File size ceiling | 100 MB |

`PdfBox-Android` requires `PDFBoxResourceLoader.init()` before use for font resources; this
happens once in the sandbox process, not the UI process.

### Encrypted PDFs

Detected at open. Reported as `Encrypted`. We do not prompt for a password in V1, and we never
attempt to bypass protection — not empty-password probes, not owner-password stripping. If the
user has an accessible copy, they can supply it.

### Known limits — stated, not hidden

- Vector-drawn tables (ruling lines with no text layer) reconstruct from text geometry only; the
  lines themselves are ignored.
- Rotated text within an otherwise upright page is captured with corrected coordinates but is not
  reassembled into rotated columns.
- Tagged-PDF structure (`/StructTreeRoot`) is not used. It would be more reliable than geometry
  where present — a good future improvement, and rare in institutional documents.

---

## Images

*Later — designed here, built after the PDF slice.*

### Pipeline

```
Decode (bounds first) ▸ Orientation ▸ Downsample ▸ [Crop] ▸ Preprocess ▸ OCR ▸ Layout
```

**Decode safely.** Always `inJustDecodeBounds = true` first, then compute `inSampleSize`. Never
allocate from declared dimensions — a 30000×30000 PNG is 3.6 GB at ARGB_8888 and is a trivially
constructed hostile input. Target the long edge at 2048–3072 px, which is well above what ML Kit
needs and far below any risk.

**Orientation.** EXIF via `ExifInterface`. Screenshots have no EXIF; camera photos frequently
carry rotation that is easy to forget and produces silently garbage OCR.

**Preprocess conservatively.** Grayscale, mild contrast normalisation. No binarisation, no
deskewing, no sharpening in V1 — ML Kit's models are trained on natural images and aggressive
preprocessing measurably *hurts* their accuracy. Preprocessing is a place where effort commonly
makes things worse.

**Crop is the strongest lever available.** A user-selected region improves accuracy, speed,
privacy, and structural clarity simultaneously. This is why rescue mode
([02-requirements.md § UC-4](02-requirements.md#uc-4-rescue-mode-later)) is a headline feature and
not a fallback: it converts a hard problem into an easy one using information only the user has.

---

## OCR

*Later.*

**ML Kit Text Recognition v2**, on-device. Verified 2026-08-10: it returns bounding boxes, corner
points, rotation, and **confidence** for blocks, lines, elements, and symbols — everything the
spatial requirement needs.

### Bundled vs unbundled

| | Bundled (`com.google.mlkit:text-recognition`) | Unbundled (`com.google.android.gms:play-services-mlkit-text-recognition:19.0.1`) |
|---|---|---|
| APK size | +~4 MB | negligible |
| First use | works immediately | may need a one-time model download |
| Devices | all | GMS only |

**Decision: unbundled**, with the download surfaced honestly as a one-time setup step if it hasn't
happened. The target market is GMS-device-dominant, and 4 MB is a real conversion cost on the
low-end devices this app must serve. If non-GMS support becomes necessary, the `OcrEngine` port
allows a bundled build variant without touching the pipeline.

### Never ML Kit types outside the adapter

ML Kit's `Text.Element` never crosses out of `:document:image`. The adapter converts to our
`TextRun`, so OCR is replaceable and the extraction engine stays JVM-testable
([05-architecture.md § Architecture review](05-architecture.md#architecture-review)).

### Confidence mapping

ML Kit element confidence maps to our levels per
[09-confidence.md](09-confidence.md#what-determines-the-level). Element-level confidence is used,
not block-level — a block averaging 0.9 can contain the one element that is 0.4, and that element
is usually the number that matters.

### OCR-specific normalisation

| Confusion | Handling |
|---|---|
| `O` ↔ `0`, `l`/`I` ↔ `1`, `S` ↔ `5`, `B` ↔ `8` | Corrected **only** in positions where the grammar demands a digit (inside a time or date pattern) |
| `1O:OO` → `10:00` | `OcrCharacterSubstitution`, caps at `Medium` |
| `OCTOBER` → `October` | `CaseNormalisation`, no confidence impact |
| `—`/`–`/`~` as range separators | `SeparatorNormalisation` |

Substitution is never applied to a subject name or a room code. `K1O` could legitimately be either
`K10` or `K1O`, and we have no grammar to decide — so the raw value is kept and shown.

---

## Spreadsheets

*Later.*

### Custom streaming reader, not Apache POI

[ADR-009](05-architecture.md#adr-009--custom-streaming-ooxml-reader-instead-of-apache-poi) has the
reasoning: POI assumes a StAX implementation Android doesn't provide, carries a large dex
footprint, and `XSSFWorkbook` loads whole workbooks into memory.

The reader uses `java.util.zip` plus `XmlPullParser` (platform built-in) and streams:

| Part | Read as |
|---|---|
| `xl/workbook.xml` | Sheet names, ids, visibility |
| `xl/sharedStrings.xml` | Streamed; strings resolved on demand, not held whole |
| `xl/styles.xml` | `numFmt` ids — the only way to know a number is a date |
| `xl/worksheets/sheetN.xml` | Streamed row by row, cells emitted as events |

Scope is narrow by design: values, positions, merges, and enough number-format handling to
recognise dates. No formula evaluation, no charts, no writing.

### The layout problem

**Row 1 is not the header.** Real institutional workbooks contain: a college name in row 1, a logo
image floating over rows 1–3, a merged title in row 4, blank spacer rows, several stacked tables
per sheet, section headings mid-sheet, hidden rows and sheets, and formatting-only cells. The
header row is found by content — the row carrying period times — never by position.

**The grid is not inferred.** Early on the reader fed synthesised coordinates into the PDF's
geometric table detection. That lost period columns which were populated only in the header row
([ADR-011](05-architecture.md#adr-011--spreadsheets-build-the-grid-directly-only-pdfs-infer-it)).
Sheet coordinates are exact and are used directly; only PDFs and images infer structure.

### Stacked sections

One sheet routinely holds many schedules — a verified real export contains **335 section blocks
across three semesters**. The splitter is structural rather than tuned to any institution: runs of
consecutive weekday rows are schedules, and whatever non-weekday row precedes a run is its label.
Nothing in the code knows what "Sem 3" means.

Detection is separated from extraction, so listing hundreds of options is cheap and only the
chosen block goes through the full engine.

### Specific handling

| Feature | Behaviour |
|---|---|
| Merged cells | Read from `mergeCells`; value applies to the whole span |
| Hidden rows/columns | Read but marked; usually excluded, surfaced if they change the result |
| Hidden sheets | Listed in the schedule picker with a "hidden" note, never silently included |
| Formulas | Cached value used; formula text ignored; no evaluation |
| Dates | Serial numbers converted via `numFmt`, including the 1900 leap-year bug and the 1904 date system |
| Multiple tables per sheet | Segmented by blank bands, same as PDF regions |
| Images | Ignored — no OCR of embedded images in V1 |

### Safety limits

Spreadsheets are the most hostile format here — ZIP plus XML is two attack surfaces.

| Control | Limit |
|---|---|
| Compression ratio | Reject above 100:1 (ZIP bomb) |
| Total uncompressed size | 200 MB |
| Entry count | 10 000 |
| Entry name validation | Reject `..`, absolute paths, backslashes — names are never used as filesystem paths |
| XML entity expansion | Disabled (`XmlPullParser` doesn't expand external entities by default; asserted explicitly) |
| Cells per sheet | 1 000 000, then stop and report |
| Parse timeout | 60 s |

### Oversized workbooks

Rather than failing: *"This spreadsheet is too large to process all at once."* → choose a sheet,
choose a range, or screenshot the relevant area. Same philosophy as rescue mode — narrow the
problem with the user's help.

---

## CSV

*Later.* The simplest format and still not trivial.

| Concern | Handling |
|---|---|
| Delimiter | Detected by consistency of field counts across sample lines — comma, semicolon, tab, pipe |
| Encoding | BOM first; then UTF-8 validity; then a Windows-1252 fallback |
| Line endings | All three |
| Quoting | RFC 4180, including embedded newlines and doubled quotes |
| Header | Detected by content, not assumed to be line 1 |
| Ragged rows | Preserved as ragged; not padded, not dropped |
| Size | Streamed; never fully materialised |

Semicolon delimiters matter more than they look: European Excel exports use them by default, and
misdetecting one produces a single-column file that fails silently.

---

## Plain text

*Later.* Pasted or shared text. No geometry, so table reconstruction is unavailable — only
free-text entity extraction. Suitable for a deadline or a single appointment; explicitly not
suitable for a timetable, and the UI says so rather than producing a poor result.

---

## What is supported — the honest statement

For the Play listing and the in-app help. Overclaiming here converts a satisfied user into a
1-star review ([01-product.md § Positioning](01-product.md#positioning)).

**Works well:**
- PDFs with a text layer containing tabular schedules
- XLSX exports where weekdays and periods form a grid — including a single sheet holding
  hundreds of stacked sections, which are detected and offered as a choice
- Clear screenshots and photos of printed timetables *(when the image path ships)*
- Well-formed CSV *(when it ships)*

**Works sometimes:**
- Scanned PDFs — depends on scan quality
- Complex multi-section layouts — often needs the section picker
- Photos taken at an angle or in poor light

**Does not work:**
- Handwritten schedules
- Password-protected documents
- Documents with no dates or times
- Timetables presented purely as images inside a spreadsheet
- Non-Latin scripts in V1
