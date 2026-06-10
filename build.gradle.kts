plugins {
    id("com.android.application")            version "9.1.1"  apply false
    // AGP 9.x has built-in Kotlin support; compose plugin is still required.
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
}
