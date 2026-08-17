plugins {
    alias(libs.plugins.docaction.android.library)
}

android {
    namespace = "com.okayanshul.docaction.document.sandbox"
    buildFeatures.aidl = true
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":document:pdf"))
    implementation(libs.kotlinx.coroutines.android)
}
