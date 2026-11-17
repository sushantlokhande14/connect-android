plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.connect.core.ui"
    buildFeatures { compose = true }
}

// Theme, shared components and the text shown for each kind of error. Every
// feature module depends on this and on :domain, and on nothing else.
dependencies {
    api(project(":domain"))
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.foundation)
    api(libs.compose.material3)
    api(libs.compose.ui.tooling.preview)
    api(libs.androidx.lifecycle.runtime.compose)
    api(libs.androidx.lifecycle.viewmodel.compose)
    api(libs.coil.compose)
    debugApi(libs.compose.ui.tooling)
}
