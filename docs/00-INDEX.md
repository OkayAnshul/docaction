# DocAction Documentation Index

> **Turn documents into actions.**

## How this documentation is organised

The build specification requested 93 separate files. Many of those topics are two paragraphs
long and only make sense next to their neighbours — `61-memory-management.md` cannot be read
usefully apart from `60-performance.md`, and `35-confidence-engine.md` is meaningless without
`36-validation-engine.md`.

So the content is consolidated into 16 documents. **Nothing was dropped.** The table below maps
every requested filename to the document and section that covers it. If you were looking for a
specific numbered file from the spec, find it here.

---

## The documents

| File | Covers |
|---|---|
| [01-product.md](01-product.md) | Vision, market, competitors, users, positioning, principles |
| [02-requirements.md](02-requirements.md) | Requirements, use cases, user flows, edge cases |
| [03-ux.md](03-ux.md) | Information architecture, UX principles, screen specs, interaction, accessibility, motion |
| [04-design-system.md](04-design-system.md) | Tokens, typography, colour, components, theming |
| [05-architecture.md](05-architecture.md) | System + module architecture, dependency rules, ADRs |
| [06-data-model.md](06-data-model.md) | Domain model, persistence schema |
| [07-pipeline.md](07-pipeline.md) | The document pipeline, stage by stage |
| [08-extraction.md](08-extraction.md) | Date, time, location, table, timetable engines |
| [09-confidence.md](09-confidence.md) | Confidence, validation, hallucination prevention, human-in-the-loop, AI policy |
| [10-formats.md](10-formats.md) | PDF, image, OCR, spreadsheet, CSV, table detection |
| [11-actions.md](11-actions.md) | Action engine, calendar, reminders, duplicates, undo |
| [12-privacy-security.md](12-privacy-security.md) | Privacy, security, offline-first, threat model |
| [13-performance.md](13-performance.md) | Performance, memory, battery, error handling |
| [14-testing.md](14-testing.md) | Test strategy, matrix, regression corpus, device coverage |
| *15-business.md* | Monetization, pricing, analytics, ASO, Play listing, roadmap, risks. **Not published** — kept private. |

---

## Requested filename → where it lives

### Product (00–05)

| Requested | Location |
|---|---|
| `00-product-vision.md` | [01-product.md § Vision](01-product.md#vision) |
| `01-market-research.md` | [01-product.md § Market](01-product.md#market) |
| `02-competitive-analysis.md` | [01-product.md § Competitive landscape](01-product.md#competitive-landscape) |
| `03-user-research.md` | [01-product.md § Users](01-product.md#users) |
| `04-product-positioning.md` | [01-product.md § Positioning](01-product.md#positioning) |
| `05-product-principles.md` | [01-product.md § Principles](01-product.md#principles) |

### Requirements (10–13)

| Requested | Location |
|---|---|
| `10-requirements.md` | [02-requirements.md § Functional requirements](02-requirements.md#functional-requirements) |
| `11-use-cases.md` | [02-requirements.md § Use cases](02-requirements.md#use-cases) |
| `12-user-flows.md` | [02-requirements.md § User flows](02-requirements.md#user-flows) |
| `13-edge-cases.md` | [02-requirements.md § Edge cases](02-requirements.md#edge-cases) |

### Experience (20–26)

| Requested | Location |
|---|---|
| `20-information-architecture.md` | [03-ux.md § Information architecture](03-ux.md#information-architecture) |
| `21-ux-principles.md` | [03-ux.md § UX principles](03-ux.md#ux-principles) |
| `22-ui-design-system.md` | [04-design-system.md](04-design-system.md) — entire document |
| `23-screen-specifications.md` | [03-ux.md § Screen specifications](03-ux.md#screen-specifications) |
| `24-interaction-design.md` | [03-ux.md § Interaction design](03-ux.md#interaction-design) |
| `25-accessibility.md` | [03-ux.md § Accessibility](03-ux.md#accessibility) |
| `26-motion-design.md` | [03-ux.md § Motion](03-ux.md#motion) |

### Architecture (30–39)

| Requested | Location |
|---|---|
| `30-system-architecture.md` | [05-architecture.md § System architecture](05-architecture.md#system-architecture) |
| `31-module-architecture.md` | [05-architecture.md § Module architecture](05-architecture.md#module-architecture) |
| `32-data-model.md` | [06-data-model.md](06-data-model.md) — entire document |
| `33-document-pipeline.md` | [07-pipeline.md](07-pipeline.md) — entire document |
| `34-extraction-engine.md` | [08-extraction.md](08-extraction.md) — entire document |
| `35-confidence-engine.md` | [09-confidence.md § Confidence](09-confidence.md#confidence) |
| `36-validation-engine.md` | [09-confidence.md § Validation](09-confidence.md#validation) |
| `37-action-engine.md` | [11-actions.md § Action engine](11-actions.md#action-engine) |
| `38-calendar-integration.md` | [11-actions.md § Calendar](11-actions.md#calendar-integration) |
| `39-reminder-integration.md` | [11-actions.md § Reminders](11-actions.md#reminders) |

### Formats (40–45)

| Requested | Location |
|---|---|
| `40-pdf-processing.md` | [10-formats.md § PDF](10-formats.md#pdf) |
| `41-image-processing.md` | [10-formats.md § Images](10-formats.md#images) |
| `42-ocr.md` | [10-formats.md § OCR](10-formats.md#ocr) |
| `43-spreadsheet-processing.md` | [10-formats.md § Spreadsheets](10-formats.md#spreadsheets) |
| `44-csv-processing.md` | [10-formats.md § CSV](10-formats.md#csv) |
| `45-table-detection.md` | [08-extraction.md § Table reconstruction](08-extraction.md#table-reconstruction) |

### Intelligence & trust (50–55)

| Requested | Location |
|---|---|
| `50-ai-strategy.md` | [09-confidence.md § AI policy](09-confidence.md#ai-policy) |
| `51-hallucination-prevention.md` | [09-confidence.md § Hallucination prevention](09-confidence.md#hallucination-prevention) |
| `52-human-in-the-loop.md` | [09-confidence.md § Human in the loop](09-confidence.md#human-in-the-loop) |
| `53-offline-first.md` | [12-privacy-security.md § Offline-first](12-privacy-security.md#offline-first) |
| `54-privacy.md` | [12-privacy-security.md § Privacy](12-privacy-security.md#privacy) |
| `55-security.md` | [12-privacy-security.md § Security](12-privacy-security.md#security) |

### Runtime quality (60–63)

| Requested | Location |
|---|---|
| `60-performance.md` | [13-performance.md § Performance](13-performance.md#performance) |
| `61-memory-management.md` | [13-performance.md § Memory](13-performance.md#memory) |
| `62-battery.md` | [13-performance.md § Battery](13-performance.md#battery) |
| `63-error-handling.md` | [13-performance.md § Error handling](13-performance.md#error-handling) |

### Testing (70–73)

| Requested | Location |
|---|---|
| `70-testing-strategy.md` | [14-testing.md § Strategy](14-testing.md#strategy) |
| `71-test-matrix.md` | [14-testing.md § Test matrix](14-testing.md#test-matrix) |
| `72-document-regression-suite.md` | [14-testing.md § Regression corpus](14-testing.md#regression-corpus) |
| `73-device-compatibility.md` | [14-testing.md § Device coverage](14-testing.md#device-coverage) |

### Business (80–84)

| Requested | Location |
|---|---|
| `80-monetization.md` | *15-business.md § Monetization (not published)* |
| `81-pricing.md` | *15-business.md § Pricing (not published)* |
| `82-analytics.md` | *15-business.md § Analytics (not published)* |
| `83-aso.md` | *15-business.md § ASO (not published)* |
| `84-play-store.md` | *15-business.md § Play Store listing (not published)* |

### Delivery (90–93)

| Requested | Location |
|---|---|
| `90-roadmap.md` | *15-business.md § Roadmap (not published)* |
| `91-release-plan.md` | *15-business.md § Release plan (not published)* |
| `92-risk-register.md` | *15-business.md § Risk register (not published)* |
| `93-architecture-decisions.md` | [05-architecture.md § Decision records](05-architecture.md#decision-records) |

---

## Reading order

**If you are implementing:** 05 → 06 → 07 → 08 → 09 → 10 → 11.

**If you are designing:** 01 → 03 → 04.

**If you are reviewing whether this product should exist:** 01 → 15.

**If you only read one document:** [09-confidence.md](09-confidence.md). It contains the rule
the whole product is built to enforce — *automatic when confident, guided when uncertain, never
silently wrong* — and explains why that rule is encoded in the type system rather than trusted
to developer discipline.

---

## Document status

| Date | Change |
|---|---|
| 2026-08-10 | Initial documentation set written before implementation. |

All external facts (API availability, Play policy dates, library versions) were verified against
primary sources on **2026-08-10** and are cited inline where they influenced a decision. They
have a shelf life. Re-verify anything load-bearing before acting on it in a later quarter.
