plugins {
    alias(libs.plugins.docaction.android.library)
    alias(libs.plugins.docaction.hilt)
}

android {
    namespace = "com.okayanshul.docaction.actions.reminder"

    defaultConfig {
        // Reboot behaviour needs a real restart between arranging and asserting, which no
        // single instrumented run can do. Those checks are annotated and excluded here, so
        // the suite never fails on half a procedure — and never reports one as skipped and
        // green, which would be worse.
        testInstrumentationRunnerArguments["notAnnotation"] =
            "com.okayanshul.docaction.actions.reminder.ManualCheck"
    }
}

dependencies {
    api(project(":domain"))
    implementation(project(":core:database"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
