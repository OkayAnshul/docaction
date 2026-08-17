package com.okayanshul.docaction.imports

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.okayanshul.docaction.domain.DocumentSource
import java.io.File
import java.util.UUID

/**
 * Copies a picked document somewhere the readers can work on it.
 *
 * A `content://` URI is a temporary grant, not a handle: it can be revoked between the
 * moment the user picks a file and the moment we read it, and some providers stream rather
 * than expose a file at all. Copying once makes the rest of the pipeline deal in plain
 * files, which is also what lets a page be re-read for Source View without asking the
 * provider again, and what lets a question be answered without re-reading the original.
 *
 * **Not the cache directory.** That was the first implementation and it is wrong: the
 * system reclaims cache whenever it likes, and on a device under storage pressure it does
 * so mid-import. Observed on an emulator — `installd` purged `cache/imports/…` while the
 * user was answering "when does this schedule run?", and answering then failed with "we
 * can't open this file any more". An in-flight document is not reclaimable data.
 *
 * `noBackupFilesDir` rather than `filesDir` so a staged document never rides along into a
 * cloud backup.
 *
 * Each instance gets its own subdirectory, so two imports open at once cannot delete each
 * other's file. Ours goes when the import finishes or is discarded; anyone else's is swept
 * only once it is old enough to be certainly abandoned.
 */
class DocumentStaging(private val context: Context) {

    private val root: File
        get() = File(context.noBackupFilesDir, "imports").apply { mkdirs() }

    private var adopted: File? = null

    private val directory: File
        get() = adopted ?: generated

    private val generated: File by lazy {
        File(root, UUID.randomUUID().toString()).apply { mkdirs() }
    }

    /**
     * Takes over the directory an earlier, interrupted import staged into.
     *
     * Resuming has to inherit the old directory rather than copy out of it, or the resumed
     * import would clean up a directory it does not own and leave the real one behind for
     * the sweep to find an hour later.
     */
    fun adopt(staged: File) {
        adopted = staged.parentFile?.takeIf { it.isDirectory && it.parentFile == root }
    }

    /** @return null when the grant has already been revoked. */
    fun stage(uri: Uri): DocumentSource? {
        val name = displayName(uri) ?: "document"
        val target = File(directory, name)

        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { input.copyTo(it) }
            } ?: return null
        }
        if (copied.isFailure) return null

        return DocumentSource(
            uri = target.absolutePath,
            displayName = name,
            declaredMimeType = context.contentResolver.getType(uri),
            sizeBytes = target.length(),
        )
    }

    /**
     * Somewhere for a camera app to put a photo.
     *
     * A hand-off, not a home: the returned URI is handed to a camera app, and the moment it
     * reports success the photo goes through [stage] like every other document. That keeps
     * one path for everything downstream — staging, Source View, rescue, resume — rather
     * than a second lifetime that has to be reasoned about separately.
     */
    fun captureTarget(): Uri {
        val captures = File(context.cacheDir, "captures").apply { mkdirs() }
        val file = File(captures, "photo-${System.currentTimeMillis()}.jpg")
        return androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.captures",
            file,
        )
    }

    /**
     * Text shared from another app, written down so it is a document like any other.
     *
     * A message pasted from WhatsApp or an email body arrives as a string in an intent, with
     * no file behind it. Writing it into staging costs a few kilobytes and buys the entire
     * downstream flow unchanged — resume, rescue and Source View all address documents by
     * path, and a special case for "text that has no file" would have to be handled in each.
     */
    fun stageText(text: String, name: String = "Shared text"): DocumentSource? {
        if (text.isBlank()) return null
        val file = File(directory, "shared-${System.currentTimeMillis()}.txt")
        val written = runCatching { file.writeText(text) }
        if (written.isFailure) return null

        return DocumentSource(
            uri = file.absolutePath,
            displayName = name,
            declaredMimeType = "text/plain",
            sizeBytes = file.length(),
        )
    }

    /** The hand-off directory, once the photo has been copied out of it. */
    fun clearCaptures() {
        runCatching { File(context.cacheDir, "captures").deleteRecursively() }
    }

    fun fileFor(source: DocumentSource): File? =
        File(source.uri).takeIf { it.exists() }

    /** Documents do not linger. Called on finish and on discard. */
    fun clear() {
        runCatching { directory.deleteRecursively() }
    }

    /**
     * Removes leftovers from imports that ended without cleaning up — a crash, or a process
     * death mid-review.
     *
     * Only directories that have gone untouched for [ABANDONED_AFTER_MILLIS] are removed,
     * because a directory being old is the only evidence available that nobody is still
     * using it. Deleting every other directory on startup would break the case where a
     * second document is shared while the first import is still open.
     *
     * [keep] spares directories that are old but known to be wanted — the one behind an
     * interrupted import waiting to be resumed.
     */
    fun sweepAbandoned(now: Long = System.currentTimeMillis(), keep: Set<File> = emptySet()) {
        runCatching {
            root.listFiles()?.forEach { candidate ->
                if (candidate == directory || candidate in keep) return@forEach
                val touched = candidate.walkTopDown().maxOfOrNull { it.lastModified() } ?: 0L
                if (now - touched > ABANDONED_AFTER_MILLIS) candidate.deleteRecursively()
            }
        }
    }

    private fun displayName(uri: Uri): String? {
        val raw = if (uri.scheme == "file") {
            uri.lastPathSegment
        } else {
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
                }
        }
        // A provider's display name is untrusted text: it can contain path separators, and
        // this string is about to become a filename.
        return raw?.substringAfterLast('/')?.substringAfterLast('\\')?.take(120)?.ifBlank { null }
    }

    private companion object {
        const val ABANDONED_AFTER_MILLIS = 60L * 60 * 1000
    }
}
