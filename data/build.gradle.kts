plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "app.connect.data"
}

dependencies {
    api(project(":domain"))
    api(libs.okhttp)
    api(libs.retrofit)
    api(libs.kotlinx.serialization.json)
    api(libs.androidx.datastore.preferences)
    api(libs.room.runtime)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp.logging)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(project(":testing"))
    testImplementation(project(":fakebackend"))
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
}

// The screen-load benchmark is a Robolectric test that runs the real Retrofit,
// OkHttp and Room stack. It is excluded from the normal test task because it
// takes minutes and its result is a report, not a pass/fail.
tasks.withType<Test>().configureEach {
    val benchmark = providers.gradleProperty("benchmark").isPresent
    if (benchmark) {
        filter { includeTestsMatching("*ScreenLoadBenchmark") }
        systemProperty("benchmark.out", rootProject.layout.buildDirectory.dir("benchmark").get().asFile.absolutePath)
        systemProperty("benchmark.runs", providers.gradleProperty("benchmarkRuns").getOrElse("30"))
        maxParallelForks = 1
        outputs.upToDateWhen { false }
    } else {
        filter { excludeTestsMatching("*ScreenLoadBenchmark") }
    }
}
