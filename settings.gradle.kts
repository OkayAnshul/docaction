pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "DocAction"

include(":app")

// Pure JVM — the extraction engine. See docs/05-architecture.md § Module architecture.
include(":core:common")
include(":domain")
include(":extraction")
// ZIP + SAX only, so it stays JVM-testable without an emulator. See ADR-009.
include(":document:spreadsheet")
// A delimited file declares its own grid, so it reuses the spreadsheet engine wholesale.
include(":document:csv")
// No layout at all, so it goes to the prose reader and never to table reconstruction.
include(":document:text")

// Test support: corpus snapshots and golden files. Never shipped.
include(":corpus")

// Android infrastructure
include(":core:designsystem")
include(":core:database")
include(":core:settings")
include(":document:pdf")
include(":document:image")
include(":document:sandbox")
include(":actions:calendar")
include(":actions:reminder")
