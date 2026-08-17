package com.okayanshul.docaction.document.spreadsheet

/** One cell, with its true position in the sheet. */
data class SheetCell(val row: Int, val column: Int, val text: String)

/**
 * A worksheet as a sparse cell map.
 *
 * The decisive advantage over the PDF path: **the grid is given, not inferred.** Column C
 * is column C whether or not anything is in it. All the geometric column detection that a
 * PDF needs — and that fails on a table whose first period column happens to be empty —
 * is simply unnecessary here.
 */
data class SheetGrid(
    val name: String,
    val hidden: Boolean,
    val rowCount: Int,
    val columnCount: Int,
    private val cells: Map<Long, String>,
) {
    fun text(row: Int, column: Int): String = cells[key(row, column)].orEmpty()

    fun row(index: Int): List<String> = (0 until columnCount).map { text(index, it) }

    fun isRowEmpty(index: Int): Boolean = row(index).all { it.isBlank() }

    val populatedCells: Int get() = cells.size

    companion object {
        internal fun key(row: Int, column: Int): Long = row.toLong() shl 20 or column.toLong()

        fun of(name: String, hidden: Boolean, cells: List<SheetCell>): SheetGrid = SheetGrid(
            name = name,
            hidden = hidden,
            rowCount = (cells.maxOfOrNull { it.row } ?: -1) + 1,
            columnCount = (cells.maxOfOrNull { it.column } ?: -1) + 1,
            cells = cells.associate { key(it.row, it.column) to it.text },
        )
    }
}

data class Workbook(val sheets: List<SheetGrid>)

/** Why a workbook could not be read, in the domain's vocabulary rather than the parser's. */
enum class XlsxFailure { NotAWorkbook, Corrupt, Empty, TooLarge, Hostile }

class XlsxException(val failure: XlsxFailure, message: String? = null) : Exception(message ?: failure.name)

/** Reference conversion: `C25` -> column 2, row 24. */
internal fun parseCellRef(ref: String): Pair<Int, Int>? {
    var column = 0
    var index = 0
    while (index < ref.length && ref[index].isLetter()) {
        column = column * 26 + (ref[index].uppercaseChar() - 'A' + 1)
        index++
    }
    if (index == 0 || index >= ref.length) return null
    val row = ref.substring(index).toIntOrNull() ?: return null
    return row - 1 to column - 1
}
