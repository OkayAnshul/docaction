package com.okayanshul.docaction.document.pdf

import com.okayanshul.docaction.domain.DocumentFormat
import com.okayanshul.docaction.domain.DocumentSource
import com.okayanshul.docaction.domain.FailureReason
import com.okayanshul.docaction.domain.FormatDetector
import com.okayanshul.docaction.domain.Outcome
import java.io.File
import java.util.zip.ZipFile

/**
 * Identifies a document by what it contains, never by what it is called.
 *
 * A file named `schedule.pdf` arriving from a chat app is regularly an XLSX, an image, or a
 * web page. The corpus contains a real example: `iitb-endsem.pdf` is served as HTML, and
 * trusting its extension would send it to a PDF parser that can only report corruption.
 *
 * The extension is used for nothing. Only the leading bytes decide, and a ZIP is narrowed by
 * looking inside it — a ZIP that is not a workbook is unsupported, which is more useful than
 * "an XLSX we failed to read".
 */
class SignatureFormatDetector(
    private val fileFor: (DocumentSource) -> File?,
) : FormatDetector {

    override suspend fun detect(source: DocumentSource): Outcome<DocumentFormat> {
        val file = fileFor(source) ?: return Outcome.Failure(FailureReason.PermissionRevoked)
        if (!file.exists()) return Outcome.Failure(FailureReason.PermissionRevoked)
        if (file.length() == 0L) return Outcome.Failure(FailureReason.Empty)
        if (file.length() > MAX_BYTES) return Outcome.Failure(FailureReason.TooLarge)

        val head = ByteArray(FormatSignature.PROBE_BYTES)
        val read = runCatching { file.inputStream().use { it.read(head) } }.getOrNull()
            ?: return Outcome.Failure(FailureReason.PermissionRevoked)

        return when (val detected = FormatSignature.detect(head.copyOf(maxOf(read, 0)))) {
            DocumentFormat.Xlsx -> Outcome.Success(narrowZip(file))
            DocumentFormat.Unsupported -> Outcome.Failure(FailureReason.UnsupportedFormat)
            else -> Outcome.Success(detected)
        }
    }

    /** A ZIP is only a workbook if it holds one. Anything else is honestly unsupported. */
    private fun narrowZip(file: File): DocumentFormat = runCatching {
        ZipFile(file).use { zip ->
            val names = zip.entries().asSequence().map { it.name }
            if (FormatSignature.isWorkbook(names)) DocumentFormat.Xlsx else DocumentFormat.Unsupported
        }
    }.getOrDefault(DocumentFormat.Unsupported)

    private companion object {
        const val MAX_BYTES = 100L * 1024 * 1024
    }
}
