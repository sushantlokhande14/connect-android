plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure Kotlin: no Android, no Retrofit, no Room. Everything else depends on
// this module; it depends on nothing but coroutines.
dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.javax.inject)

    testImplementation(project(":testing"))
}
