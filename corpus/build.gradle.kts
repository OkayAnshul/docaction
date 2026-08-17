plugins {
    alias(libs.plugins.docaction.jvm.library)
}

/*
 * Test support only. Nothing here ships in the app — it is depended on by `:extraction`'s
 * tests and `:app`'s instrumented tests, never by a production source set.
 *
 * It is a module rather than test fixtures because both sides need it: the device writes
 * snapshots, the JVM reads them, and a single codec is the whole point. Two copies would
 * drift on exactly the documents that are hard.
 */
dependencies {
    api(project(":domain"))
    api(libs.kotlinx.serialization.json)

    // The gate itself lives in this module's test source set rather than in :extraction's,
    // because what it tests is the *pipeline* — reader port, finder and choke point
    // together — not any one module. Test-only, so it crosses no production boundary.
    testImplementation(project(":extraction"))
    testImplementation(project(":document:spreadsheet"))
    testImplementation(project(":document:csv"))
    testImplementation(project(":document:text"))
    testImplementation(libs.kotlinx.coroutines.core)
}

/**
 * Rewrites every golden from what the engine currently produces.
 *
 * Deliberately a task and not a `-Pupdate` flag on the test run: regenerating is a decision,
 * and it should be visible in shell history and impossible to do by accident while chasing a
 * red build. CI never runs it.
 *
 * The workflow is: run it, read the diff, and only then commit. A golden diff nobody reads is
 * worse than no goldens, because it looks like coverage.
 */
val regenerateGoldens = tasks.register<Test>("regenerateGoldens") {
    group = "verification"
    description = "Rewrites corpus goldens from current engine behaviour. Read the diff before committing."

    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter { includeTestsMatching("com.okayanshul.docaction.corpus.GoldenWriter") }
    outputs.upToDateWhen { false }
}

tasks.named<Test>("test") {
    // Regeneration must never be a side effect of running the suite.
    //
    // `mustRunAfter` alone did not achieve that — it orders the two tasks but leaves
    // GoldenWriter in this task's own discovery, so `:corpus:test` ran it 4.6s *after*
    // CorpusGoldenTest and rewrote the very goldens the comparison had just used. That makes
    // the gate self-approving: any behaviour change is compared against stale goldens once,
    // then written into them, and every run after that is green. The exclusion is what
    // actually keeps regeneration a deliberate act.
    filter { excludeTestsMatching("com.okayanshul.docaction.corpus.GoldenWriter") }
    mustRunAfter(regenerateGoldens)
}
