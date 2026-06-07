plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace  = "com.gguf.ipc"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.gguf.ipc"
        minSdk        = 27
        targetSdk     = 36
        versionCode   = 5
        versionName   = "5.0-ULTRA"

        externalNativeBuild {
            cmake {
                // High-performance CPU flags for S23 FE
                cppFlags("-std=c++17 -O3 -mfloat-abi=softfp -mfpu=neon-vfpv4")
                arguments("-DANDROID_STL=c++_shared", "-DGGML_OPENMP=OFF", "-DGGML_VULKAN=OFF")
                abiFilters += "arm64-v8a"
            }
        }
    }
    buildFeatures { compose = true }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2026.05.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    
    // CRITICAL: This library provides Theme.Material3.DayNight.NoActionBar
    implementation("com.google.android.material:material:1.12.0")
}
