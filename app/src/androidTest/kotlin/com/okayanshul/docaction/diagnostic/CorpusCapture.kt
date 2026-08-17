package com.okayanshul.docaction.diagnostic

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.okayanshul.docaction.corpus.ContentSnapshot
import com.okayanshul.docaction.document.image.ImageDocumentReader
import com.okayanshul.docaction.document.image.MlKitOcrEngine
import com.okayanshul.docaction.document.pdf.PdfBoxTextSource
import com.okayanshul.docaction.document.pdf.PdfDocumentReader
import com.okayanshul.docaction.document.pdf.SignatureFormatDetector
import com.okayanshul.docaction.domain.DocumentContent
import com.okayanshul.docaction.domain.DocumentFormat
import com.okayanshul.docaction.domain.DocumentSource
import com.okayanshul.docaction.domain.ExtractionHints
import com.okayanshul.docaction.domain.Outcome
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Freezes what the real readers produce, so the engine can be tested without a device.
 *
 * Not a test — a capture. It asserts nothing and always passes; its output is the point.
 * Run it deliberately, then pull the snapshots with `./gradlew captureCorpus`.
 *
 * Why the reader boundary: every reason the corpus produced 3 events from 42 documents sits
 * *downstream* of reading. Freezing here puts the whole engine under a JVM gate that runs in
 * seconds over hundreds of documents, and leaves on-device testing to the small, slow part
 * that genuinely needs a device — PDFBox, `PdfRenderer` and ML Kit.
 *
 * The snapshots are checked in and the source documents are not, so regenerating one is a
 * reviewed act and the JSON diff shows exactly what a reader change did. That is also the
 * risk: a snapshot can drift from reality unnoticed. The nightly job re-captures and fails on
 * any difference, which turns drift into a build failure rather than a discovery.
 */
@RunWith(AndroidJUnit4::class)
@ManualTool
class CorpusCapture {

    private val assets = InstrumentationRegistry.getInstrumentation().context.assets
    private val target = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Documents pushed onto the device, in preference to the ones baked into the test APK.
     *
     * The APK assets cap the corpus at whatever fits in an APK, and they already had to be
     * cut from 9.6 MB to 1.8 MB when the emulator ran out of storage mid-run — which is the
     * real reason the corpus stopped growing at twelve reader-coverage documents. Pushing
     * instead means the corpus is bounded by the device's disk, not by the build, and the
     * documents never enter the repository at all.
     *
     * Assets remain the fallback so a plain `connectedDebugAndroidTest` on a clean machine
     * still captures something rather than silently capturing nothing.
     *
     * `/data/local/tmp` is the staging location that actually works on a stock emulator.
     * The obvious candidates both fail: the app's external files directory is FUSE-backed,
     * so adb-pushed files stay owned by `shell` and `chown` is refused even to root, and
     * `/data/data/<pkg>/files` cannot be written at all on a production build. Staging in
     * `/data/local/tmp` with `chmod 755` on the directory and `644` on the documents leaves
     * them readable to the app's uid, which is the whole requirement.
     *
     *     adb push <dir>/. /data/local/tmp/corpus-in/
     *     adb shell chmod 755 /data/local/tmp/corpus-in
     *     adb shell "chmod 644 /data/local/tmp/corpus-in/"[star]
     *
     * The app-private and external directories are still checked first, because on a device
     * where they are writable they are the tidier choice.
     */
    private val pushed: File?
        get() = listOf(
            File(target.filesDir, "corpus-in"),
            File(target.getExternalFilesDir(null), "corpus-in"),
            File("/data/local/tmp/corpus-in"),
        ).firstOrNull { it.isDirectory && (it.list()?.isNotEmpty() == true) }

    companion object {
        @JvmStatic
        @BeforeClass
        fun init() = PdfBoxTextSource.initialise(
            InstrumentationRegistry.getInstrumentation().targetContext,
        )
    }

    @Test
    fun capture() = runBlocking {
        val out = File(target.filesDir, "corpus-snapshots").apply {
            deleteRecursively()
            mkdirs()
        }
        val scratch = File(target.cacheDir, "corpus-capture").apply {
            deleteRecursively()
            mkdirs()
        }
        val ocr = MlKitOcrEngine(target)

        var captured = 0
        var skipped = 0

        val pushedDir = pushed
        val names = pushedDir?.list()?.sorted() ?: assets.list("webcorpus")!!.sorted()
        println("CAPTURE ===== ${names.size} documents from ${pushedDir?.absolutePath ?: "APK assets"} =====")

        names.forEach { name ->
            // Re-made per file: cache is reclaimable, and on a device under storage
            // pressure `installd` will purge it part-way through a run.
            val file = File(scratch.apply { mkdirs() }, name)
            if (pushedDir != null) {
                File(pushedDir, name).inputStream().use { input ->
                    file.outputStream().use(input::copyTo)
                }
            } else {
                assets.open("webcorpus/$name").use { input ->
                    file.outputStream().use(input::copyTo)
                }
            }
            val source = DocumentSource(file.absolutePath, name, null, file.length())

            val format = when (val detected = SignatureFormatDetector { file }.detect(source)) {
                is Outcome.Success -> detected.value
                is Outcome.Partial -> detected.value
                // A refusal is a reader-stage fact, so it belongs in the device suite, not
                // in a snapshot. The JVM gate never sees these documents.
                is Outcome.Failure -> {
                    println("CAPTURE $name -> refused at detect: ${detected.reason}")
                    skipped++
                    return@forEach
                }
            }

            // Spreadsheets skip positioned text entirely (ADR-011) and are pure JVM already,
            // so they run against the real file in the JVM suite and need no snapshot.
            if (format == DocumentFormat.Xlsx || format == DocumentFormat.Csv) {
                println("CAPTURE $name -> $format, read directly on the JVM")
                skipped++
                return@forEach
            }

            val reader = when (format) {
                DocumentFormat.Pdf -> PdfDocumentReader(fileFor = { file }, ocr = ocr)
                DocumentFormat.Image -> ImageDocumentReader(ocr)
                else -> {
                    println("CAPTURE $name -> no reader for $format")
                    skipped++
                    return@forEach
                }
            }

            val content: DocumentContent = when (val read = reader.read(source, ExtractionHints()) {}) {
                is Outcome.Success -> read.value
                is Outcome.Partial -> read.value
                is Outcome.Failure -> {
                    println("CAPTURE $name -> refused at read: ${read.reason}")
                    skipped++
                    return@forEach
                }
            }

            File(out, "$name.content.json").writeText(ContentSnapshot.write(content))
            captured++
            println("CAPTURE $name -> ${content.pages.size} pages, ${content.pages.sumOf { it.runs.size }} runs")
        }

        println("CAPTURE ===== captured=$captured skipped=$skipped into ${out.absolutePath} =====")
    }
}
