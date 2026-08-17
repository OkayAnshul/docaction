plugins {
    alias(libs.plugins.docaction.android.library)
    alias(libs.plugins.docaction.hilt)
}

android {
    namespace = "com.okayanshul.docaction.actions.calendar"
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":core:database"))
    implementation(project(":core:common"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.truth)
}
