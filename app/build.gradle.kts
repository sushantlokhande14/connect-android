plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// The prod flavor talks to Firebase. Its config file is per developer and is not in the repository,
// so the Google Services plugin is applied only where the file exists. Without it prod still compiles.
if (file("google-services.json").exists()) {
    apply(plugin = libs.plugins.google.services.get().pluginId)
}

android {
    namespace = "app.connect.android"

    defaultConfig {
        applicationId = "app.connect.android"
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "app.connect.android.HiltTestRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // demo: the backend runs inside the app (no server, no Firebase project needed) and pushes are simulated.
    // prod: a real base URL and Firebase Cloud Messaging.
    flavorDimensions += "backend"
    productFlavors {
        create("demo") {
            dimension = "backend"
            applicationIdSuffix = ".demo"
            versionNameSuffix = "-demo"
        }
        create("prod") {
            dimension = "backend"
            buildConfigField("String", "API_BASE_URL", "\"https://api.connect.example/\"")
        }
    }

    // The end-to-end tests are written once and run twice: on the JVM under Robolectric (fast, every push)
    // and on an emulator (slower, the real framework). They drive the demo backend, so they belong to
    // the demo flavor's test source sets.
    sourceSets {
        getByName("testDemo") { java.srcDir("src/sharedTest/kotlin") }
        getByName("androidTestDemo") { java.srcDir("src/sharedTest/kotlin") }
    }
}

dependencies {
    implementation(project(":data"))
    implementation(project(":core:ui"))
    implementation(project(":feature:auth"))
    implementation(project(":feature:discovery"))
    implementation(project(":feature:profile"))
    implementation(project(":feature:chat"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    "demoImplementation"(project(":fakebackend"))
    "prodImplementation"(platform(libs.firebase.bom))
    "prodImplementation"(libs.firebase.messaging)

    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(project(":testing"))
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.test.rules)
    testImplementation(libs.androidx.test.espresso.core)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)

    androidTestImplementation(project(":testing"))
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
}
