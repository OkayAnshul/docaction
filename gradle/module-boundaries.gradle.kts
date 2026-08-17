/*
 * Enforces the dependency direction from docs/05-architecture.md § Dependency rules.
 *
 * "Document parsers must not know about Compose" is a build failure here, not a
 * code-review convention. Conventions erode; build failures don't.
 *
 * Run with:  ./gradlew checkModuleBoundaries
 * Also wired into :check for every module.
 */

/** module path -> dependency group prefixes it may not depend on */
val forbiddenGroups: Map<String, List<String>> = mapOf(
    // The extraction engine must stay JVM-testable with no emulator.
    ":domain" to listOf("com.android", "androidx", "com.google.android"),
    ":extraction" to listOf("com.android", "androidx", "com.google.android"),
    ":core:common" to listOf("com.android", "androidx", "com.google.android"),
    ":document:spreadsheet" to listOf("com.android", "androidx", "com.google.android"),
    ":document:csv" to listOf("com.android", "androidx", "com.google.android"),
    ":document:text" to listOf("com.android", "androidx", "com.google.android"),

    // Parsers and action executors must not reach into the UI layer.
    ":document:pdf" to listOf("androidx.compose"),
    ":document:image" to listOf("androidx.compose"),
    ":document:sandbox" to listOf("androidx.compose"),
    ":actions:calendar" to listOf("androidx.compose"),
    ":actions:reminder" to listOf("androidx.compose"),
    ":core:database" to listOf("androidx.compose"),
    ":core:settings" to listOf("androidx.compose"),
)

/** module path -> project paths it may not depend on */
val forbiddenProjects: Map<String, List<String>> = mapOf(
    // Calendar integration must not know about OCR or extraction internals.
    ":actions:calendar" to listOf(":extraction", ":document:pdf", ":document:sandbox"),
    ":actions:reminder" to listOf(":extraction", ":document:pdf", ":document:image"),
    // Extraction must not know how documents are read, only what they produce.
    ":extraction" to listOf(":document:pdf", ":document:sandbox", ":actions:calendar"),
    ":domain" to listOf(":extraction", ":document:pdf", ":document:sandbox", ":actions:calendar"),
)

private val declaringConfigurations = setOf(
    "api", "implementation", "compileOnly", "runtimeOnly",
    "debugImplementation", "releaseImplementation",
    "debugApi", "releaseApi",
)

/** Collected at configuration time so the task never reaches across projects at execution time. */
val violations = mutableListOf<String>()

gradle.projectsEvaluated {
    (forbiddenGroups.keys + forbiddenProjects.keys).distinct().forEach { path ->
        val module = rootProject.findProject(path) ?: return@forEach
        val bannedGroups = forbiddenGroups[path].orEmpty()
        val bannedProjects = forbiddenProjects[path].orEmpty()

        module.configurations
            .filter { it.name in declaringConfigurations }
            .forEach { configuration ->
                configuration.dependencies.forEach { dependency ->
                    when (dependency) {
                        is ProjectDependency -> {
                            val target = dependency.path
                            if (target in bannedProjects) {
                                violations += "$path (${configuration.name}) -> project $target"
                            }
                        }

                        else -> {
                            val group = dependency.group.orEmpty()
                            val hit = bannedGroups.firstOrNull { group == it || group.startsWith("$it.") }
                            if (hit != null) {
                                violations += "$path (${configuration.name}) -> $group:${dependency.name}"
                            }
                        }
                    }
                }
            }
    }
}

val checkModuleBoundaries = tasks.register("checkModuleBoundaries") {
    group = "verification"
    description = "Fails if a module depends on something the architecture forbids."

    // Capture the list itself; nothing reaches into other projects at execution time.
    val found = violations
    doLast {
        if (found.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Module boundary violations (docs/05-architecture.md § Dependency rules):")
                    found.sorted().forEach { appendLine("  ✗ $it") }
                    appendLine()
                    appendLine("Either remove the dependency, or change the architecture on purpose")
                    appendLine("by editing gradle/module-boundaries.gradle.kts and recording why.")
                }
            )
        }
        logger.lifecycle("Module boundaries OK.")
    }
}

subprojects {
    tasks.matching { it.name == "check" }.configureEach {
        dependsOn(checkModuleBoundaries)
    }
}
