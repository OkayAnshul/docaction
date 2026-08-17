package com.okayanshul.docaction.diagnostic

/**
 * A tool a person runs, not a test the suite runs.
 *
 * `CorpusCapture` regenerates checked-in snapshots; running it automatically would mean the
 * suite quietly rewrote its own inputs, which is the opposite of a regression gate. Excluded
 * via `notAnnotation` in the app's build file, and invoked explicitly:
 *
 * `tools/capture-corpus.sh`
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class ManualTool
