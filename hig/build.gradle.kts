plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.convx.music.hig"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.util)

    // Upstream compose-hig deps for the vendored Cupertino* components (Liquid Glass
    // rendering). `api` not `implementation`: Backdrop/ContinuousRoundedRectangle types
    // appear directly in this module's public function signatures, so consumers need them.
    api(libs.hig.backdrop)
    api(libs.hig.capsule)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.atomicfu)
}
