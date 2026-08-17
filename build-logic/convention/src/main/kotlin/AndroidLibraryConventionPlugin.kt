import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")

        extensions.configure<LibraryExtension> {
            configureAndroidCommon(this)
            defaultConfig {
                minSdk = libs.version("minSdk").toInt()
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
            testOptions.targetSdk = libs.version("targetSdk").toInt()
        }

        // AGP creates a connected-test task for every library whether or not it has any
        // instrumented sources, and the empty ones then fail the whole run by producing no
        // results file to parse. Disabling them keeps `./gradlew connectedDebugAndroidTest`
        // — the corpus regression command — usable from the root.
        extensions.configure<com.android.build.api.variant.LibraryAndroidComponentsExtension> {
            beforeVariants { variant ->
                if (!file("src/androidTest").exists()) variant.androidTest.enable = false
            }
        }

        dependencies {
            add("testImplementation", libs.findLibrary("junit").get())
            add("testImplementation", libs.findLibrary("truth").get())
            add("testImplementation", libs.findLibrary("kotlinx-coroutines-test").get())
        }
    }
}
