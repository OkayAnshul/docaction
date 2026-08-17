package com.okayanshul.docaction.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * The return path from code that has just parsed a hostile file.
 *
 * Round-tripping is the easy half. The half worth testing is what happens when the bytes are
 * *not* ours: a compromised sandbox controls every length prefix and every enum ordinal on
 * this stream, and the reader has to refuse all of it without allocating anything large or
 * constructing anything unexpected.
 */
class DocumentCodecTest {

    private fun roundTrip(content: DocumentContent): DocumentContent {
        val bytes = ByteArrayOutputStream()
        DocumentCodec.write(content, bytes)
        return DocumentCodec.read(ByteArrayInputStream(bytes.toByteArray()))
    }

    private fun run(text: String, confidence: Float? = null) = TextRun(
        text = text,
        bounds = BoundingBox(1f, 2f, 3f, 4f),
        confidence = confidence,
        origin = TextOrigin.PdfTextLayer,
    )

    // --- it survives the trip ---

    @Test
    fun `a document comes back exactly as it went in`() {
        val original = DocumentContent(
            format = DocumentFormat.Pdf,
            pages = listOf(
                PageContent(0, 595f, 842f, listOf(run("Monday", 0.94f), run("09:00"))),
                PageContent(1, 595f, 842f, listOf(run("Tuesday"))),
            ),
            issues = listOf(Issue(IssueKind.NoTextOnPage, "page 2 had no text")),
        )

        assertThat(roundTrip(original)).isEqualTo(original)
    }

    @Test
    fun `a missing confidence stays missing rather than becoming zero`() {
        // The distinction the whole confidence model rests on: a PDF text layer is not a
        // recognition result, and 0.0 would read as "recognised, badly".
        val back = roundTrip(
            DocumentContent(DocumentFormat.Pdf, listOf(PageContent(0, 1f, 1f, listOf(run("x"))))),
        )
        assertThat(back.pages.single().runs.single().confidence).isNull()
    }

    @Test
    fun `a confidence of zero survives as zero`() {
        val back = roundTrip(
            DocumentContent(
                DocumentFormat.Image,
                listOf(PageContent(0, 1f, 1f, listOf(run("x", confidence = 0f)))),
            ),
        )
        assertThat(back.pages.single().runs.single().confidence).isEqualTo(0f)
    }

    @Test
    fun `text longer than the old 64k writeUTF limit survives`() {
        // A single spreadsheet cell can exceed it, and DataOutput.writeUTF would throw.
        val long = "x".repeat(100_000)
        val back = roundTrip(
            DocumentContent(
                DocumentFormat.Xlsx,
                listOf(PageContent(0, 1f, 1f, listOf(run(long)))),
            ),
        )
        assertThat(back.pages.single().runs.single().text).hasLength(100_000)
    }

    @Test
    fun `an empty document is still a document`() {
        assertThat(roundTrip(DocumentContent(DocumentFormat.PlainText, emptyList())).pages)
            .isEmpty()
    }

    @Test
    fun `non-ascii text is not mangled`() {
        val back = roundTrip(
            DocumentContent(
                DocumentFormat.PlainText,
                listOf(PageContent(0, 1f, 1f, listOf(run("परीक्षा 09:00 — कक्ष K10")))),
            ),
        )
        assertThat(back.pages.single().runs.single().text).isEqualTo("परीक्षा 09:00 — कक्ष K10")
    }

    // --- it refuses everything else ---

    private fun expectMalformed(build: DataOutputStream.() -> Unit) {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply(build).flush()
        try {
            DocumentCodec.read(ByteArrayInputStream(bytes.toByteArray()))
            throw AssertionError("expected the reader to refuse these bytes")
        } catch (expected: DocumentCodec.Malformed) {
            // The only exception this boundary is allowed to produce.
        }
    }

    @Test
    fun `bytes that are not ours are refused`() {
        expectMalformed { writeInt(0xDEADBEEF.toInt()) }
    }

    @Test
    fun `a future version is refused rather than guessed at`() {
        expectMalformed {
            writeInt(0x44434131)
            writeInt(99)
        }
    }

    @Test
    fun `a truncated stream is refused`() {
        val bytes = ByteArrayOutputStream()
        DocumentCodec.write(
            DocumentContent(DocumentFormat.Pdf, listOf(PageContent(0, 1f, 1f, listOf(run("x"))))),
            bytes,
        )
        val half = bytes.toByteArray().copyOf(bytes.size() / 2)

        try {
            DocumentCodec.read(ByteArrayInputStream(half))
            throw AssertionError("expected the reader to refuse a truncated stream")
        } catch (expected: DocumentCodec.Malformed) {
            // As intended.
        }
    }

    @Test
    fun `an absurd page count allocates nothing`() {
        // The attack this format exists to survive: a length prefix claiming two billion
        // items. Checked before allocating, so it costs a comparison rather than the heap.
        expectMalformed {
            writeInt(0x44434131)
            writeInt(1)
            writeInt(DocumentFormat.Pdf.ordinal)
            writeInt(Int.MAX_VALUE)
        }
    }

    @Test
    fun `an absurd string length allocates nothing`() {
        expectMalformed {
            writeInt(0x44434131)
            writeInt(1)
            writeInt(DocumentFormat.Pdf.ordinal)
            writeInt(1) // one page
            writeInt(0)
            writeFloat(1f)
            writeFloat(1f)
            writeInt(1) // one run
            writeInt(Int.MAX_VALUE) // …whose text claims to be 2 GB
        }
    }

    @Test
    fun `a negative count is refused rather than wrapping`() {
        expectMalformed {
            writeInt(0x44434131)
            writeInt(1)
            writeInt(DocumentFormat.Pdf.ordinal)
            writeInt(-1)
        }
    }

    @Test
    fun `an enum ordinal we do not have is refused`() {
        // Not clamped to a default. A format we cannot name is a stream we do not understand.
        expectMalformed {
            writeInt(0x44434131)
            writeInt(1)
            writeInt(999)
        }
    }
}
