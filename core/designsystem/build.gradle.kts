plugins {
    alias(libs.plugins.docaction.android.library)
    alias(libs.plugins.docaction.android.compose)
}

android {
    namespace = "com.okayanshul.docaction.core.designsystem"

    // Robolectric needs real resources to inflate a Compose host.
    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    api(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.core.ktx)

    // These assert semantics and copy, not pixels, so they belong in the fast JVM suite —
    // and Espresso cannot drive API 36 anyway (InputManager.getInstance is gone).
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
