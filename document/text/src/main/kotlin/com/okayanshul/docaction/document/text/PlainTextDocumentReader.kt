package com.okayanshul.docaction.document.text

import com.okayanshul.docaction.domain.BoundingBox
import com.okayanshul.docaction.domain.DocumentContent
import com.okayanshul.docaction.domain.DocumentFormat
import com.okayanshul.docaction.domain.DocumentReader
import com.okayanshul.docaction.domain.DocumentSource
import com.okayanshul.docaction.domain.ExtractionHints
import com.okayanshul.docaction.domain.FailureReason
import com.okayanshul.docaction.domain.Outcome
import com.okayanshul.docaction.domain.PageContent
import com.okayanshul.docaction.domain.Stage
import com.okayanshul.docaction.domain.StageProgress
import com.okayanshul.docaction.domain.TextOrigin
import com.okayanshul.docaction.domain.TextRun
import java.io.File
import java.nio.charset.CodingErrorAction

/**
 * Reads text that has no layout at all — a pasted message, a shared note, a `.txt` file.
 *
 * The important thing about this reader is what it must **not** claim. Every other reader
 * produces runs with real coordinates, measured from the document. Text has none, so the
 * boxes here are synthetic: one line per row, each the full width. They exist because the
 * `TextRun` contract requires them, not because they mean anything.
 *
 * That is exactly why plain text must never reach table reconstruction. ADR-011 records what
 * happens when synthesised coordinates are fed to geometric detection — a real timetable lost
 * three period columns to it. `EngineScheduleFinder` routes this format to the prose reader
 * only, and the synthetic geometry never gets a chance to be mistaken for a measurement.
 */
class PlainTextDocumentReader(
    private val fileFor: (DocumentSource) -> File?,
    private val maxBytes: Long = 5L * 1024 * 1024,
) : DocumentReader {

    override fun supports(format: DocumentFormat) = format == DocumentFormat.PlainText

    override suspend fun read(
        source: DocumentSource,
        hints: ExtractionHints,
        onProgress: (StageProgress) -> Unit,
    ): Outcome<DocumentContent> {
        val file = fileFor(source) ?: return Outcome.Failure(FailureReason.PermissionRevoked)
        if (!file.exists()) return Outcome.Failure(FailureReason.PermissionRevoked)
        if (file.length() == 0L) return Outcome.Failure(FailureReason.Empty)
        if (file.length() > maxBytes) return Outcome.Failure(FailureReason.TooLarge)

        onProgress(StageProgress(Stage.ReadingDocument, 1, 1))

        val text = decode(file.readBytes()) ?: return Outcome.Failure(FailureReason.UnsupportedFormat)
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return Outcome.Failure(FailureReason.Empty)

        val runs = lines.mapIndexed { index, line ->
            TextRun(
                text = line,
                // Synthetic and uniform. A row per line, full width — enough for the prose
                // reader to order lines and find neighbours, and deliberately not enough to
                // look like a column layout to anything else.
                bounds = BoundingBox(
                    left = 0f,
                    top = index * LINE_HEIGHT,
                    right = LINE_WIDTH,
                    bottom = index * LINE_HEIGHT + LINE_HEIGHT,
                ),
                confidence = null,
                origin = TextOrigin.PlainText,
            )
        }

        return Outcome.Success(
            DocumentContent(
                format = DocumentFormat.PlainText,
                pages = listOf(
                    PageContent(
                        index = 0,
                        widthPt = LINE_WIDTH,
                        heightPt = lines.size * LINE_HEIGHT,
                        runs = runs,
                    )
                ),
            )
        )
    }

    /** UTF-8 if it decodes cleanly; Windows-1252 otherwise. Binary is refused outright. */
    private fun decode(bytes: ByteArray): String? {
        val body = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) bytes.copyOfRange(3, bytes.size) else bytes

        if (body.take(PROBE_BYTES).any { it == 0.toByte() }) return null

        return runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(body))
                .toString()
        }.getOrElse {
            runCatching { String(body, java.nio.charset.Charset.forName("windows-1252")) }.getOrNull()
        }
    }

    private companion object {
        const val LINE_HEIGHT = 12f
        const val LINE_WIDTH = 500f
        const val PROBE_BYTES = 4096
    }
}
