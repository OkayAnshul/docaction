package com.okayanshul.docaction.corpus

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * The whole corpus in six numbers, pinned.
 *
 * This is the honest replacement for the figure this project reported for weeks —
 * *"29 of 42 ready, 0 wrong outputs"* — which counted a document that produced nothing
 * exactly like one that produced twenty-three events, and so could not distinguish an engine
 * that works from one that has stopped answering.
 *
 * `produced` and `candidates` are the numbers that matter. A change that moves them is the
 * change worth reading about, and here it is one line of diff.
 */
class CorpusSummaryTest {

    @Test
    fun theCorpusTotalsMatchWhatWeExpect() {
        val results = CorpusRunner.documents().associateWith { CorpusRunner.run(it) }
        val summary = Golden.summarise(results)
        val expected = File("src/test/goldens/summary.json")

        println(summary.toJson())

        assertThat(expected.exists()).isTrue()
        assertThat(summary.toJson().trim()).isEqualTo(expected.readText().trim())
    }
}
