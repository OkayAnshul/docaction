package com.okayanshul.docaction.corpus

import com.okayanshul.docaction.document.spreadsheet.XlsxScheduleSource
import com.okayanshul.docaction.domain.DocumentContent
import com.okayanshul.docaction.domain.DocumentFormat
import com.okayanshul.docaction.domain.DocumentPipeline
import com.okayanshul.docaction.domain.DocumentReader
import com.okayanshul.docaction.domain.DocumentSource
import com.okayanshul.docaction.domain.ExtractionHints
import com.okayanshul.docaction.domain.FormatDetector
import com.okayanshul.docaction.domain.Outcome
import com.okayanshul.docaction.domain.PipelineAnswers
import com.okayanshul.docaction.domain.PipelineResult
import com.okayanshul.docaction.domain.StageProgress
import com.okayanshul.docaction.extraction.EngineScheduleFinder
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * Runs the real pipeline over the corpus, on the JVM, in milliseconds per document.
 *
 * Two kinds of document, one pipeline:
 *
 * - **Snapshotted** (PDF, image) — a `.content.json` captured from the real readers on a
 *   device stands in for the reader. Everything downstream is genuine.
 * - **Direct** (XLSX, CSV) — the real file, read by the real reader, because those modules
 *   are pure JVM already. No snapshot, no stand-in.
 *
 * What is deliberately *not* covered here: the readers themselves. Those stay on a device,
 * where PDFBox, `PdfRenderer` and ML Kit actually live. This gate covers the part where all
 * the bugs were.
 */
object CorpusRunner {

    val zone: ZoneId = ZoneId.of("Asia/Kolkata")

    /**
     * Fixed, not `LocalDate.now()`.
     *
     * A golden containing a date derived from today's date would rewrite itself every
     * morning, and the diff nobody can trust is the diff nobody reads.
     */
    val term = com.okayanshul.docaction.domain.TermBounds(
        LocalDate.of(2026, 8, 17),
        LocalDate.of(2026, 12, 5),
    )

    private val resources: File by lazy {
        File(CorpusRunner::class.java.getResource("/corpus")!!.toURI())
    }

    /** Every document the JVM gate can run, snapshotted or direct. */
    fun documents(): List<String> = resources.listFiles()!!
        .map { it.name }
        .map { it.removeSuffix(".content.json") }
        .distinct()
        .sorted()

    fun run(
        document: String,
        answers: PipelineAnswers = PipelineAnswers(term = term),
        // What the app actually uses. The gate measures shipped behaviour; the strict rule
        // is measured separately by InferencePotentialTest.
        policy: com.okayanshul.docaction.domain.InferencePolicy =
            com.okayanshul.docaction.domain.InferencePolicy.Default,
    ): PipelineResult {
        val snapshot = File(resources, "$document.content.json")
        return if (snapshot.exists()) {
            runSnapshotted(document, snapshot, answers, policy)
        } else {
            runDirect(document, File(resources, document), answers, policy)
        }
    }

    private fun runSnapshotted(
        document: String,
        snapshot: File,
        answers: PipelineAnswers,
        policy: com.okayanshul.docaction.domain.InferencePolicy,
    ): PipelineResult {
        val content = ContentSnapshot.read(snapshot.readText())
        val source = DocumentSource(snapshot.absolutePath, document, null, snapshot.length())

        val pipeline = DocumentPipeline(
            detector = fixedFormat(content.format),
            readers = listOf(Replay(content)),
            schedules = EngineScheduleFinder(),
            zone = zone,
            policy = policy,
        )
        return kotlinx.coroutines.runBlocking { pipeline.run(source, answers = answers) }
    }

    private fun runDirect(
        document: String,
        file: File,
        answers: PipelineAnswers,
        policy: com.okayanshul.docaction.domain.InferencePolicy,
    ): PipelineResult {
        val source = DocumentSource(file.absolutePath, document, null, file.length())
        // Both of these are pure JVM, so they read the real file rather than a snapshot.
        val format = when {
            document.endsWith(".csv") -> DocumentFormat.Csv
            document.endsWith(".txt") -> DocumentFormat.PlainText
            else -> DocumentFormat.Xlsx
        }
        val pipeline = DocumentPipeline(
            detector = fixedFormat(format),
            readers = listOf(
                com.okayanshul.docaction.document.text.PlainTextDocumentReader({ file }),
            ),
            schedules = EngineScheduleFinder(),
            zone = zone,
            scheduleSources = listOf(
                XlsxScheduleSource({ file }),
                com.okayanshul.docaction.document.csv.CsvScheduleSource({ file }),
            ),
            policy = policy,
        )
        return kotlinx.coroutines.runBlocking { pipeline.run(source, answers = answers) }
    }

    /**
     * The format is taken from the snapshot rather than re-detected.
     *
     * Detection is a reader-stage concern and is covered on device; re-implementing it here
     * would mean the gate could disagree with the app about what a file even is.
     */
    private fun fixedFormat(format: DocumentFormat) = object : FormatDetector {
        override suspend fun detect(source: DocumentSource): Outcome<DocumentFormat> =
            Outcome.Success(format)
    }

    private class Replay(private val content: DocumentContent) : DocumentReader {
        override fun supports(format: DocumentFormat) = format == content.format

        override suspend fun read(
            source: DocumentSource,
            hints: ExtractionHints,
            onProgress: (StageProgress) -> Unit,
        ): Outcome<DocumentContent> = Outcome.Success(
            content.copy(pages = content.pages.map { it.cropped(hints.cropRegion) }),
        )
    }
}
