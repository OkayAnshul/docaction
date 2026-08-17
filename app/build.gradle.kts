plugins {
    alias(libs.plugins.docaction.android.application)
    alias(libs.plugins.docaction.android.compose)
    alias(libs.plugins.docaction.hilt)
}

android {
    namespace = "com.okayanshul.docaction"

    // Robolectric needs real resources to inflate a Compose host.
    testOptions.unitTests.isIncludeAndroidResources = true

    defaultConfig {
        // Corpus capture rewrites checked-in snapshots. A suite that regenerates its own
        // inputs is not a gate, so it is annotated and excluded here and run deliberately
        // via tools/capture-corpus.sh.
        testInstrumentationRunnerArguments["notAnnotation"] =
            "com.okayanshul.docaction.diagnostic.ManualTool"

        applicationId = "com.okayanshul.docaction"
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":extraction"))
    implementation(project(":core:common"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:database"))
    implementation(project(":document:pdf"))
    implementation(project(":document:image"))
    implementation(project(":document:spreadsheet"))
    implementation(project(":document:csv"))
    implementation(project(":document:text"))
    implementation(project(":document:sandbox"))
    implementation(project(":actions:calendar"))
    implementation(project(":actions:reminder"))
    implementation(project(":core:settings"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // Three icons, for the navigation bar. R8 strips the rest of the set in release; the
    // alternative is hand-drawn vectors for glyphs the platform already ships.
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    // Screen behaviour is asserted through semantics, not pixels, so it belongs in the
    // fast JVM suite — and Espresso cannot drive API 36 (InputManager.getInstance is gone).
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.truth)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Corpus snapshots: the device writes them, the JVM gate in :extraction reads them.
    androidTestImplementation(project(":corpus"))
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.truth)
}
