# R8 / ProGuard rules for the agent client (Android + desktop).
#
# The big win here is shrinking compose.materialIconsExtended: it ships every
# Material vector (~11k classes across 5 styles on desktop, 35 MB as an AAR on
# Android) while this app references only ~55 of them. Both R8 (Android) and
# ProGuard (desktop) remove the unreferenced ones automatically — which is why
# the dependency is kept instead of hand-rolling ImageVectors.

# ---------------------------------------------------------------------------
# protobuf-javalite
#
# Kept for the generated agent SDK's javalite descriptors (unused now that the
# app runs on pbandk; harmless if the artifact is absent).
# ---------------------------------------------------------------------------
-keep class com.google.protobuf.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
    <init>();
}
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite$Builder {
    <fields>;
}

# ---------------------------------------------------------------------------
# okhttp / okio
# ---------------------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---------------------------------------------------------------------------
# pbandk
#
# pbandk is a pure-Kotlin protobuf runtime (no reflection-based message
# registration), so shrinking is safe. Silences the optional codegen markers.
# ---------------------------------------------------------------------------
-dontwarn pbandk.**

# ---------------------------------------------------------------------------
# sqlite-jdbc (desktop local mirror)
#
# Loaded via Class.forName("org.sqlite.JDBC"); the JDBC entry point and its
# `java.sql.Driver` service file must not be stripped/renamed.
# ---------------------------------------------------------------------------
-keep class org.sqlite.** { *; }
-keep class org.sqlite.JDBC { *; }
-dontwarn org.sqlite.**
-dontwarn java.sql.**
-dontwarn javax.sql.**

# ---------------------------------------------------------------------------
# Kotlin 2.x compiler-synthesised types
#
# `kotlin.concurrent.atomics.AtomicInt/Long/Reference/...` are @JvmInline value
# classes declared in `atomics.kotlin_builtins`; the compiler erases them to
# `java.util.concurrent.atomic.*` so no class file exists. They (and the
# internal `kotlin.jvm.internal.EnhancedNullability` annotation) still appear in
# `kotlin.Metadata` signatures, which ProGuard reads when building its Kotlin
# model, so it warns about missing classes. Nothing is needed at runtime.
# ---------------------------------------------------------------------------
-dontwarn kotlin.concurrent.atomics.**
-dontwarn kotlin.jvm.internal.**

# ---------------------------------------------------------------------------
# multiplatform-markdown-renderer-m3 (JetBrains org.jetbrains:markdown parser)
#
# The library exposes a public @Composable facade only, so R8/ProGuard shrink
# it normally; silence the optional integrations it references.
# ---------------------------------------------------------------------------
-dontwarn org.intellij.markdown.**
-dontwarn org.jetbrains.**

# ---------------------------------------------------------------------------
# Kotlin / coroutines: the Compose desktop rules already keep kotlin.** and the
# coroutines rules ship inside the artifact. Just silence the optional deps.
# ---------------------------------------------------------------------------
-dontwarn java.lang.instrument.**
-dontwarn sun.misc.**
-dontwarn org.graalvm.**

# Desktop runs on a jlink-produced runtime that only contains a subset of the
# JDK; keep javac-generated noise quiet for the modules we do not ship.
-dontwarn java.beans.**
-dontwarn javax.script.**
