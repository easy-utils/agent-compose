import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    kotlin("plugin.serialization") version "2.4.20"
    id("org.jetbrains.compose")
    // Library-only Android target. AGP 9 removed support for applying
    // `com.android.library`/`com.android.application` together with the KMP
    // plugin; this is the supported replacement. The APK lives in :android.
    id("com.android.kotlin.multiplatform.library")
}

group = "com.agent"
version = "0.3.9"

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

kotlin {
    androidLibrary {
        namespace = "easy.agent.compose.shared"
        compileSdk = 37
        minSdk = 26
        // Android resource processing is opt-in with the KMP library plugin.
        // The module itself only uses android.R/android.provider constants, but
        // dependency AARs (activity-compose, appcompat) still need it enabled.
        androidResources.enable = true
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm("desktop")

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        binaries.executable()
        browser {
            val projectDirPath = project.projectDir.path
            commonWebpackConfig {
                outputFileName = "composeApp.js"
                devServer = (devServer ?: org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig.DevServer()).apply {
                    // Serve sources so webpack-dev-server resolves the resources.
                    static(projectDirPath + "/src/commonMain/composeResources")
                    port = 5603
                }
            }
        }
    }

    // Shared JVM source set: easy-rpc's OkHttp transport plus the app's CA trust.
    sourceSets {
        val jvmShared = create("jvmShared") {
            dependsOn(commonMain.get())
            dependencies {
                implementation("com.squareup.okhttp3:okhttp:4.12.0")
            }
        }
        // Web source set (wasmJs): the same typed client over the KMP Ktor/fetch
        // transport — no bespoke Connect-JSON codec.
        val wasmJsMain = getByName("wasmJsMain") {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
            }
        }
        val commonMain = getByName("commonMain") {
            dependencies {
                // Typed agent client + pbandk messages over easy-rpc; `api`
                // re-exports easy-rpc-kotlin (Transport/RPCError) and pbandk.
                api("io.github.easy-utils:agent-sdk-kotlin:0.18.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                // Icons: the unified Lucide set shared with the Flutter / WebUI /
                // SwiftUI clients (see tools/icons.py for the slot table). This
                // REPLACES compose.materialIconsExtended, a ~37MB desktop jar
                // holding every Material vector while the app referenced ~60.
                // material-icons-core still arrives transitively via material3
                // (DropdownMenu et al. draw their own glyphs from it).
                implementation("com.composables:icons-lucide-cmp:2.2.1")
                implementation(compose.ui)
                implementation(compose.components.resources)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
                // Mature multiplatform Markdown renderer (JetBrains
                // org.jetbrains:markdown GFM parser + Compose M3 components),
                // replacing the hand-rolled parser. 0.45.0 is the newest line;
                // 0.42+ AARs require compileSdk 37, which this module now uses.
                implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.45.0")
            }
        }
        val desktopMain = getByName("desktopMain") {
            dependsOn(jvmShared)
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation("org.xerial:sqlite-jdbc:3.47.1.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
            }
        }
        val androidMain = getByName("androidMain") {
            dependsOn(jvmShared)
            // No android-only deps here any more: the Activity/Compose runtime
            // live in :android, and this module is a plain AAR producer.
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.agent.app.MainKt"
        // Desktop has no R8 — the Compose plugin drives ProGuard instead
        // (`com.guardsquare:proguard-gradle`). Shrinking matters most for
        // material-icons-extended, a ~86 MB desktop jar holding every Material
        // vector while this app references ~52.
        buildTypes {
            release {
                proguard {
                    // 7.10.0 bundles proguard-core 9.4.0 → kotlin-metadata-jvm
                    // 2.4.0, which reads the Kotlin 2.4 metadata our stdlib
                    // ships (the plugin default 7.8.0 → metadata 2.1.0 cannot).
                    version.set("7.10.0")
                    // ProGuard needs a working java toolchain handle; on some
                    // CI/headless hosts `getStandardOutput()` returns null and
                    // the plugin aborts. `-PskipProguard` builds the release
                    // distributable without shrinking (bigger jar, same app).
                    isEnabled.set(!project.hasProperty("skipProguard"))
                    optimize.set(true)
                    obfuscate.set(false)
                    joinOutputJars.set(true)
                    configurationFiles.from(project.file("proguard-rules.pro"))
                }
            }
        }
        nativeDistributions {
            // Dmg on macOS, Deb on Linux (the packaging host controls which
            // format is actually produced); the appstore publishes the deb.
            targetFormats(TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "Easy Agent"
            packageVersion = "1.6.0"
            description = "Easy Agent (Compose Multiplatform)"
            vendor = "EasyLab"
            macOS {
                bundleID = "easy.agent.compose"
                iconFile.set(project.file("src/desktopMain/resources/app-icon.icns"))
            }
            linux {
                // Debian metadata; the appstore .deb is produced by
                // tool/package-deb.sh (the plugin's deb bundler cannot resolve
                // the main jar when ProGuard is skipped on headless hosts).
                packageName = "easy-agent-compose"
                debMaintainer = "dev@easylab.local"
                appCategory = "Utility"
                // jpackage wants a PNG for linux bundles.
                iconFile.set(project.file("src/desktopMain/resources/icon.png"))
            }
        }
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "com.agent.app.resources"
}

// Copy the runtime-loaded sqlite-wasm assets into the web distribution so a
// local `:wasmJsBrowserDistribution` matches what Dockerfile.web serves (the
// image also copies web/sqlite itself). Kept OUT of webpack on purpose: the
// module resolves `sqlite3.wasm` / the OPFS proxy relative to its own
// import.meta.url, so it must be served un-bundled.
tasks.matching { it.name == "wasmJsBrowserDistribution" }.configureEach {
    doLast {
        val src = file("web/sqlite")
        val dst = file("build/dist/wasmJs/productionExecutable/sqlite")
        if (src.isDirectory) {
            dst.mkdirs()
            src.listFiles()?.forEach { f ->
                f.copyTo(File(dst, f.name), overwrite = true)
            }
        }
    }
}
