package com.okayanshul.docaction.corpus

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Every document, one test each, against a written-down expectation.
 *
 * One test per document rather than one loop over all of them, because the old shape
 * (`names.forEach` inside a single `@Test`) collapsed 42 documents into one pass/fail row:
 * the first `error(...)` aborted every remaining document, and a regression in one file was
 * invisible in the XML report. A failure here names the file.
 *
 * To accept a change: `./gradlew :corpus:regenerateGoldens`, then read the diff before
 * committing it. That is the whole review process, and it only works because the goldens are
 * normalised — see [Golden].
 */
@RunWith(Parameterized::class)
class CorpusGoldenTest(private val document: String) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun documents(): List<String> = CorpusRunner.documents()

        /** Written by the regenerate task; read here. */
        val goldens: File = File("src/test/goldens")
    }

    @Test
    fun matchesItsGolden() {
        val actual = Golden.of(document, CorpusRunner.run(document))
        val expected = File(goldens, "$document.expected.json")

        // A document with no expectation must fail, not pass quietly. Adding a file to the
        // corpus without recording what it should produce is how a suite stops meaning
        // anything.
        assertThat(expected.exists()).isTrue()
        assertThat(actual.trim()).isEqualTo(expected.readText().trim())
    }
}
