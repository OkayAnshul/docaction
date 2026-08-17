plugins {
    alias(libs.plugins.docaction.android.library)
    alias(libs.plugins.docaction.hilt)
}

android {
    namespace = "com.okayanshul.docaction.core.database"
}

dependencies {
    implementation(project(":domain"))

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.kotlinx.coroutines.android)

    // The migration is verified against real database files, not asserted in prose.
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.truth)
}


ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

/**
 * Puts Room's exported schemas into the instrumented test APK.
 *
 * `MigrationTestHelper` reads them at runtime to build the *old* database, so a migration is
 * checked against the schema Room actually generated rather than one written out by hand
 * beside it. Added through the variant `sources` API because AGP 9 no longer accepts the
 * older `sourceSets[...].assets.srcDir` form.
 */
androidComponents {
    onVariants { variant ->
        variant.androidTest?.sources?.assets?.addStaticSourceDirectory("$projectDir/schemas")
    }
}
