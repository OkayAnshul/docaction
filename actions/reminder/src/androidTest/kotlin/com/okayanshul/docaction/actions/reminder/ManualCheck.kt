package com.okayanshul.docaction.actions.reminder

/**
 * A procedure a person drives, not a test the suite runs.
 *
 * Some behaviour cannot be observed from inside one instrumented run — the device has to
 * actually restart between the arrangement and the assertion. Marking those means the
 * automated suite neither fails on them nor, worse, reports them as skipped and green.
 *
 * Excluded via `notAnnotation` in this module's build file. Run one explicitly with:
 * `adb shell am instrument -w -e class '<Class>#<method>' <testPackage>/androidx.test.runner.AndroidJUnitRunner`
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class ManualCheck
