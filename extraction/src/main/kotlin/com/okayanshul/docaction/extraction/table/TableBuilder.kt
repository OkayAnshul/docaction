package com.okayanshul.docaction.extraction.table

import com.okayanshul.docaction.domain.TextRun

/**
 * Reconstructs tabular structure from positioned text.
 *
 * Text order is not reading order. A PDF content stream emits text in whatever order the
 * producer wrote it, and OCR emits it in detection order — a two-column timetable read in
 * stream order interleaves Monday and Tuesday into nonsense. Geometry is the only
 * reliable signal, so every decision here comes from coordinates.
 *
 * Every tolerance is a fraction of the median run height rather than a fixed number of
 * pixels, which is what lets the same code handle a 72-dpi PDF and a 300-dpi scan.
 *
 * See docs/08-extraction.md § Table reconstruction.
 */
class TableBuilder(
    private val lineTolerance: Float = 0.6f,
    private val gutterWidth: Float = 0.4f,
    /** A band still counts as a gutter if at most this fraction of lines crosses it. */
    private val gutterLineFraction: Float = 0.25f,
    /**
     * A gutter may always be crossed by this many lines, however short the document.
     *
     * A fraction alone is wrong for the common shape of a letter: a title, a greeting, a
     * small table, and a footnote. Ten lines gives a ceiling of two, four prose lines cross
     * the table's gutter, and two real columns merge — which is why a lab appointment's
     * `Test | Date` header arrived as one cell reading "Test Date", and why the row beneath
     * it read "MRI Brain 22/09/ (Contrast) 2026".
     *
     * The floor is what makes surrounding prose survivable. It is deliberately small: the
     * fraction still governs anything long enough for a fraction to mean something.
     */
    private val gutterLineFloor: Int = 4,
    /** A run wider than its column by this factor is treated as a merged cell. */
    private val spanningCellFactor: Float = 1.2f,
    /**
     * How quiet a band must be, relative to the busiest one, to count as a gutter.
     *
     * Requiring silence — no ordinary cell text at all — is right for a clean table and
     * impossible for a real one. Columns are narrow, their contents are not, and a subject
     * that overruns its column by a few pixels erases that gutter for the whole document. On
     * a screenshot of a college timetable this left six columns where there were nine, so two
     * period headings shared a cell (`10:30 TO 11:20 TO`) and a weekday was glued to the
     * first subject beside it. Nothing downstream can recover from that: the period row can
     * no longer be read, and the timetable is refused.
     *
     * A gutter is really a *valley* in the occupancy profile, not a zero, so it is measured
     * against the busiest band instead. The absolute rule survives where it was right: on a
     * sparse table the threshold falls below one, which means zero, exactly as before. Only a
     * page dense enough for a fraction to be meaningful is treated more loosely.
     */
    private val gutterQuietFraction: Float = 0.15f,
) {

    fun build(runs: List<TextRun>): Grid? {
        val usable = runs.filter { it.text.isNotBlank() }
        if (usable.isEmpty()) return null

        val unit = medianHeight(usable)
        if (unit <= 0f) return null

        val lines = clusterIntoLines(usable, unit)
        if (lines.isEmpty()) return null

        val columns = detectColumns(lines, unit)
        if (columns.isEmpty()) return null

        return assign(groupIntoRows(lines, unit), columns)
    }

    /**
     * Merges consecutive text lines that belong to the same table row.
     *
     * **A table row is not a text line.** Real timetables have narrow columns, so cell
     * content wraps: `09:00-10:00` becomes `09:00-` above `10:00`, and `Data Structures /
     * K10` splits across two lines. Treating each visual line as a row shatters every cell
     * in the table — which is exactly what happened on the first real PDF this was pointed
     * at.
     *
     * The signal is vertical spacing. Wrapped lines inside one cell sit a single leading
     * apart; separate rows are pushed apart by cell padding and borders. Those two
     * populations are strongly bimodal, so the split point is found by looking for the
     * widest jump in the sorted gaps rather than by hard-coding a threshold that would
     * only suit one font size.
     */
    internal fun groupIntoRows(lines: List<TextLine>, unit: Float): List<List<TextLine>> {
        if (lines.size < 2) return lines.map { listOf(it) }

        val gaps = lines.zipWithNext { above, below ->
            (below.bounds.top - above.bounds.bottom).coerceAtLeast(0f)
        }
        val threshold = rowBreakThreshold(gaps, unit) ?: return lines.map { listOf(it) }

        val rows = mutableListOf(mutableListOf(lines.first()))
        lines.drop(1).forEachIndexed { index, line ->
            if (gaps[index] > threshold) rows += mutableListOf(line) else rows.last() += line
        }
        return rows
    }

    /**
     * Splits the gaps into two clusters at the widest jump, and returns the midpoint —
     * or null when the gaps are one uniform population, meaning nothing wrapped and every
     * line is its own row.
     *
     * **Outliers are trimmed before the search.** A page almost always contains one or two
     * enormous gaps — title to table, table to footer — and searching the raw distribution
     * finds *those* as the widest jump, producing a threshold so high that every real row
     * merges together. A real college timetable collapsed from 36 lines to 2 rows exactly
     * this way. Only the body of the distribution describes row spacing.
     */
    internal fun rowBreakThreshold(gaps: List<Float>, unit: Float): Float? {
        if (gaps.size < 3) return null
        val sorted = gaps.sorted()

        // A split is only believable when the cluster *below* it is tight. Page furniture —
        // the gap from a title down to the table, or from the table to a footer — also
        // produces a wide jump, but everything beneath such a split is a jumble of row
        // spacings rather than one consistent within-cell spacing. Requiring a tight lower
        // cluster rejects those splits without needing to guess which gaps are furniture.
        //
        // Getting this wrong is not symmetric: over-merging fuses two classes into one row
        // and produces confidently wrong times, while under-merging splits a cell and the
        // entry is rejected for a missing end time. The first is the failure this product
        // exists to prevent, so the test is deliberately strict.
        var bestSplit = -1
        var bestJump = 0f

        for (i in 0 until sorted.size - 1) {
            val jump = sorted[i + 1] - sorted[i]
            val lowerSpread = sorted[i] - sorted[0]
            val required = maxOf(unit * MIN_ROW_SEPARATION, lowerSpread * SPREAD_MULTIPLE)
            if (jump >= required && jump > bestJump) {
                bestJump = jump
                bestSplit = i
            }
        }

        if (bestSplit < 0) return null
        return (sorted[bestSplit] + sorted[bestSplit + 1]) / 2f
    }

    /** The scale everything else is measured against. */
    internal fun medianHeight(runs: List<TextRun>): Float =
        runs.map { it.bounds.height }.filter { it > 0f }.sorted().let { heights ->
            when {
                heights.isEmpty() -> 0f
                heights.size % 2 == 1 -> heights[heights.size / 2]
                else -> (heights[heights.size / 2 - 1] + heights[heights.size / 2]) / 2f
            }
        }

    /**
     * Groups runs whose vertical centres agree. Superscripts and subscripts merge into
     * their base line; a genuinely new line does not.
     */
    internal fun clusterIntoLines(runs: List<TextRun>, unit: Float): List<TextLine> {
        val tolerance = unit * lineTolerance
        val sorted = runs.sortedBy { it.bounds.centerY }

        val groups = mutableListOf<MutableList<TextRun>>()
        var anchor = Float.NaN

        for (run in sorted) {
            val center = run.bounds.centerY
            if (groups.isEmpty() || kotlin.math.abs(center - anchor) > tolerance) {
                groups += mutableListOf(run)
                anchor = center
            } else {
                groups.last() += run
                // Track the running centre so a slowly drifting line stays one line.
                anchor = groups.last().sumOf { it.bounds.centerY.toDouble() }.toFloat() / groups.last().size
            }
        }

        return groups.mapIndexed { index, group -> TextLine(index, group.sortedBy { it.bounds.left }) }
    }

    /**
     * Finds column boundaries by counting, for each vertical band, how many *lines* place
     * any text there.
     *
     * Counting lines rather than runs is what makes merged cells survive: a gap appearing
     * in one line is a word space, a gap appearing across most lines is a column
     * boundary, and a single merged header spanning the whole table does not erase the
     * gutters beneath it.
     */
    internal fun detectColumns(lines: List<TextLine>, unit: Float): List<ColumnSpan> {
        val allRuns = lines.flatMap { it.runs }
        if (allRuns.isEmpty()) return emptyList()

        val left = allRuns.minOf { it.bounds.left }
        val right = allRuns.maxOf { it.bounds.right }
        if (right <= left) return emptyList()

        val bucket = (unit * 0.25f).coerceAtLeast(0.5f)
        val bucketCount = (((right - left) / bucket).toInt() + 1).coerceAtMost(MAX_BUCKETS)

        // A merged header spanning the table and a column populated in only one line look
        // identical if you just count lines. They are told apart by run width: a spanning
        // run is much wider than typical, an ordinary cell is not. Only spanning runs are
        // allowed to sit over a gutter.
        val medianWidth = allRuns.map { it.bounds.width }.sorted()[allRuns.size / 2]
        val spanningWidth = medianWidth * spanningCellFactor

        val linesWithCellText = IntArray(bucketCount)
        val linesWithSpanningText = IntArray(bucketCount)

        for (line in lines) {
            val cellCovered = BooleanArray(bucketCount)
            val spanningCovered = BooleanArray(bucketCount)
            for (run in line.runs) {
                val from = (((run.bounds.left - left) / bucket).toInt()).coerceIn(0, bucketCount - 1)
                val to = (((run.bounds.right - left) / bucket).toInt()).coerceIn(0, bucketCount - 1)
                val target = if (run.bounds.width > spanningWidth) spanningCovered else cellCovered
                for (i in from..to) target[i] = true
            }
            for (i in 0 until bucketCount) {
                if (cellCovered[i]) linesWithCellText[i]++
                if (spanningCovered[i]) linesWithSpanningText[i]++
            }
        }

        val gutterCeiling = maxOf((lines.size * gutterLineFraction).toInt(), gutterLineFloor)
        val minGutterBuckets = ((unit * gutterWidth) / bucket).toInt().coerceAtLeast(1)

        val spans = mutableListOf<Pair<Float, Float>>()
        var spanStart: Int? = null
        var gap = 0

        // Measured against the busiest band, so "quiet" means quiet *for this page*. Where the
        // page is sparse this rounds below one and the test is `== 0`, which is what it always
        // was — the relaxation only reaches documents dense enough to need it.
        val busiest = linesWithCellText.maxOrNull() ?: 0
        val quiet = busiest * gutterQuietFraction

        for (i in 0 until bucketCount) {
            // Ordinary cell text well above the page's quiet level means this is a column,
            // however rarely it occurs.
            val isGutter = linesWithCellText[i] <= quiet && linesWithSpanningText[i] <= gutterCeiling
            if (!isGutter) {
                if (spanStart == null) spanStart = i
                gap = 0
            } else {
                gap++
                val start = spanStart
                if (start != null && gap >= minGutterBuckets) {
                    spans += left + start * bucket to left + (i - gap + 1) * bucket
                    spanStart = null
                }
            }
        }
        spanStart?.let { spans += left + it * bucket to right }

        return spans.mapIndexed { index, (from, to) -> ColumnSpan(index, from, to) }
    }

    private fun assign(rows: List<List<TextLine>>, columns: List<ColumnSpan>): Grid {
        val cells = mutableListOf<Cell>()

        rows.forEachIndexed { rowIndex, linesInRow ->
            val buckets = mutableMapOf<Int, MutableList<TextRun>>()
            val spanning = mutableSetOf<Int>()

            linesInRow.flatMap { it.runs }.forEach { run ->
                val index = columnFor(run.bounds.centerX, columns)
                buckets.getOrPut(index) { mutableListOf() } += run
                // A run wider than its column reaches into a neighbour — a merged cell.
                val columnWidth = columns[index].right - columns[index].left
                if (run.bounds.width > columnWidth * spanningCellFactor) spanning += index
            }

            buckets.forEach { (column, runs) ->
                cells += Cell(rowIndex, column, runs, column in spanning)
            }
        }

        return Grid(rowCount = rows.size, columns = columns, cells = cells)
    }

    /** Nearest column by centre, so a run sitting slightly outside a span still lands. */
    private fun columnFor(x: Float, columns: List<ColumnSpan>): Int {
        columns.firstOrNull { it.contains(x) }?.let { return it.index }
        return columns.minBy { kotlin.math.abs(it.center - x) }.index
    }

    private companion object {
        /** Guards against a pathological page reporting enormous dimensions. */
        const val MAX_BUCKETS = 20_000

        /** A row break must clear wrapped-line spacing by at least this much text-height. */
        const val MIN_ROW_SEPARATION = 0.15f

        /** ...and must be this many times wider than the spread of the cluster below it. */
        const val SPREAD_MULTIPLE = 3f
    }
}
