plugins {
    // AGP 9.1.1 — compatible with Gradle 9.5.1
    // The standalone org.jetbrains.kotlin.android plugin is removed in AGP 9.x.
    // Kotlin is now bundled directly inside AGP (KGP 2.2.10).
    id("com.android.application") version "9.1.1" apply false
}
