package com.okayanshul.docaction.domain

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * Moves a [DocumentContent] across a process boundary.
 *
 * Hand-written rather than reflective, and that is the point. This is the return path from
 * code that has just parsed a hostile file, so the thing reading it is a trust boundary: a
 * general-purpose deserialiser would let a compromised sandbox choose which classes to
 * instantiate in the app process, which is the classic way a sandbox becomes a delivery
 * mechanism. This reads four primitive types and constructs exactly four known shapes.
 *
 * Everything is bounded. A malicious length prefix cannot make the reader allocate a
 * gigabyte, because every count is checked against a ceiling before anything is allocated —
 * an unbounded `ByteArray(readInt())` is how a decompression bomb turns into an OOM in the
 * process it was supposed to be kept out of.
 *
 * The format is deliberately dull: a magic number, a version, then counted, length-prefixed
 * records. There is no back-reference, no object graph and no polymorphism, so there is
 * nothing to be clever with.
 */
object DocumentCodec {

    private const val MAGIC = 0x44434131 // "DCA1"
    private const val VERSION = 1

    /**
     * Ceilings, not expectations.
     *
     * Sized well above anything a real document produces — the largest in the corpus is a few
     * thousand runs — and far below anything that would exhaust memory. A file that genuinely
     * exceeds these is refused rather than read, which is the same rule the input limits
     * follow.
     */
    private const val MAX_PAGES = 10_000
    private const val MAX_RUNS_PER_PAGE = 200_000
    private const val MAX_ISSUES = 10_000
    private const val MAX_TEXT_BYTES = 1 shl 20

    fun write(content: DocumentContent, out: OutputStream) {
        val data = DataOutputStream(out.buffered())
        data.writeInt(MAGIC)
        data.writeInt(VERSION)
        data.writeInt(content.format.ordinal)

        data.writeInt(content.pages.size)
        content.pages.forEach { page ->
            data.writeInt(page.index)
            data.writeFloat(page.widthPt)
            data.writeFloat(page.heightPt)
            data.writeInt(page.runs.size)
            page.runs.forEach { run ->
                data.writeUTF8(run.text)
                data.writeFloat(run.bounds.left)
                data.writeFloat(run.bounds.top)
                data.writeFloat(run.bounds.right)
                data.writeFloat(run.bounds.bottom)
                // A nullable float, without a boxed type crossing the boundary.
                data.writeBoolean(run.confidence != null)
                data.writeFloat(run.confidence ?: 0f)
                data.writeInt(run.origin.ordinal)
            }
        }

        data.writeInt(content.issues.size)
        content.issues.forEach { issue ->
            data.writeInt(issue.kind.ordinal)
            data.writeUTF8(issue.detail)
        }
        data.flush()
    }

    /**
     * Reads content back, or throws [Malformed].
     *
     * Every failure mode here is the same failure from the caller's point of view — the
     * sandbox did not return something we can use — so they collapse into one exception the
     * caller turns into `FailureReason.Corrupt`. Distinguishing "truncated" from "bad magic"
     * would only matter to an attacker probing the boundary.
     */
    fun read(input: InputStream): DocumentContent {
        val data = DataInputStream(input.buffered())
        try {
            if (data.readInt() != MAGIC) throw Malformed("not our format")
            if (data.readInt() != VERSION) throw Malformed("unknown version")

            val format = DocumentFormat.entries.at(data.readInt())

            val pages = data.readList(MAX_PAGES) {
                val index = data.readInt()
                val width = data.readFloat()
                val height = data.readFloat()
                val runs = data.readList(MAX_RUNS_PER_PAGE) {
                    TextRun(
                        text = data.readUTF8(),
                        bounds = BoundingBox(
                            left = data.readFloat(),
                            top = data.readFloat(),
                            right = data.readFloat(),
                            bottom = data.readFloat(),
                        ),
                        confidence = data.readBoolean().let { present ->
                            val value = data.readFloat()
                            if (present) value else null
                        },
                        origin = TextOrigin.entries.at(data.readInt()),
                    )
                }
                PageContent(index, width, height, runs)
            }

            val issues = data.readList(MAX_ISSUES) {
                Issue(IssueKind.entries.at(data.readInt()), data.readUTF8())
            }

            return DocumentContent(format, pages, issues)
        } catch (e: EOFException) {
            throw Malformed("truncated")
        } catch (e: IndexOutOfBoundsException) {
            throw Malformed("out of range")
        } catch (e: NegativeArraySizeException) {
            throw Malformed("negative length")
        }
    }

    /** The one exception this object throws. Carries no attacker-controlled text. */
    class Malformed(reason: String) : java.io.IOException(reason)

    private fun <T> List<T>.at(ordinal: Int): T =
        getOrNull(ordinal) ?: throw Malformed("unknown enum value")

    /**
     * `DataOutput.writeUTF` caps at 64 KB and a single spreadsheet cell can exceed that, so
     * strings carry their own byte length.
     */
    private fun DataOutputStream.writeUTF8(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readUTF8(): String {
        val size = readInt()
        // Checked before allocating, not after. This is the line that stops a hostile length
        // prefix turning into an OOM on this side of the boundary.
        if (size < 0 || size > MAX_TEXT_BYTES) throw Malformed("string too long")
        val bytes = ByteArray(size)
        readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private inline fun <T> DataInputStream.readList(limit: Int, item: () -> T): List<T> {
        val count = readInt()
        if (count < 0 || count > limit) throw Malformed("too many items")
        // No pre-sized allocation from an untrusted count: a claim of 200,000 items costs
        // nothing until that many are actually read.
        val out = ArrayList<T>()
        repeat(count) { out += item() }
        return out
    }
}
