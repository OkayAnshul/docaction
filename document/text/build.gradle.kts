plugins {
    alias(libs.plugins.docaction.jvm.library)
}

/*
 * Pure JVM. Plain text needs nothing from Android, and keeping it off the classpath is what
 * lets the format be tested without an emulator.
 */
dependencies {
    api(project(":domain"))
}
