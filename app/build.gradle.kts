plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace  = "com.gguf.ipc"
    compileSdk = 35 
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.gguf.ipc"
        minSdk        = 27
        targetSdk     = 35
        versionCode   = 5
        versionName   = "5.0-ULTRA"

        externalNativeBuild {
            cmake {
                // FIXED: Removed -mfloat-abi (illegal for 64-bit)
                cppFlags("-std=c++17 -O3 -fno-stack-protector")
                arguments("-DANDROID_STL=c++_shared", "-DGGML_OPENMP=OFF", "-DGGML_VULKAN=OFF")
                abiFilters += "arm64-v8a"
            }
        }
    }
    buildFeatures { compose = true }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2026.05.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.material:material:1.12.0")
}
