package com.okayanshul.docaction.document.sandbox

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.okayanshul.docaction.document.pdf.PdfInfo
import com.okayanshul.docaction.document.pdf.PdfOpenException
import com.okayanshul.docaction.document.pdf.PdfOpenFailure
import com.okayanshul.docaction.document.pdf.PdfTextSource
import com.okayanshul.docaction.document.pdf.PdfTextSourceFactory
import com.okayanshul.docaction.domain.DocumentCodec
import com.okayanshul.docaction.domain.PageContent
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A [PdfTextSourceFactory] whose parsing happens somewhere it cannot hurt anyone.
 *
 * Deliberately a *text source* rather than a whole document reader. `PdfDocumentReader` keeps
 * its per-page timeouts, its OCR fallback for pages with no text layer, and its partial
 * results — all of which are tested — and the only thing that moves out of process is the
 * PdfBox call that actually touches attacker-controlled bytes. Replacing the reader instead
 * would have meant reimplementing that logic on the far side of an IPC boundary, which is how
 * a security improvement becomes a regression.
 *
 * The remote process holds the open document between calls, so page-at-a-time still means
 * page-at-a-time. An 84-page file is never materialised on either side.
 */
class SandboxedPdfTextSource(private val context: Context) : PdfTextSourceFactory {

    override fun open(file: File): PdfTextSource {
        if (!file.exists() || file.length() == 0L) throw PdfOpenException(PdfOpenFailure.Empty)

        val connection = Connection()
        val intent = Intent(context, SandboxParsingService::class.java)

        // BIND_AUTO_CREATE starts the isolated process; without it the bind waits for a
        // service nothing else will ever start.
        val bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        if (!bound) {
            context.unbindService(connection)
            throw PdfOpenException(PdfOpenFailure.Corrupt)
        }

        val parser = connection.awaitBinder()
        if (parser == null) {
            runCatching { context.unbindService(connection) }
            throw PdfOpenException(PdfOpenFailure.Corrupt)
        }

        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val pages = try {
            parser.open(descriptor)
        } catch (e: Exception) {
            // A dead binder here means the parser died opening the file, which is exactly the
            // crash this service exists to contain. It reads as a damaged document.
            runCatching { context.unbindService(connection) }
            throw PdfOpenException(PdfOpenFailure.Corrupt)
        } finally {
            runCatching { descriptor.close() }
        }

        if (pages <= 0) {
            runCatching { context.unbindService(connection) }
            throw PdfOpenException(
                when (pages) {
                    SandboxCodes.ENCRYPTED -> PdfOpenFailure.Encrypted
                    SandboxCodes.EMPTY -> PdfOpenFailure.Empty
                    SandboxCodes.TOO_LARGE -> PdfOpenFailure.TooLarge
                    else -> PdfOpenFailure.Corrupt
                },
            )
        }

        return RemoteSource(context, connection, parser, PdfInfo(pages, encrypted = false))
    }

    private class RemoteSource(
        private val context: Context,
        private val connection: Connection,
        private val parser: ISandboxParser,
        override val info: PdfInfo,
    ) : PdfTextSource {

        /**
         * One scratch file, reused for every page.
         *
         * A file rather than a pipe because the call filling it is synchronous: a page dense
         * enough to exceed a pipe's buffer would block the service writing while this side
         * blocks waiting for it to return.
         */
        private val scratch: File =
            File.createTempFile("sandbox-page", ".bin", context.cacheDir)

        override fun page(index: Int): PageContent? {
            val descriptor = ParcelFileDescriptor.open(
                scratch,
                ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE,
            )

            val hadText = try {
                parser.readPage(index, descriptor)
            } catch (e: Exception) {
                // The parser died on this page. Not fatal to the import: the caller falls
                // back to OCR for it, exactly as it would for a scanned page.
                false
            } finally {
                runCatching { descriptor.close() }
            }
            if (!hadText) return null

            return try {
                FileInputStream(scratch).use { DocumentCodec.read(it) }.pages.firstOrNull()
            } catch (e: DocumentCodec.Malformed) {
                // Bytes we do not understand from a process we do not trust. Treated as a
                // page with no text rather than propagated.
                null
            }
        }

        override fun close() {
            runCatching { parser.release() }
            runCatching { context.unbindService(connection) }
            runCatching { scratch.delete() }
        }
    }

    /**
     * Waits for the isolated process to start, with a bound.
     *
     * A bind that never completes would otherwise hang the import for ever on a device under
     * memory pressure — the one situation where starting a second process is most likely to
     * be refused.
     */
    private class Connection : ServiceConnection {
        private val ready = CountDownLatch(1)

        @Volatile
        private var parser: ISandboxParser? = null

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            parser = ISandboxParser.Stub.asInterface(service)
            ready.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            parser = null
            ready.countDown()
        }

        fun awaitBinder(): ISandboxParser? =
            if (ready.await(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) parser else null
    }

    companion object {
        private const val BIND_TIMEOUT_SECONDS = 10L
    }
}
