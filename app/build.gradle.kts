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
        versionName   = "5.0-PRO"

        externalNativeBuild {
            cmake {
                // EXTREME OPTIMIZATION: ThinLTO + Vectorization
                cppFlags("-std=c++17 -O3 -flto=thin -march=armv8.4a+dotprod+crc -fno-stack-protector")
                arguments(
                    "-DANDROID_STL=c++_shared",
                    "-DGGML_VULKAN=ON",
                    "-DGGML_VULKAN_MEMORY_MODEL=2", // Optimized Vulkan memory
                    "-DGGML_OPENMP=OFF"
                )
                abiFilters += "arm64-v8a"
            }
        }
    }

    buildFeatures { compose = true }
    
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }

    packaging {
        jniLibs { useLegacyPackaging = true } // Better for Zero-Copy memory mapping
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2026.05.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
}
