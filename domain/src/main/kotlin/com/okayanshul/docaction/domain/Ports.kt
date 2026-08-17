package com.okayanshul.docaction.domain

/**
 * What the domain needs from the outside world. Implementations live in the
 * infrastructure modules and are wired in `:app`; the domain knows none of them.
 */

/** Reads a document into positioned text. One implementation per format. */
interface DocumentReader {
    fun supports(format: DocumentFormat): Boolean

    suspend fun read(
        source: DocumentSource,
        hints: ExtractionHints,
        onProgress: (StageProgress) -> Unit,
    ): Outcome<DocumentContent>
}

/** Identifies a format from content, never from the extension. */
interface FormatDetector {
    suspend fun detect(source: DocumentSource): Outcome<DocumentFormat>
}

/**
 * Recognises text in an image.
 *
 * Returns our own [TextRun]s, never the recogniser's types, so OCR stays replaceable and
 * `:extraction` keeps compiling on the JVM with no Android on the classpath. The engine
 * downstream cannot tell — and must not care — which recogniser produced a run.
 */
interface OcrEngine {
    /** True when the recogniser is ready; false while a model is still being fetched. */
    suspend fun isReady(): Boolean

    /**
     * @param image opaque handle the implementation knows how to decode — a URI string, or
     *   a rendered page supplied by the PDF reader.
     * @return positioned runs carrying per-element confidence, in image coordinates.
     */
    suspend fun recognise(image: OcrInput): Outcome<List<TextRun>>
}

sealed interface OcrInput {
    /** An image the user supplied, addressed by content URI. */
    data class ImageUri(val uri: String) : OcrInput

    /**
     * A page rendered by the PDF reader. Bytes rather than a bitmap so the type stays free
     * of Android, and so this can cross the sandbox process boundary unchanged.
     */
    data class RenderedPage(
        val pageIndex: Int,
        val widthPx: Int,
        val heightPx: Int,
        val argb: ByteArray,
    ) : OcrInput {
        override fun equals(other: Any?) = this === other
        override fun hashCode() = pageIndex
    }
}

/** V1 ships this wherever no recogniser is wired; the pipeline stays functional without OCR. */
class NoOpOcrEngine : OcrEngine {
    override suspend fun isReady() = false
    override suspend fun recognise(image: OcrInput): Outcome<List<TextRun>> =
        Outcome.Failure(FailureReason.ProcessingUnavailable)
}

/** Where an action is written — e.g. a specific calendar. */
interface ActionTarget {
    val id: String
    val label: String
}

data class ExecutionReport(
    val importId: ImportId,
    val written: List<CandidateId>,
    val skipped: List<CandidateId>,
    val failed: Map<CandidateId, String>,
) {
    /** There is no boolean success. Partial results are reported as partial. */
    val isComplete: Boolean get() = failed.isEmpty()
}

data class RevertReport(val removed: Int, val alreadyGone: Int, val modifiedByUser: Int)

data class DuplicateMatch(
    val candidateId: CandidateId,
    val existingTitle: String,
    val existingStartMillis: Long,
    val createdByUs: Boolean,
)

interface ActionExecutor<in T : ActionCandidate> {
    suspend fun targets(): Outcome<List<ActionTarget>>

    suspend fun findDuplicates(candidates: List<T>, target: ActionTarget): Outcome<List<DuplicateMatch>>

    suspend fun execute(
        importId: ImportId,
        candidates: List<T>,
        target: ActionTarget,
        onProgress: (Int, Int) -> Unit,
    ): Outcome<ExecutionReport>

    /** Removes only what [importId] created. Never a time-range delete. */
    suspend fun revert(importId: ImportId): Outcome<RevertReport>
}

/**
 * The AI seam. V1 ships [NoOpAmbiguityResolver] and the pipeline is fully functional
 * with it — AI is a plug-in, never a dependency. See docs/05-architecture.md ADR-008.
 */
interface AmbiguityResolver {
    suspend fun resolve(region: AmbiguousRegion): Outcome<StructuredHypothesis>
}

data class AmbiguousRegion(
    val runs: List<TextRun>,
    val expecting: Expectation,
    val source: SourceReference,
)

/**
 * A proposed reading of an ambiguous region. Every value here must be found verbatim in
 * the region's extracted text before it is accepted — a resolver can rearrange what the
 * document says, never introduce something it doesn't.
 */
data class StructuredHypothesis(
    val entries: List<ScheduleEntry>,
    val explanation: String,
)

class NoOpAmbiguityResolver : AmbiguityResolver {
    override suspend fun resolve(region: AmbiguousRegion): Outcome<StructuredHypothesis> =
        Outcome.Failure(FailureReason.ProcessingUnavailable)
}
