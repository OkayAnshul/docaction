package com.okayanshul.docaction.extraction

import com.okayanshul.docaction.domain.BoundingBox
import com.okayanshul.docaction.domain.DocumentFormat
import com.okayanshul.docaction.domain.ExtractionHints
import com.okayanshul.docaction.domain.DocumentContent
import com.okayanshul.docaction.domain.PageContent
import com.okayanshul.docaction.domain.FoundSchedules
import com.okayanshul.docaction.domain.PipelineAnswers
import com.okayanshul.docaction.domain.PipelineQuestion
import com.okayanshul.docaction.domain.ScheduleFinder
import com.okayanshul.docaction.domain.ScheduleGroup
import com.okayanshul.docaction.domain.SourceReference
import com.okayanshul.docaction.domain.TextOrigin
import com.okayanshul.docaction.extraction.prose.ProseExtractor
import com.okayanshul.docaction.extraction.table.Cell
import com.okayanshul.docaction.extraction.table.TableBuilder
import com.okayanshul.docaction.extraction.timetable.CellContent
import com.okayanshul.docaction.extraction.timetable.DatedTableBuilder
import com.okayanshul.docaction.extraction.timetable.TimetableBuilder

/**
 * Finds schedules in a document: a grid if there is one, otherwise prose.
 *
 * This is the sequence already validated across the 41-document corpus — it was previously
 * written out inside `CorpusDiagnostic` and is promoted here so production and the
 * regression harness run the *same* code rather than two copies that can drift.
 *
 * Grid first, because a table carries structure worth using. Prose second, because a notice
 * or a booking confirmation has no grid and would otherwise be a dead end. A page never
 * gets both: the grid result wins where it exists.
 */
class EngineScheduleFinder(
    private val tables: TableBuilder = TableBuilder(),
    private val timetables: TimetableBuilder = TimetableBuilder(),
    private val dated: DatedTableBuilder = DatedTableBuilder(),
    private val prose: ProseExtractor = ProseExtractor(),
    /** Named `cells`, not `content`: `find` already has a DocumentContent by that name. */
    private val cells: CellContent = CellContent(),
    /** Long documents are sampled rather than fully walked; a schedule is rarely on page 40. */
    private val maxPages: Int = 12,
) : ScheduleFinder {

    override suspend fun find(
        content: DocumentContent,
        label: String,
        answers: PipelineAnswers,
        @Suppress("UNUSED_PARAMETER") hints: ExtractionHints,
    ): FoundSchedules {
        val found = mutableListOf<ScheduleGroup>()
        val questions = mutableListOf<PipelineQuestion>()
        // A photo is one image with no page number; a scanned PDF has pages worth naming.
        val paged = content.format != DocumentFormat.Image
        // Every page is looked at; only the most schedule-like are read in full.
        val pages = mostSchedulelike(content.pages)

        // Plain text has no geometry. Its "coordinates" are one line per row, invented to
        // satisfy the TextRun contract, and handing those to table reconstruction is exactly
        // the mistake ADR-011 exists to prevent — a real timetable once lost three period
        // columns to synthesised positions. Prose only, and the routing lives here rather
        // than in the reader so no future reader can forget it.
        if (content.format == DocumentFormat.PlainText) {
            val groups = pages
                .mapNotNull { proseSchedule(it, label, answers, paged = false) }
            return FoundSchedules(distinguish(groups))
        }

        pages.forEach { page ->
            gridSchedule(page, label, paged)?.let { found += it }
        }

        // A table of dated occasions — an interview letter's rounds, a lab appointment, an
        // exam schedule. Tried after the weekly reading and before prose, because these are
        // tables and reading a table line by line is what produced titles like
        // "HR :30 Conference" from a row spread over three physical lines.
        if (found.isEmpty()) {
            pages.forEach { page ->
                val result = datedSchedule(page, label, answers, paged)
                result.group?.let { found += it }
                // Carried, not discarded: an ambiguous date is the reason this page produced
                // nothing, and the user is the only one who can settle it.
                result.ambiguous?.let {
                    questions += PipelineQuestion.DateOrder(it.example, it.dayFirst, it.monthFirst)
                }
            }
        }

        // Only fall back to prose when neither table reading worked. Running both would
        // produce the same dates twice — once as a table row, once as a sentence.
        if (found.isEmpty()) {
            pages.forEach { page ->
                proseSchedule(page, label, answers, paged)?.let { found += it }
            }
        }

        return FoundSchedules(
            groups = distinguish(found),
            questions = questions.distinctBy { (it as PipelineQuestion.DateOrder).example },
        )
    }

    /**
     * The pages worth reading in full, cheapest evidence first.
     *
     * `take(maxPages)` was taking the *first* twelve, which on a 38-page central timetable
     * means reading the cover, the index and the preamble and stopping before any timetable.
     * Seven of the ten largest documents in the corpus exceed the cap, so this was not a rare
     * case. Counting date-, time- and weekday-shaped runs is far cheaper than building a grid,
     * so every page can be looked at and only the promising ones read properly.
     *
     * Page order is restored afterwards: the readers below label schedules by page, and
     * handing them pages out of order would make "page 3" mean the third one we happened to
     * like.
     */
    private fun mostSchedulelike(pages: List<PageContent>): List<PageContent> {
        if (pages.size <= maxPages) return pages
        return pages
            .sortedByDescending { page -> scheduleEvidence(page) }
            .take(maxPages)
            .sortedBy { it.index }
    }

    private fun scheduleEvidence(page: PageContent): Int = page.runs.count { run ->
        val text = run.text
        cells.looksLikeTime(text) || cells.looksLikeWeekday(text) || cells.looksLikeDate(text)
    }

    /**
     * Makes several schedules from one document tellable apart.
     *
     * A 38-page central timetable yields a schedule per page, and every one of them would
     * otherwise carry the filename as its label — asking the user to choose between twelve
     * identical options, which is worse than not asking. Where the engine could not name a
     * schedule from its own heading, the page number is the honest distinguishing fact.
     */
    private fun distinguish(groups: List<ScheduleGroup>): List<ScheduleGroup> {
        if (groups.size < 2) return groups
        if (groups.map { it.label }.distinct().size == groups.size) return groups

        return groups.mapIndexed { index, group ->
            val page = (group.source as? SourceReference.PdfSpan)?.page
            val suffix = if (page != null) "page ${page + 1}" else "part ${index + 1}"
            group.copy(
                id = com.okayanshul.docaction.domain.GroupId("${group.id.value}#$index"),
                label = "${group.label} · $suffix",
            )
        }
    }

    private fun gridSchedule(page: PageContent, label: String, paged: Boolean): ScheduleGroup? {
        val grid = tables.build(page.runs) ?: return null
        val result = timetables.build(grid, label) { cell -> sourceFor(page, cell, paged) }
        return result.group?.takeIf { it.entries.isNotEmpty() }
    }

    private fun datedSchedule(
        page: PageContent,
        label: String,
        answers: PipelineAnswers,
        paged: Boolean,
    ): DatedTableBuilder.Result {
        val grid = tables.build(page.runs) ?: return DatedTableBuilder.Result(null)
        val result = dated.build(grid, label, answers.assumedYear, answers.dateOrder) { cell ->
            sourceFor(page, cell, paged)
        }
        return result.copy(group = result.group?.takeIf { it.entries.isNotEmpty() })
    }

    private fun proseSchedule(
        page: PageContent,
        label: String,
        answers: PipelineAnswers,
        paged: Boolean,
    ): ScheduleGroup? {
        val result = prose.extract(
            runs = page.runs,
            label = label,
            sourceOf = { line -> page.reference(line.bounds, paged) },
            assumedYear = answers.assumedYear,
        )
        return result.group?.takeIf { it.entries.isNotEmpty() }
    }

    private fun sourceFor(page: PageContent, cell: Cell, paged: Boolean): SourceReference =
        page.reference(cell.bounds ?: BoundingBox(0f, 0f, 0f, 0f), paged)

    /**
     * Where something on this page came from, in terms Source View can render.
     *
     * A page read by recognition is an image region; a page with a text layer is a span on
     * a numbered page. Either way the box is converted to a fraction of the page here —
     * this is the boundary between extraction's working units and a reference meant for a
     * human, and the only place the conversion happens.
     */
    private fun PageContent.reference(bounds: BoundingBox, paged: Boolean): SourceReference {
        val fraction = bounds.fractionOf(widthPt, heightPt)
        return if (isRecognised) {
            SourceReference.ImageRegion(fraction, page = index.takeIf { paged })
        } else {
            SourceReference.PdfSpan(index, fraction)
        }
    }

    /** True when this page's text came from recognition rather than a text layer. */
    private val PageContent.isRecognised: Boolean
        get() = runs.isNotEmpty() && runs.first().origin == TextOrigin.Ocr
}
