plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Fakes and rules shared by every module's tests. Pure Kotlin so both JVM and
// Android test source sets can use it.
dependencies {
    api(project(":domain"))
    api(libs.junit)
    api(libs.truth)
    api(libs.turbine)
    api(libs.kotlinx.coroutines.test)
}
