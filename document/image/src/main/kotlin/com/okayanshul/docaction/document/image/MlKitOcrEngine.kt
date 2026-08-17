package com.okayanshul.docaction.document.image

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.okayanshul.docaction.domain.BoundingBox
import com.okayanshul.docaction.domain.FailureReason
import com.okayanshul.docaction.domain.OcrEngine
import com.okayanshul.docaction.domain.OcrInput
import com.okayanshul.docaction.domain.Outcome
import com.okayanshul.docaction.domain.TextOrigin
import com.okayanshul.docaction.domain.TextRun
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * On-device text recognition, ADR-001 tier 3.
 *
 * ML Kit's own types stop here: everything leaves as [TextRun], so the recogniser can be
 * swapped — or removed — without the extraction engine noticing.
 *
 * Recognition happens at **element** granularity rather than block or line. A block that
 * averages 0.9 confidence routinely contains the one element at 0.4, and that element is
 * usually the number that matters — a time or a room code. Averaging would hide exactly the
 * uncertainty the review screen exists to surface.
 */
class MlKitOcrEngine(
    private val context: Context,
    private val decoder: SafeImageDecoder = SafeImageDecoder(),
) : OcrEngine {

    private val recogniser by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /**
     * The unbundled recogniser fetches its model through Play Services on first use. This
     * reports whether that has happened, so the UI can say "getting ready" once instead of
     * stalling an import with no explanation.
     */
    override suspend fun isReady(): Boolean = runCatching {
        val probe = Bitmap.createBitmap(1, 1, Bitmap.Config.RGB_565)
        recognise(InputImage.fromBitmap(probe, 0))
        probe.recycle()
        true
    }.getOrDefault(false)

    override suspend fun recognise(image: OcrInput): Outcome<List<TextRun>> {
        val bitmap = when (image) {
            is OcrInput.ImageUri -> when (val result = decoder.decode { streamFor(image.uri) }) {
                is SafeImageDecoder.Result.Decoded -> result.bitmap
                is SafeImageDecoder.Result.Rejected -> return Outcome.Failure(FailureReason.UnsupportedFormat)
            }

            is OcrInput.RenderedPage -> runCatching { image.toBitmap() }.getOrNull()
                ?: return Outcome.Failure(FailureReason.Corrupt)
        }

        return try {
            val text = recognise(InputImage.fromBitmap(bitmap, 0))
            val runs = text.toRuns()
            if (runs.isEmpty()) Outcome.Failure(FailureReason.NothingActionable) else Outcome.Success(runs)
        } catch (e: Exception) {
            Outcome.Failure(FailureReason.ProcessingUnavailable)
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun recognise(input: InputImage): Text = suspendCancellableCoroutine { continuation ->
        recogniser.process(input)
            .addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
            .addOnFailureListener { if (continuation.isActive) continuation.cancel(it) }
    }

    private fun Text.toRuns(): List<TextRun> = textBlocks
        .flatMap { it.lines }
        .flatMap { line -> line.phrases() }

    /**
     * Groups a recognised line's words into phrases, the way a PDF text layer already arrives.
     *
     * Recognition stays at element granularity — that part was right, and the confidence
     * reason below still holds — but emitting one run per *word* is what made photographed
     * and screenshotted timetables unreadable. `TableBuilder` finds columns by looking for
     * vertical bands that no text crosses, and it sizes "text wide enough to span a gutter"
     * from the median run width. A PDF gives it phrases; OCR gave it single words, which are
     * far narrower and far more numerous, so the calibration it relies on came out different
     * for the same document. The corpus proves the point: a native-PDF timetable produced
     * three schedules while a screenshot of the very same timetable produced none.
     *
     * Splitting on a gap proportional to the median character width mirrors
     * `PositionedTextStripper`, which breaks a run only at something gutter-sized rather than
     * at every space.
     *
     * **Confidence is the minimum, never the mean.** A phrase is exactly as trustworthy as
     * its worst word, and the one element at 0.4 inside an otherwise clean line is usually
     * the number that matters — a time or a room code. Averaging would bury the uncertainty
     * the review screen exists to surface.
     */
    private fun Text.Line.phrases(): List<TextRun> {
        val words = elements.mapNotNull { element ->
            val box = element.boundingBox ?: return@mapNotNull null
            if (element.text.isBlank()) null else element to box
        }.sortedBy { (_, box) -> box.left }
        if (words.isEmpty()) return emptyList()

        // A rotated label comes back as an axis-aligned box with an extreme aspect ratio,
        // because ML Kit's rotation is not carried on TextRun. Left in, it distorts the
        // median width every tolerance downstream is measured against.
        val typical = words.map { (_, box) -> box.height() }.sorted()[words.size / 2]
        val upright = words.filterNot { (_, box) ->
            typical > 0 && box.height() > box.width() * ROTATED_ASPECT
        }.ifEmpty { words }

        val gap = upright.map { (element, box) ->
            if (element.text.isEmpty()) box.width().toFloat() else box.width().toFloat() / element.text.length
        }.average().toFloat() * GAP_IN_CHARACTERS

        val phrases = mutableListOf<TextRun>()
        var current = mutableListOf(upright.first())

        upright.drop(1).forEach { word ->
            val previous = current.last().second
            if (word.second.left - previous.right > gap) {
                phrases += current.merge()
                current = mutableListOf(word)
            } else {
                current += word
            }
        }
        phrases += current.merge()
        return phrases
    }

    private fun List<Pair<Text.Element, android.graphics.Rect>>.merge(): TextRun = TextRun(
        text = joinToString(" ") { it.first.text },
        bounds = BoundingBox(
            left = minOf { it.second.left }.toFloat(),
            top = minOf { it.second.top }.toFloat(),
            right = maxOf { it.second.right }.toFloat(),
            bottom = maxOf { it.second.bottom }.toFloat(),
        ),
        // A genuine measurement this time, unlike a PDF text layer — and the worst word's,
        // so a single doubtful number is never averaged away behind a confident line.
        confidence = minOf { it.first.confidence },
        origin = TextOrigin.Ocr,
    )

    /**
     * Images arrive as `content://` from the photo picker and share sheet, which is the
     * path that matters in production. A plain path or `file://` is handled directly
     * because `ContentResolver` does not reliably open file URIs, and rasterised pages and
     * tests both take that route.
     */
    private fun streamFor(uri: String): InputStream {
        val parsed = Uri.parse(uri)
        return when (parsed.scheme) {
            null, "file" -> FileInputStream(parsed.path ?: uri)
            else -> context.contentResolver.openInputStream(parsed) ?: error("stream unavailable")
        }
    }

    private fun OcrInput.RenderedPage.toBitmap(): Bitmap =
        Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888).also {
            it.copyPixelsFromBuffer(ByteBuffer.wrap(argb))
        }

    private companion object {
        /**
         * How wide a gap has to be, in characters, before it separates two phrases.
         *
         * Chosen to match `PositionedTextStripper`'s rule of four space widths, which is the
         * PDF path this is being brought into line with.
         */
        const val GAP_IN_CHARACTERS = 2.0f

        /** Taller than this multiple of its width and the label was written sideways. */
        const val ROTATED_ASPECT = 2.0f
    }
}
