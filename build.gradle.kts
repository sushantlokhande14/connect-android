import com.android.build.gradle.BaseExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.google.services) apply false
}

// One place for the settings every module shares, so module build files only
// state what is specific to them.
subprojects {
    val configureAndroid: BaseExtension.() -> Unit = {
        compileSdkVersion(35)
        defaultConfig {
            minSdk = 26
            targetSdk = 35
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
        testOptions {
            unitTests {
                isIncludeAndroidResources = true
                isReturnDefaultValues = true
            }
        }
        packagingOptions {
            resources.excludes += setOf(
                "META-INF/{AL2.0,LGPL2.1}",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
            )
        }
    }
    plugins.withId("com.android.application") { extensions.configure<BaseExtension>(configureAndroid) }
    plugins.withId("com.android.library") { extensions.configure<BaseExtension>(configureAndroid) }

    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.add("-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi")
        }
    }
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
    }

    tasks.withType<Test>().configureEach {
        // Scenario ids are printed by the tests; keep stdout in the XML reports
        // so tools/scenario_coverage.py can cross-check them.
        testLogging { events("failed") }
        maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
    }
}
