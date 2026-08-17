plugins {
    alias(libs.plugins.docaction.android.library)
}

android {
    namespace = "com.okayanshul.docaction.document.pdf"
}

dependencies {
    api(project(":domain"))

    // ADR-001 tier 2. Last upstream release 2023-01-02 — runs in the sandbox
    // process (ADR-002) because of it. See docs/12-privacy-security.md.
    implementation(libs.pdfbox.android)
    implementation(libs.kotlinx.coroutines.android)

    // The corpus test exercises real PDFs through the real engine on a real device.
    androidTestImplementation(project(":extraction"))
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.truth)
}
