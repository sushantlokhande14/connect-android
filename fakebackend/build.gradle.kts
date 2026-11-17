plugins {
    alias(libs.plugins.kotlin.jvm)
}

// An in-memory implementation of the Connect REST API with a latency model and
// fault injection. The demo build runs it inside the app; tests and the
// screen-load benchmark run it behind MockWebServer.
dependencies {
    api(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
