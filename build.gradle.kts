// Root build.gradle.kts — AGP 9.x project
// NOTE: org.jetbrains.kotlin.android is intentionally absent.
//   AGP 9.0+ includes built-in Kotlin support; applying kotlin.android would
//   cause "plugin is no longer required" build failure. See:
//   https://developer.android.com/build/migrate-to-built-in-kotlin
plugins {
    id("com.android.application")         version "9.1.1"   apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false
}

