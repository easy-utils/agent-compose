pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("org.jetbrains.kotlin.multiplatform") version "2.4.20"
        id("org.jetbrains.kotlin.android") version "2.4.20"
        id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
        id("org.jetbrains.compose") version "1.12.0"
        id("com.android.application") version "9.4.0"
        id("com.android.kotlin.multiplatform.library") version "9.4.0"
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/easy-utils/agent-sdk-kotlin")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: ""
                password = System.getenv("GITHUB_TOKEN") ?: ""
            }
        }
        maven {
            url = uri("https://maven.pkg.github.com/easy-utils/easy-rpc-kotlin")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: ""
                password = System.getenv("GITHUB_TOKEN") ?: ""
            }
        }
    }
}

rootProject.name = "agent-compose"

// The Android application shell is its own module because AGP 9 does not allow
// `com.android.application` alongside the Kotlin Multiplatform plugin; the KMP
// module (this root project) now uses `com.android.kotlin.multiplatform.library`
// and produces an AAR that the app consumes.
//
// The module is only wired in when an Android SDK is available: the macOS
// packaging host used for the desktop DMG has no SDK, and configuring an
// `com.android.application` project without one fails the whole build even when
// only desktop tasks are requested.
val hasAndroidSdk = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    file("local.properties").exists()
if (hasAndroidSdk) {
    include(":android")
} else {
    logger.lifecycle("Android SDK not found - skipping :android (desktop/web builds only)")
}
