package com.okayanshul.docaction.imports

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Where a staged document lives, and why it matters.
 *
 * The first implementation used `cacheDir`, which is exactly what cache is for — until the
 * system reclaims it. On a device low on storage it did: `installd` purged
 * `cache/imports/…` while the user was answering "when does this schedule run?", and
 * answering then failed with "we can't open this file any more". An in-flight document is
 * not reclaimable data, and no amount of care elsewhere in the flow can survive the file
 * disappearing underneath it.
 */
@RunWith(AndroidJUnit4::class)
class DocumentStagingTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val staging = DocumentStaging(context)

    @After
    fun tearDown() = staging.clear()

    private fun aFile(name: String = "timetable.pdf"): Uri {
        val file = File(context.filesDir, name).apply { writeBytes("%PDF-1.4".toByteArray()) }
        return Uri.fromFile(file)
    }

    @Test
    fun aStagedDocumentIsNotStoredWhereTheSystemCanReclaimIt() {
        val staged = staging.stage(aFile())!!

        assertThat(staged.uri).doesNotContain(context.cacheDir.absolutePath)
        assertThat(staged.uri).contains(context.noBackupFilesDir.absolutePath)
        assertThat(File(staged.uri).exists()).isTrue()
    }

    @Test
    fun twoImportsAtOnceDoNotDeleteEachOthersDocument() {
        val first = DocumentStaging(context)
        val second = DocumentStaging(context)

        val a = first.stage(aFile("a.pdf"))!!
        val b = second.stage(aFile("b.pdf"))!!

        // Sharing a second document while the first import is open must not break the first.
        second.sweepAbandoned()
        assertThat(File(a.uri).exists()).isTrue()

        // Finishing one import takes only its own document with it.
        second.clear()
        assertThat(File(a.uri).exists()).isTrue()
        assertThat(File(b.uri).exists()).isFalse()

        first.clear()
    }

    @Test
    fun anAbandonedImportIsEventuallySweptUp() {
        val abandoned = DocumentStaging(context)
        val stale = abandoned.stage(aFile("stale.pdf"))!!

        // Left alone, it stays: age is the only evidence that nobody is still using it.
        staging.sweepAbandoned()
        assertThat(File(stale.uri).exists()).isTrue()

        staging.sweepAbandoned(now = System.currentTimeMillis() + 2 * 60 * 60 * 1000)
        assertThat(File(stale.uri).exists()).isFalse()
    }

    @Test
    fun aProviderSuppliedNameCannotEscapeTheStagingDirectory() {
        // A display name is untrusted text that is about to become a filename.
        val hostile = File(context.filesDir, "hostile.pdf").apply { writeBytes(byteArrayOf(1)) }
        val staged = staging.stage(Uri.parse("file://${hostile.absolutePath}"))!!

        assertThat(staged.displayName).doesNotContain("/")
        assertThat(File(staged.uri).canonicalPath)
            .startsWith(File(context.noBackupFilesDir, "imports").canonicalPath)
    }
}
