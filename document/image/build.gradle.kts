plugins {
    alias(libs.plugins.docaction.android.library)
}

android {
    namespace = "com.okayanshul.docaction.document.image"
}

dependencies {
    api(project(":domain"))

    // Unbundled: ~260KB in the APK, model fetched once via Play Services.
    implementation(libs.mlkit.text.recognition)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.truth)
}
