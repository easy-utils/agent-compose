// The Android application shell.
//
// Deliberately a SEPARATE Gradle module from `compose-app`: since AGP 9 the
// `com.android.application` plugin refuses to coexist with the Kotlin
// Multiplatform plugin, and Google's replacement
// (`com.android.kotlin.multiplatform.library`) is library-only. The documented
// migration is therefore to extract the Android *application* (manifest, launcher
// activity, resources, signing, R8) into its own module and consume the shared
// KMP code as an AAR.
plugins {
    id("com.android.application")
    // AGP 9 has built-in Kotlin support, so `org.jetbrains.kotlin.android` must
    // NOT be applied (it would fail with "no longer required since AGP 9.0").
    // Only the Compose compiler plugin is needed on top.
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "easy.agent.compose"
    compileSdk = 37

    defaultConfig {
        applicationId = "easy.agent.compose"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "0.1.1"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // AGP 9's bundled lint carries a Kotlin UAST older than the 2.4 stdlib the
    // dependency graph resolves, which produces bogus "incompatible version of
    // Kotlin" diagnostics. R8 is unaffected, so skip release lint.
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    buildTypes.getByName("release") {
        // R8: strips the ~11k unreferenced Material icons that
        // compose.materialIconsExtended ships (this app uses ~52), plus the
        // unused parts of Compose/protobuf/okhttp. See proguard-rules.pro.
        isMinifyEnabled = true
        isShrinkResources = true
        proguardFiles(
            getDefaultProguardFile("proguard-android-optimize.txt"),
            rootProject.file("proguard-rules.pro"),
        )
        signingConfig = signingConfigs.getByName("debug")
    }
}

dependencies {
    // The shared KMP module (compose UI + agent transport) as an AAR.
    // The root project IS the KMP module (:\) — see settings.gradle.kts.
    implementation(project(":"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
