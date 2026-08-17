package com.okayanshul.docaction.document.pdf

import com.okayanshul.docaction.domain.DocumentFormat

/**
 * Identifies a format from its leading bytes.
 *
 * The filename extension and the OS-declared MIME type are advisory only — a file named
 * `schedule.pdf` arriving from a chat app is regularly an XLSX, an image, or truncated
 * rubbish. Extension is used solely to decide which parser to *try first*.
 *
 * This is a fast pre-filter. The definitive test is whether a parser can open the file:
 * a valid `%PDF-` header with a shredded xref table is Corrupt, not Pdf.
 */
object FormatSignature {

    /** Enough for every signature below. Never read more to guess a format. */
    const val PROBE_BYTES = 512

    fun detect(head: ByteArray): DocumentFormat = when {
        head.startsWith(PDF) -> DocumentFormat.Pdf
        head.startsWith(ZIP) -> DocumentFormat.Xlsx // narrowed by container inspection
        head.startsWith(PNG) -> DocumentFormat.Image
        head.startsWith(JPEG) -> DocumentFormat.Image
        head.startsWith(GIF87) || head.startsWith(GIF89) -> DocumentFormat.Image
        isRiffWebp(head) -> DocumentFormat.Image
        isHeif(head) -> DocumentFormat.Image
        // Text last, and by inspection rather than by signature. Every branch above decides
        // on magic bytes, which is what makes this class cheap and hard to fool. Text has no
        // magic bytes at all, so the only honest test is "does this look like text, and is
        // it delimited?" — applied only once every real signature has missed.
        looksDelimited(head) -> DocumentFormat.Csv
        // Text, but not in columns. There is nothing to reconstruct, so the prose reader
        // takes it and the UI says as much.
        looksLikeText(head) -> DocumentFormat.PlainText
        else -> DocumentFormat.Unsupported
    }

    /**
     * Text arranged in columns by a separator.
     *
     * Two conditions, both necessary. It must decode as text — a NUL byte anywhere in the
     * probe means this is a binary file that happens to have a `.csv` name, and the name is
     * never trusted. And a single delimiter must produce the *same* field count on every
     * sampled line, which is a property of a real separator: a prose file full of commas
     * fails it immediately, because its lines have nothing in common.
     */
    /**
     * Decodable as text, with no NUL bytes.
     *
     * Checked only after every real signature has missed, so a PDF or a JPEG can never fall
     * through to here. A binary file with a `.txt` name still fails, because the name is
     * never consulted — only the bytes are.
     */
    internal fun looksLikeText(head: ByteArray): Boolean {
        if (head.isEmpty()) return false
        val body = withoutBom(head)
        if (body.any { it == 0.toByte() }) return false
        val text = runCatching { String(body, Charsets.UTF_8) }.getOrNull() ?: return false
        // A decoder that replaced bytes it could not read produces U+FFFD; a file full of
        // those is binary being optimistically reinterpreted, not text.
        val replacements = text.count { it == '\uFFFD' }
        return text.isNotBlank() && replacements * REPLACEMENT_LIMIT < text.length
    }

    private fun withoutBom(head: ByteArray): ByteArray =
        if (head.size >= 3 && head[0] == 0xEF.toByte() &&
            head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()
        ) head.copyOfRange(3, head.size) else head

    internal fun looksDelimited(head: ByteArray): Boolean {
        if (head.isEmpty()) return false
        val body = withoutBom(head)
        if (body.any { it == 0.toByte() }) return false

        val text = runCatching { String(body, Charsets.UTF_8) }.getOrNull() ?: return false
        // The probe cuts mid-line, so the last one is dropped rather than counted short.
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList().dropLast(1)
        if (lines.size < MIN_DELIMITED_LINES) return false

        return DELIMITERS.any { delimiter ->
            val counts = lines.map { line -> line.count { it == delimiter } }
            counts.first() >= 1 && counts.distinct().size == 1
        }
    }

    /**
     * A ZIP is only an XLSX if it contains a workbook part. A ZIP that isn't one is
     * unsupported — saying so is more useful than reporting an XLSX we failed to read.
     */
    fun isWorkbook(entryNames: Sequence<String>): Boolean =
        entryNames.any { it == "xl/workbook.xml" || it.startsWith("xl/worksheets/") }

    /**
     * Rejects entry names that could escape the extraction directory. Names are never
     * used to build a filesystem path anywhere in this codebase, but a hostile name is
     * also a signal worth refusing outright.
     */
    private val DELIMITERS = listOf(',', ';', '\t', '|')

    /** Two lines agreeing is coincidence; three is a shape. */
    private const val MIN_DELIMITED_LINES = 3

    /** More than one character in twenty being a replacement means this was not text. */
    private const val REPLACEMENT_LIMIT = 20

    fun isSafeEntryName(name: String): Boolean =
        !name.startsWith("/") &&
            !name.contains("..") &&
            !name.contains('\\') &&
            !name.contains(':')

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        return prefix.indices.all { this[it] == prefix[it] }
    }

    private fun isRiffWebp(head: ByteArray): Boolean =
        head.size >= 12 && head.startsWith(RIFF) &&
            head[8] == 'W'.code.toByte() && head[9] == 'E'.code.toByte() &&
            head[10] == 'B'.code.toByte() && head[11] == 'P'.code.toByte()

    private fun isHeif(head: ByteArray): Boolean =
        head.size >= 12 &&
            head[4] == 'f'.code.toByte() && head[5] == 't'.code.toByte() &&
            head[6] == 'y'.code.toByte() && head[7] == 'p'.code.toByte()

    private val PDF = "%PDF-".toByteArray(Charsets.US_ASCII)
    private val ZIP = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
    private val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val GIF87 = "GIF87a".toByteArray(Charsets.US_ASCII)
    private val GIF89 = "GIF89a".toByteArray(Charsets.US_ASCII)
    private val RIFF = "RIFF".toByteArray(Charsets.US_ASCII)
}
