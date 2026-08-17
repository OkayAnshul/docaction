import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.version(name: String): String =
    findVersion(name).get().requiredVersion

/** One JVM target across every module, so JVM libraries stay consumable by Android modules. */
internal val JAVA_VERSION = JavaVersion.VERSION_17
internal val JVM_TARGET = JvmTarget.JVM_17

/**
 * Settings shared by every Android module. Note AGP 9's [CommonExtension] no longer
 * carries type parameters and no longer exposes `defaultConfig` — minSdk/targetSdk are
 * set by the application/library plugins against their own extension types.
 */
internal fun Project.configureAndroidCommon(extension: CommonExtension) {
    extension.compileSdk = libs.version("compileSdk").toInt()
    extension.compileSdkMinor = libs.version("compileSdkMinor").toInt()

    extension.compileOptions.sourceCompatibility = JAVA_VERSION
    extension.compileOptions.targetCompatibility = JAVA_VERSION

    extension.packaging.resources.excludes.addAll(
        listOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
            "/META-INF/LICENSE*",
            "/META-INF/NOTICE*",
        )
    )

    extensions.getByType<KotlinAndroidProjectExtension>().compilerOptions {
        jvmTarget.set(JVM_TARGET)
    }
}

internal fun Project.configureKotlinJvm() {
    extensions.getByType<KotlinJvmProjectExtension>().compilerOptions {
        jvmTarget.set(JVM_TARGET)
    }
}
