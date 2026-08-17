package com.okayanshul.docaction.document.spreadsheet

/**
 * One schedule found inside a sheet, with the rows it occupies.
 *
 * [label] is the text of the row that introduced it — `Sem 3 | CS-S3 | CS1` — which is
 * what the user picks from.
 */
data class SectionBlock(
    val label: String,
    val headerRow: Int,
    val firstDataRow: Int,
    val lastDataRow: Int,
) {
    val dataRows: IntRange get() = firstDataRow..lastDataRow
}

/**
 * Finds the separate schedules stacked inside one sheet.
 *
 * Institutional exports repeat a small structure many times: a label row naming the
 * section, then a handful of day rows, then the next section. A real example runs to 335
 * blocks across three semesters in a single sheet — importing all of them would be
 * absurd, so they are detected and offered as a choice.
 *
 * The rule is deliberately structural rather than pattern-matched to any one institution:
 * **runs of consecutive day rows are schedules, and whatever non-day row precedes a run
 * is its label.** Nothing here knows what "Sem 3" or "course group(s)" means.
 */
class SectionSplitter(private val isDayRow: (List<String>) -> Boolean) {

    fun split(sheet: SheetGrid, headerRow: Int): List<SectionBlock> {
        val blocks = mutableListOf<SectionBlock>()

        var runStart = -1
        var pendingLabel: String? = null
        var labelRow = headerRow

        for (row in (headerRow + 1) until sheet.rowCount) {
            val values = sheet.row(row)

            if (isDayRow(values)) {
                if (runStart < 0) runStart = row
                continue
            }

            // A non-day row closes any run in progress.
            if (runStart >= 0) {
                blocks += SectionBlock(
                    label = pendingLabel ?: "Schedule ${blocks.size + 1}",
                    headerRow = labelRow,
                    firstDataRow = runStart,
                    lastDataRow = row - 1,
                )
                runStart = -1
            }

            // ...and, if it has text, becomes the label for the next one.
            val text = values.filter { it.isNotBlank() }
            if (text.isNotEmpty()) {
                pendingLabel = text.joinToString(" · ") { it.replace('\n', ' ').trim() }
                labelRow = row
            }
        }

        if (runStart >= 0) {
            blocks += SectionBlock(
                label = pendingLabel ?: "Schedule ${blocks.size + 1}",
                headerRow = labelRow,
                firstDataRow = runStart,
                lastDataRow = sheet.rowCount - 1,
            )
        }

        return blocks
    }
}
