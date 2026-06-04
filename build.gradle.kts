plugins {
    id("com.android.application")
    // No id("org.jetbrains.kotlin.android") — removed in AGP 9.x.
    // Kotlin (KGP 2.2.10) is bundled inside AGP 9.1.1 automatically.
}

android {
    namespace = "com.gguf.ipc"
    compileSdk = 36

    // AGP 9.1.1 default NDK. Pin explicitly so CI and local builds match.
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.gguf.ipc"
        minSdk = 27          // ASharedMemory_setProt requires API 27
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17 -O3 -flto -march=armv8.4a+dotprod")
                cFlags("-O3 -flto -march=armv8.4a+dotprod")
                arguments(
                    "-DANDROID_STL=c++_shared",
                    "-DGGML_VULKAN=ON",
                    // Required for Android NDK cross-compilation
                    "-DGGML_OPENMP=OFF",
                    "-DGGML_LLAMAFILE=OFF",
                    "-DLLAMA_BUILD_TESTS=OFF",
                    "-DLLAMA_BUILD_EXAMPLES=OFF",
                    "-DGGML_BACKEND_DL=OFF"
                )
                abiFilters += "arm64-v8a"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // In AGP 9.x the Compose compiler is bundled — no kotlinCompilerExtensionVersion needed.
    // composeOptions block is removed entirely.

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2025.05.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
}
