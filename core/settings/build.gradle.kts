plugins {
    alias(libs.plugins.docaction.android.library)
}

android {
    namespace = "com.okayanshul.docaction.core.settings"
}

dependencies {
    api(project(":domain"))
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
