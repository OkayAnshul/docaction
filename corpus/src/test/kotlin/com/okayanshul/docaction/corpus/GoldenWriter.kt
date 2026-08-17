package com.okayanshul.docaction.corpus

import java.io.File
import org.junit.Test

/**
 * Writes the goldens. Runs only from `:corpus:regenerateGoldens`, never from `test`.
 *
 * It is shaped as a test because that is the cheapest way to get the corpus on the classpath
 * with the same wiring the gate uses — running the real thing rather than a parallel copy of
 * it is the entire point, and a separate `main` would drift.
 */
class GoldenWriter {

    @Test
    fun writeEveryGolden() {
        val out = File("src/test/goldens").apply { mkdirs() }
        val results = CorpusRunner.documents().associateWith { CorpusRunner.run(it) }

        results.forEach { (document, result) ->
            File(out, "$document.expected.json").writeText(Golden.of(document, result) + "\n")
        }
        File(out, "summary.json").writeText(Golden.summarise(results).toJson() + "\n")

        println("GOLDENS wrote ${results.size} documents into ${out.absolutePath}")
        println(Golden.summarise(results).toJson())
    }
}
