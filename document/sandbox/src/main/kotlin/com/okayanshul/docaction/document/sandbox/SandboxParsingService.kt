package com.okayanshul.docaction.document.sandbox

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.okayanshul.docaction.document.pdf.PdfBoxTextSource
import com.okayanshul.docaction.document.pdf.PdfOpenException
import com.okayanshul.docaction.document.pdf.PdfOpenFailure
import com.okayanshul.docaction.document.pdf.PdfTextSource
import com.okayanshul.docaction.domain.DocumentCodec
import com.okayanshul.docaction.domain.DocumentContent
import com.okayanshul.docaction.domain.DocumentFormat
import java.io.FileInputStream
import java.io.FileOutputStream

/** What [ISandboxParser.open] returns instead of a page count. Mirrors `PdfOpenFailure`. */
object SandboxCodes {
    const val ENCRYPTED = -1
    const val CORRUPT = -2
    const val EMPTY = -3
    const val TOO_LARGE = -4
    const val UNAVAILABLE = -5
}

/**
 * Parses untrusted documents in a process that can do nothing else.
 *
 * Declared `android:isolatedProcess="true"`, which gives it its own UID with no permissions,
 * no filesystem access and no ability to talk to anything except the app that bound it. The
 * document arrives as an already-open file descriptor because it could not open a file even
 * if it wanted to.
 *
 * The point is what this buys when the parser misbehaves, and PdfBox-Android's last upstream
 * release was January 2023 so it will:
 *
 * - A parser crash kills a throwaway process. The app sees a dead binder and reports a
 *   damaged file, which is a screen it already has.
 * - A parser stuck in a native loop is killable. Cooperative cancellation cannot reach code
 *   that never checks; `Process.killProcess` on the child can.
 * - A decompression bomb exhausts this process's heap, not the one holding the user's review.
 *
 * Nothing structured crosses the boundary. Text runs go back as bytes that
 * [DocumentCodec] reads with bounded allocations, so a compromised parser cannot choose what
 * gets constructed on the other side.
 */
class SandboxParsingService : Service() {

    private var source: PdfTextSource? = null

    override fun onCreate() {
        super.onCreate()
        // PdfBox's font resources live in the APK's assets, which an isolated process can
        // still read — it has the package loaded, just no data directory.
        runCatching { PdfBoxTextSource.initialise(this) }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        releaseDocument()
        super.onDestroy()
    }

    private val binder = object : ISandboxParser.Stub() {

        override fun open(document: ParcelFileDescriptor?): Int {
            releaseDocument()
            val fd = document ?: return SandboxCodes.CORRUPT

            return try {
                // An isolated process has no filesystem, but it does have its own fd table,
                // and /proc/self/fd/N is a real path to an already-open file. That is what
                // lets PdfBox keep random access — loading through a stream would buffer the
                // whole document and lose the page-at-a-time memory discipline this exists
                // to protect.
                // Streamed off the descriptor, never re-opened by path. An isolated
                // process may read the fd it was handed; it may not open the file behind it,
                // and /proc/self/fd/N is the latter wearing the former's clothes.
                val opened = FileInputStream(fd.fileDescriptor).use { stream ->
                    PdfBoxTextSource.openStream(stream, sizeBytes = fd.statSize)
                }
                source = opened
                opened.info.pageCount
            } catch (e: PdfOpenException) {
                // Type and location, never the message: a parser exception routinely quotes
                // document content, and this is the boundary that content must not cross.
                // Kept rather than trimmed to a bare type — a process with no filesystem and
                // no debugger attached is one you diagnose from six frames or not at all.
                android.util.Log.w(
                    LOG_TAG,
                    "open refused: ${e.failure} <- ${e.cause?.let { c -> c::class.java.name }} " +
                        "at ${e.cause?.stackTrace?.take(3)?.joinToString(" | ")}",
                )
                when (e.failure) {
                    PdfOpenFailure.Encrypted -> SandboxCodes.ENCRYPTED
                    PdfOpenFailure.Corrupt -> SandboxCodes.CORRUPT
                    PdfOpenFailure.Empty -> SandboxCodes.EMPTY
                    PdfOpenFailure.TooLarge -> SandboxCodes.TOO_LARGE
                }
            } catch (e: Throwable) {
                // Including OutOfMemoryError. Nothing about the cause travels back: a parser
                // exception message routinely quotes document content, and this is the one
                // boundary where that content must not cross. The *type* is logged, because a
                // catch-all that discards the cause entirely turns every failure in here into
                // a guessing game — and this process is deliberately hard to debug.
                android.util.Log.w(LOG_TAG, "open failed: ${e::class.java.name}")
                SandboxCodes.CORRUPT
            } finally {
                runCatching { fd.close() }
            }
        }

        override fun readPage(index: Int, target: ParcelFileDescriptor?): Boolean {
            val document = source ?: return false
            val destination = target ?: return false

            return try {
                val page = document.page(index) ?: return false
                FileOutputStream(destination.fileDescriptor).use { stream ->
                    // One page, wrapped in the same envelope a whole document uses, so the
                    // reader on the other side is one code path rather than two.
                    DocumentCodec.write(
                        DocumentContent(DocumentFormat.Pdf, listOf(page)),
                        stream,
                    )
                }
                true
            } catch (e: Throwable) {
                // A page that kills the parser is a page we skip, not a document we lose.
                android.util.Log.w(LOG_TAG, "page $index failed: ${e::class.java.name}")
                false
            } finally {
                runCatching { destination.close() }
            }
        }

        override fun release() = releaseDocument()
    }

    private fun releaseDocument() {
        runCatching { source?.close() }
        source = null
    }

    private companion object {
        const val LOG_TAG = "DocActionSandbox"
    }
}
