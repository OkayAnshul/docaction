package com.okayanshul.docaction.document.csv

import com.okayanshul.docaction.document.spreadsheet.SheetCell
import com.okayanshul.docaction.document.spreadsheet.SheetGrid
import com.okayanshul.docaction.document.spreadsheet.Workbook
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Reads a delimited text file into the same shape a spreadsheet produces.
 *
 * The output is a one-sheet [Workbook], which means everything already built for XLSX —
 * stacked-section detection, the section picker, the entry builder — applies unchanged. That
 * reuse is the whole design: a CSV is a spreadsheet that forgot its formatting.
 *
 * Three things about real CSV files that a naive `split(",")` gets wrong, and which this
 * handles:
 *
 * 1. **The delimiter is not always a comma.** Excel in most of Europe writes semicolons,
 *    because the comma is the decimal separator there. Tabs and pipes both occur. The
 *    delimiter is chosen by which one produces a *consistent* field count across lines,
 *    which is a property of the real separator and not of a character that happens to appear.
 * 2. **The encoding is not always UTF-8.** A file exported from an older Windows tool is
 *    Windows-1252, and decoding it as UTF-8 replaces its punctuation with question marks.
 * 3. **Quoted fields contain anything**, including the delimiter and newlines.
 */
class CsvReader(
    private val maxBytes: Long = 20L * 1024 * 1024,
    private val sampleLines: Int = 20,
) {

    /** Why a file could not be read, in this module's own vocabulary. */
    sealed interface Failure {
        data object Empty : Failure
        data object TooLarge : Failure
        data object NotText : Failure
    }

    sealed interface Result {
        data class Read(val workbook: Workbook) : Result
        data class Refused(val why: Failure) : Result
    }

    fun read(file: File, sheetName: String = "Sheet"): Result {
        if (!file.exists() || file.length() == 0L) return Result.Refused(Failure.Empty)
        if (file.length() > maxBytes) return Result.Refused(Failure.TooLarge)

        val text = decode(file.readBytes()) ?: return Result.Refused(Failure.NotText)
        if (text.isBlank()) return Result.Refused(Failure.Empty)

        val delimiter = detectDelimiter(text) ?: return Result.Refused(Failure.NotText)
        val rows = parse(text, delimiter)
        if (rows.isEmpty()) return Result.Refused(Failure.Empty)

        val cells = rows.flatMapIndexed { row, values ->
            values.mapIndexed { column, value -> SheetCell(row, column, value) }
        }
        return Result.Read(Workbook(listOf(SheetGrid.of(sheetName, hidden = false, cells = cells))))
    }

    /**
     * UTF-8 if it decodes cleanly, Windows-1252 otherwise.
     *
     * Strict decoding is the point: UTF-8 will happily *accept* almost any byte sequence if
     * told to replace what it cannot read, and the result is a file full of replacement
     * characters that looks like a successful read. Failing and falling back gives the user
     * their own punctuation instead.
     */
    private fun decode(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        // A UTF-8 BOM is a statement of encoding; honour it and drop it.
        val body = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            bytes.copyOfRange(3, bytes.size)
        } else {
            bytes
        }

        // A NUL byte means this is not text at all — it is a binary file with a .csv name.
        if (body.take(PROBE_BYTES).any { it == 0.toByte() }) return null

        return runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(body))
                .toString()
        }.getOrElse {
            runCatching { String(body, Charset.forName("windows-1252")) }.getOrNull()
        }
    }

    /**
     * The delimiter is the candidate that splits lines into a *consistent* number of fields.
     *
     * Counting occurrences would pick the wrong character constantly: a subject column full
     * of "Data Structures, Algorithms" has more commas than a semicolon-delimited file has
     * semicolons. Consistency is what actually distinguishes a separator from a character
     * that merely appears often.
     */
    private fun detectDelimiter(text: String): Char? {
        val lines = text.lineSequence().filter { it.isNotBlank() }.take(sampleLines).toList()
        if (lines.isEmpty()) return null

        return CANDIDATES
            .map { candidate -> candidate to lines.map { parseLine(it, candidate).size } }
            .filter { (_, counts) -> counts.first() > 1 && counts.distinct().size == 1 }
            .maxByOrNull { (_, counts) -> counts.first() }
            ?.first
            // A single column is still a readable file, just not a very useful one.
            ?: CANDIDATES.first().takeIf { lines.size > 1 }
    }

    private fun parse(text: String, delimiter: Char): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val field = StringBuilder()
        val row = mutableListOf<String>()
        var quoted = false
        var index = 0

        while (index < text.length) {
            val ch = text[index]
            when {
                quoted && ch == '"' && index + 1 < text.length && text[index + 1] == '"' -> {
                    // "" inside a quoted field is a literal quote (RFC 4180).
                    field.append('"'); index++
                }

                ch == '"' -> quoted = !quoted
                !quoted && ch == delimiter -> { row += field.toString().trim(); field.clear() }
                !quoted && (ch == '\n' || ch == '\r') -> {
                    // A quoted field may contain newlines, so only an unquoted one ends a row.
                    if (field.isNotEmpty() || row.isNotEmpty()) {
                        row += field.toString().trim()
                        rows += row.toList()
                        row.clear(); field.clear()
                    }
                    if (ch == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++
                }

                else -> field.append(ch)
            }
            index++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row += field.toString().trim()
            rows += row.toList()
        }
        // Ragged rows are kept as they are: a short row is information about the document,
        // and padding it would invent empty cells the file never had.
        return rows
    }

    private fun parseLine(line: String, delimiter: Char): List<String> =
        parse(line, delimiter).firstOrNull() ?: emptyList()

    private companion object {
        /** Semicolon second, not last: European Excel writes it by default. */
        val CANDIDATES = listOf(',', ';', '\t', '|')
        const val PROBE_BYTES = 4096
    }
}
